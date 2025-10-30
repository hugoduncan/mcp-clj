(ns mcp-clj.mcp-server.core
  "MCP server implementation supporting the Anthropic Model Context Protocol"
  (:require
    [clojure.set :as set]
    [mcp-clj.json-rpc.protocols :as json-rpc-protocols]
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.logging :as logging]
    [mcp-clj.mcp-server.prompts :as prompts]
    [mcp-clj.mcp-server.resources :as resources]
    [mcp-clj.mcp-server.subscriptions :as subscriptions]
    [mcp-clj.mcp-server.version :as version]
    [mcp-clj.server-transport.factory :as transport-factory]
    [mcp-clj.tools.core :as tools])
  (:import
    (java.lang
      AutoCloseable)))

(declare stop!)

(defrecord ^:private Session
  [^String session-id
   initialized?
   client-info
   client-capabilities
   protocol-version
   log-level])

(defrecord ^:private MCPServer
  [json-rpc-server
   session-id->session
   tool-registry
   prompt-registry
   resource-registry
   resource-subscriptions
   capabilities]

  AutoCloseable

  (close [this] (stop! this)))

(defn- request-session-id
  [request]
  (cond
    ;; For HTTP/SSE transports: request is a map with :query-params
    (map? request) (get (:query-params request) "session_id")
    ;; For STDIO transport: request is just the method string
    :else "stdio"))

(defn- request-session
  [server request]
  (let [session-id (request-session-id request)
        session-id->session (:session-id->session server)]
    (get @session-id->session session-id)))

(defn- client-supports?
  "Check if client supports a specific capability.

  Capability path is a vector of keys, e.g., [:sampling] or [:notifications].
  Returns boolean indicating if the capability is present and truthy."
  [session capability-path]
  (boolean (get-in (:client-capabilities session) capability-path)))

(defn- client-supports-sampling?
  "Check if client supports sampling capability"
  [session]
  (client-supports? session [:sampling]))

(defn- client-supports-notifications?
  "Check if client supports notification handling"
  [session]
  (client-supports? session [:notifications]))

(defn- notify-tools-changed!
  "Notify all sessions that the tool list has changed"
  [server]
  (log/info :server/notify-tools-changed {:server server})
  (json-rpc-protocols/notify-all!
    @(:json-rpc-server server)
    "notifications/tools/list_changed"
    nil))

(defn- notify-prompts-changed!
  "Notify all sessions that the prompt list has changed"
  [server]
  (log/info :server/notify-prompts-changed {:server server})
  (json-rpc-protocols/notify-all!
    @(:json-rpc-server server)
    "notifications/prompts/list_changed"
    nil))

(defn- notify-resources-changed!
  "Notify all sessions that the resource list has changed"
  [server]
  (log/info :server/notify-resources-changed {:server server})
  (json-rpc-protocols/notify-all!
    @(:json-rpc-server server)
    "notifications/resources/list_changed"
    nil))

(defn- notify-resource-updated-all!
  "Internal: Notify all sessions that a resource has been updated"
  [server uri]
  (log/info :server/notify-resource-updated {:server server :uri uri})
  (json-rpc-protocols/notify-all!
    @(:json-rpc-server server)
    "notifications/resources/updated"
    {:uri uri}))

(defn notify-resource-updated!
  "Notify subscribed sessions that a resource has been updated.
  Should be called by resource implementations when content changes.

  Only notifies sessions that have explicitly subscribed to this resource URI."
  [server uri]
  (log/info :server/notify-resource-updated {:uri uri})
  (let [subscribers (subscriptions/get-subscribers
                      @(:resource-subscriptions server)
                      uri)
        rpc-server @(:json-rpc-server server)]
    (if (empty? subscribers)
      (log/debug :server/no-subscribers {:uri uri})
      (do
        (log/info :server/notifying-subscribers
                  {:uri uri :subscriber-count (count subscribers)})
        ;; Use notify-all! which works for all transport types including in-memory
        (json-rpc-protocols/notify-all!
          rpc-server
          "notifications/resources/updated"
          {:uri uri})))))

(defn- text-map
  [msg]
  {:type "text" :text msg})

(defn- transform-tool-result
  "Transform tool implementation result into MCP format"
  [result]
  (cond
    ;; Already in MCP format (has :content and :isError)
    (and (contains? result :content) (contains? result :isError))
    result

    ;; Tool implementation returned {:result "..."} format
    (contains? result :result)
    (do
      (assert false)
      {:content [(text-map (str (:result result)))]
       :isError false})

    ;; Tool implementation returned string directly
    (string? result)
    {:content [(text-map result)]
     :isError false}

    ;; Other formats - convert to string
    :else
    {:content [(text-map (str result))]
     :isError false}))

(defn- negotiate-initialization
  "Negotiate initialization request according to MCP specification.
  
  server-capabilities should contain capability configs like {:logging {}}"
  [server-capabilities {:keys [protocolVersion capabilities clientInfo] :as params}]
  (let [negotiation (version/negotiate-version protocolVersion)
        {:keys [negotiated-version client-was-supported? supported-versions]} negotiation
        warnings (when-not client-was-supported?
                   [(str "Client version " protocolVersion " not supported. "
                         "Using " negotiated-version ". "
                         "Supported versions: " (pr-str supported-versions))])
        ;; Create base server capabilities, conditionally including logging
        base-capabilities (cond-> {:tools {:listChanged true}
                                   :resources {:listChanged true
                                               :subscribe true}
                                   :prompts {:listChanged true}}
                            (contains? server-capabilities :logging)
                            (assoc :logging {}))
        ;; Apply version-specific capability formatting
        version-capabilities (version/handle-version-specific-behavior
                               negotiated-version
                               :capabilities
                               {:capabilities base-capabilities})
        ;; Create base server info
        base-server-info {:name "mcp-clj"
                          :version "0.1.0"
                          :title "MCP Clojure Server"}
        ;; Apply version-specific server info formatting
        version-server-info (version/handle-version-specific-behavior
                              negotiated-version
                              :server-info
                              {:server-info base-server-info})]
    (log/info :server/mcp-version
              {:negotiated-version negotiated-version
               :warnings warnings})
    {:negotiation negotiation
     :client-info clientInfo
     :response {:serverInfo version-server-info
                :protocolVersion negotiated-version
                :capabilities version-capabilities
                :instructions "mcp-clj is used to interact with a clojure REPL."
                :warnings warnings}}))

(defn- handle-initialize
  "Handle initialize request from client"
  [server params]
  (log/info :server/initialize params)
  (let [{:keys [negotiation client-info response]} (negotiate-initialization
                                                     (:capabilities server)
                                                     params)
        {:keys [negotiated-version]} negotiation]
    (when-not (:client-was-supported? negotiation)
      (log/warn :server/version-fallback
                {:client-version (:protocolVersion params)
                 :negotiated-version negotiated-version}))
    ;; Return session update function along with response
    (with-meta
      response
      {:session-update (fn [session]
                         (assoc session
                                :client-info client-info
                                :client-capabilities (:capabilities params)
                                :protocol-version negotiated-version))})))

(defn- handle-initialized
  "Handle initialized notification"
  [server _params]
  (log/info :server/initialized)
  (fn [session]
    (log/info :server/marking-session-initialized {:session-id (:session-id session)})
    (swap! (:session-id->session server)
           update (:session-id session)
           assoc :initialized? true)
    (log/info :server/session-marked-initialized {:session-id (:session-id session)})))

(defn- handle-ping
  "Handle ping request"
  [_server _params]
  (log/info :server/ping)
  {})

(defn- handle-list-tools
  "Handle tools/list request from client"
  [server _params]
  (log/info :server/tools-list)
  {:tools (mapv tools/tool-definition (vals @(:tool-registry server)))})

(defn- handle-call-tool
  "Handle tools/call request from client"
  [server {:keys [name arguments] :as params}]
  (log/info :server/tools-call)
  (let [session-id (-> params meta :session-id)]
    (if-let [{:keys [implementation inputSchema]} (get
                                                    @(:tool-registry server)
                                                    name)]
      (try
        (let [missing-args (set/difference
                             (set (mapv keyword (:required inputSchema)))
                             (set (keys arguments)))]
          (if (empty? missing-args)
            (let [context {:server server :session-id session-id}]
              (transform-tool-result (implementation context arguments)))
            {:content [(text-map
                         (str "Missing args: " (vec missing-args) ", found "
                              (set (keys arguments))))]
             :isError true}))
        (catch Throwable e
          {:content [(text-map (str "Error: " (.getMessage e)))]
           :isError true}))
      {:content [(text-map (str "Tool not found: " name))]
       :isError true})))

(defn- version-aware-handle-call-tool
  "Version-aware wrapper for handle-call-tool"
  [server protocol-version params]
  (let [base-response (handle-call-tool server params)]
    (version/handle-version-specific-behavior
      protocol-version
      :tool-response
      base-response)))

(defn- handle-list-resources
  "Handle resources/list request from client"
  [server params]
  (log/info :server/resources-list)
  (resources/list-resources (:resource-registry server) params))

(defn- handle-read-resource
  "Handle resources/read request from client"
  [server params]
  (log/info :server/resources-read)
  (let [session-id (-> params meta :session-id)
        context {:server server :session-id session-id}]
    (resources/read-resource context (:resource-registry server) params)))

(defn- handle-subscribe-resource
  "Handle resources/subscribe request from client"
  [server params]
  (log/info :server/resources-subscribe params)
  (let [{:keys [uri]} params
        session-id (-> params meta :session-id)]
    (if (some #(= uri (:uri %)) (vals @(:resource-registry server)))
      (do
        (swap! (:resource-subscriptions server)
               subscriptions/subscribe! session-id uri)
        {})
      (throw (ex-info "Resource not found"
                      {:code -32602
                       :message "Resource not found"
                       :data {:uri uri}})))))

(defn- handle-unsubscribe-resource
  "Handle resources/unsubscribe request from client"
  [server params]
  (log/info :server/resources-unsubscribe params)
  (let [{:keys [uri]} params
        session-id (-> params meta :session-id)]
    (swap! (:resource-subscriptions server)
           subscriptions/unsubscribe! session-id uri)
    {}))

(defn- handle-list-prompts
  "Handle prompts/list request from client"
  [server params]
  (log/info :server/prompts-list)
  (prompts/list-prompts (:prompt-registry server) params))

(defn- handle-get-prompt
  "Handle prompts/get request from client"
  [server params]
  (log/info :server/prompts-get)
  (prompts/get-prompt (:prompt-registry server) params))

(defn- handle-logging-set-level
  "Handle logging/setLevel request from client to set minimum log level"
  [server params]
  (log/info :server/logging-set-level {:params params})
  (let [session-id (-> params meta :session-id)
        level-str (:level params)
        level (keyword level-str)]
    (log/info :server/logging-set-level-details
              {:session-id session-id
               :level level
               :level-str level-str
               :valid? (logging/valid-level? level)})
    (if (logging/valid-level? level)
      (do
        (swap! (:session-id->session server)
               assoc-in [session-id :log-level] level)
        (log/info :server/logging-level-updated
                  {:session-id session-id
                   :level level
                   :sessions (keys @(:session-id->session server))
                   :updated-session (get @(:session-id->session server) session-id)})
        {})
      (throw (ex-info "Invalid log level"
                      {:code -32602
                       :message "Invalid params"
                       :data {:level level-str
                              :valid-levels ["debug" "info" "notice" "warning"
                                             "error" "critical" "alert" "emergency"]}})))))

(defn- request-handler
  "Wrap a handler to support async responses and session updates"
  [server handler request params]
  (let [session-id (request-session-id request)
        session (request-session server request)
        ;; Create session on-demand if missing (for in-memory transport)
        session (or session
                    (when session-id
                      (let [new-session (->Session session-id false nil nil nil nil)]
                        (swap! (:session-id->session server) assoc session-id new-session)
                        (log/info :server/session-created {:session-id session-id})
                        new-session)))
        protocol-version (:protocol-version session)
        ;; Add session-id to params metadata for handlers that need it
        params-with-session (with-meta (or params {}) {:session-id session-id})
        ;; Use version-aware handler for tool calls if protocol version is available
        actual-handler (if (and protocol-version (= handler handle-call-tool))
                         #(version-aware-handle-call-tool %1 protocol-version %2)
                         handler)
        response (actual-handler server params-with-session)
        session-update-fn (-> response meta :session-update)]
    (cond
      ;; Handle responses with session updates (like initialize)
      session-update-fn
      (let [session (request-session server request)]
        (when session
          (let [updated-session (session-update-fn session)]
            (swap! (:session-id->session server)
                   assoc (:session-id session) updated-session)))
        ;; Return response without metadata
        (with-meta response {}))

      ;; Handle async responses (functions)
      (fn? response)
      (let [session (request-session server request)]
        (if session
          (do
            (response session)
            nil)
          (do
            (log/warn
              :server/error
              {:msg "missing mcp session"
               :request request
               :params params})
            (response nil)
            nil)))

      ;; Handle regular responses
      :else response)))

(defn- create-handlers
  "Create request handlers with server reference"
  [server]
  (let [base-handlers {"initialize" handle-initialize
                       "notifications/initialized" handle-initialized
                       "ping" handle-ping
                       "tools/list" handle-list-tools
                       "tools/call" handle-call-tool
                       "resources/list" handle-list-resources
                       "resources/read" handle-read-resource
                       "resources/subscribe" handle-subscribe-resource
                       "resources/unsubscribe" handle-unsubscribe-resource
                       "prompts/list" handle-list-prompts
                       "prompts/get" handle-get-prompt}
        ;; Conditionally add logging handler if capability is enabled
        handlers (if (contains? (:capabilities server) :logging)
                   (assoc base-handlers "logging/setLevel" handle-logging-set-level)
                   base-handlers)]
    (update-vals
      handlers
      (fn [handler]
        #(request-handler server handler %1 %2)))))

(defn add-tool!
  "Add or update a tool in a running server"
  [server tool]
  (log/info :server/add-tool!)
  (when-not (tools/valid-tool? tool)
    (throw (ex-info "Invalid tool definition" {:tool tool})))
  (swap! (:tool-registry server) assoc (:name tool) tool)
  (notify-tools-changed! server)
  server)

(defn remove-tool!
  "Remove a tool from a running server"
  [server tool-name]
  (log/info :server/remove-tool!)
  (swap! (:tool-registry server) dissoc tool-name)
  (notify-tools-changed! server)
  server)

(defn add-prompt!
  "Add or update a prompt in a running server"
  [server prompt]
  (log/info :server/add-prompt!)
  (when-not (prompts/valid-prompt? prompt)
    (throw (ex-info "Invalid prompt definition" {:prompt prompt})))
  (swap! (:prompt-registry server) assoc (:name prompt) prompt)
  (notify-prompts-changed! server)
  server)

(defn remove-prompt!
  "Remove a prompt from a running server"
  [server prompt-name]
  (log/info :server/remove-prompt!)
  (swap! (:prompt-registry server) dissoc prompt-name)
  (notify-prompts-changed! server)
  server)

(defn- on-sse-connect
  [server id]
  (let [session (->Session id false nil nil nil nil)]
    (log/info :server/sse-connect {:session-id id})
    (swap! (:session-id->session server) assoc id session)))

(defn- on-sse-close
  [server id]
  (swap! (:session-id->session server) dissoc id)
  (swap! (:resource-subscriptions server)
         subscriptions/unsubscribe-all! id))

(defn- stop!
  [server]
  (let [rpc-server @(:json-rpc-server server)]
    ;; Close individual sessions only for SSE servers
    (when (contains? rpc-server :session-id->session)
      ;; We need to dynamically require sse-server when needed
      (require 'mcp-clj.json-rpc.sse-server)
      (let [close! (ns-resolve 'mcp-clj.json-rpc.sse-server 'close!)]
        (doseq [session (vals @(:session-id->session server))]
          (close! rpc-server (:session-id session)))))))

(defn add-resource!
  "Add or update a resource in a running server"
  [server resource]
  (log/info :server/add-resource!)
  (when-not (resources/valid-resource? resource)
    (throw (ex-info "Invalid resource definition" {:resource resource})))
  (swap! (:resource-registry server) assoc (:name resource) resource)
  (notify-resources-changed! server)
  server)

(defn remove-resource!
  "Remove a resource from a running server"
  [server resource-name]
  (log/info :server/remove-resource!)
  (swap! (:resource-registry server) dissoc resource-name)
  (notify-resources-changed! server)
  server)

(defn create-server
  "Create MCP server instance.

  Options:
  - :transport - Transport configuration map with :type key (:sse, :http, or :stdio)
  - :tools - Map of tool name to tool definition
  - :prompts - Map of prompt name to prompt definition
  - :resources - Map of resource name to resource definition
  - :capabilities - Map of capability configs (e.g., {:logging {}})

  Transport configuration:
  - {:type :stdio :num-threads N} - Standard input/output
  - {:type :sse :port N :allowed-origins [...]} - Server-Sent Events over HTTP
  - {:type :http :port N :num-threads N} - MCP Streamable HTTP transport

  Examples:
    (create-server {:transport {:type :stdio}
                    :tools {...}})
    (create-server {:transport {:type :http :port 3001}
                    :prompts {...}
                    :capabilities {:logging {}}})"
  ^MCPServer
  [{:keys [transport tools prompts resources capabilities]
    :or {tools tools/default-tools
         prompts prompts/default-prompts
         resources resources/default-resources
         capabilities {}}
    :as opts}]
  (when-not transport
    (throw (ex-info "Missing :transport configuration"
                    {:config opts
                     :expected "Map with :type key and transport-specific options"})))
  (when-not (:type transport)
    (throw (ex-info "Missing :type in transport configuration"
                    {:transport transport
                     :supported-types [:stdio :sse :http]})))
  (doseq [tool (vals tools)]
    (when-not (tools/valid-tool? tool)
      (throw (ex-info "Invalid tool in constructor" {:tool tool}))))
  (doseq [prompt (vals prompts)]
    (when-not (prompts/valid-prompt? prompt)
      (throw (ex-info "Invalid prompt in constructor" {:prompt prompt}))))
  (let [session-id->session (atom {})
        ;; For STDIO transport, create a default session BEFORE creating handlers
        ;; to avoid race condition where initialized notification arrives before session exists
        _ (when (= (:type transport) :stdio)
            (let [default-session (->Session "stdio" false nil nil nil nil)]
              (swap! session-id->session assoc "stdio" default-session)
              (log/info :server/stdio-session-created {:session-id "stdio"})))
        tool-registry (atom tools)
        prompt-registry (atom prompts)
        resource-registry (atom resources)
        rpc-server-prom (promise)
        server (->MCPServer
                 rpc-server-prom
                 session-id->session
                 tool-registry
                 prompt-registry
                 resource-registry
                 (atom {})
                 capabilities)
        ;; Create handlers before creating the JSON-RPC server to avoid race
        ;; conditions
        handlers (create-handlers server)
        ;; Add callbacks to transport options
        transport-with-callbacks (merge transport
                                        {:on-sse-connect (partial on-sse-connect server)
                                         :on-sse-close (partial on-sse-close server)})
        json-rpc-server (transport-factory/create-transport
                          transport-with-callbacks
                          handlers)
        server (assoc server
                      :stop #(do
                               (log/info :server/stopping {})
                               (stop! server)
                               (json-rpc-protocols/stop! json-rpc-server)
                               (log/info :server/stopped {})))]
    ;; Set handlers immediately after creating the JSON-RPC server to minimize
    ;; race window
    (json-rpc-protocols/set-handlers! json-rpc-server handlers)
    (deliver rpc-server-prom json-rpc-server)

    (log/info :server/started {})
    server))
