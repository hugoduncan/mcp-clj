(ns mcp-clj.mcp-server.core
  "MCP server implementation supporting the Anthropic Model Context Protocol"
  (:require
   [mcp-clj.json-rpc.protocols :as json-rpc-protocols]
   [mcp-clj.json-rpc.sse-server :as sse-server]
   [mcp-clj.json-rpc.stdio-server :as stdio-server]
   [mcp-clj.log :as log]
   [mcp-clj.mcp-server.prompts :as prompts]
   [mcp-clj.mcp-server.resources :as resources]
   [mcp-clj.mcp-server.version :as version]
   [mcp-clj.tools.core :as tools]))

(defrecord ^:private Session
           [^String session-id
            initialized?
            client-info
            client-capabilities
            protocol-version])

(defrecord ^:private MCPServer
           [json-rpc-server
            session-id->session
            tool-registry
            prompt-registry
            resource-registry])

(defn- request-session-id [request]
  (get (:query-params request) "session_id"))

(defn- request-session
  [server request]
  (let [session-id (request-session-id request)
        session-id->session (:session-id->session server)]
    (get @session-id->session session-id)))

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

(defn- notify-resource-updated!
  "Notify all sessions that a resource has been updated"
  [server uri]
  (log/info :server/notify-resource-updated {:server server :uri uri})
  (json-rpc-protocols/notify-all!
   @(:json-rpc-server server)
   "notifications/resources/updated"
   {:uri uri}))

(defn- text-map [msg]
  {:type "text" :text msg})

(defn- negotiate-initialization
  "Negotiate initialization request according to MCP specification"
  [{:keys [protocolVersion capabilities clientInfo] :as params}]
  (let [negotiation (version/negotiate-version protocolVersion)
        {:keys [negotiated-version client-was-supported? supported-versions]} negotiation
        warnings (when-not client-was-supported?
                   [(str "Client version " protocolVersion " not supported. "
                         "Using " negotiated-version ". "
                         "Supported versions: " (pr-str supported-versions))])]
    {:negotiation negotiation
     :client-info clientInfo
     :response {:serverInfo {:name "mcp-clj"
                             :title "MCP Clojure Server"
                             :version "0.1.0"}
                :protocolVersion negotiated-version
                :capabilities {;; :logging   {} needs to implement logging/setLevel
                               :tools {:listChanged true}
                               :resources {:listChanged false
                                           :subscribe false}
                               :prompts {:listChanged true}}
                :instructions "mcp-clj is used to interact with a clojure REPL."
                :warnings warnings}}))

(defn- handle-initialize
  "Handle initialize request from client"
  [server params]
  (log/info :server/initialize params)
  (let [{:keys [negotiation client-info response]} (negotiate-initialization params)
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
    (swap! (:session-id->session server)
           update (:session-id session)
           assoc :initialized? true)))

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
  [server {:keys [name arguments] :as _params}]
  (log/info :server/tools-call)
  (if-let [{:keys [implementation]} (get @(:tool-registry server) name)]
    (try
      (implementation arguments)
      (catch Throwable e
        {:content [(text-map (str "Error: " (.getMessage e)))]
         :isError true}))
    {:content [(text-map (str "Tool not found: " name))]
     :isError true}))

(defn- handle-list-resources
  "Handle resources/list request from client"
  [server params]
  (log/info :server/resources-list)
  (resources/list-resources (:resource-registry server) params))

(defn- handle-read-resource
  "Handle resources/read request from client"
  [server params]
  (log/info :server/resources-read)
  (resources/read-resource (:resource-registry server) params))

(defn- handle-subscribe-resource
  "Handle resources/subscribe request from client"
  [server params]
  (log/info :server/resources-subscribe)
  (resources/subscribe-resource (:resource-registry server) params))

(defn- handle-unsubscribe-resource
  "Handle resources/unsubscribe request from client"
  [server params]
  (log/info :server/resources-unsubscribe)
  (resources/unsubscribe-resource (:resource-registry server) params))

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

(defn- request-handler
  "Wrap a handler to support async responses and session updates"
  [server handler request params]
  (let [response (handler server params)
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
  (update-vals
   {"initialize" handle-initialize
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
   (fn [handler]
     #(request-handler server handler %1 %2))))

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
  (let [session (->Session id false nil nil nil)]
    (log/info :server/sse-connect {:session-id id})
    (swap! (:session-id->session server) assoc id session)))

(defn- on-sse-close
  [server id]
  (swap! (:session-id->session server) dissoc id))

(defn- stop!
  [server]
  (let [rpc-server @(:json-rpc-server server)]
    ;; Close individual sessions only for SSE servers
    (when (contains? rpc-server :session-id->session)
      (doseq [session (vals @(:session-id->session server))]
        (sse-server/close! rpc-server (:session-id session))))))

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

(defn- determine-transport
  "Determine transport type from configuration"
  [{:keys [transport port]}]
  (cond
    (= transport :stdio) :stdio
    (= transport :sse) :sse
    (some? port) :sse
    (nil? transport) :stdio
    :else (throw (ex-info "Unsupported transport type" {:transport transport}))))

(defn- create-json-rpc-server
  "Create JSON-RPC server based on transport type"
  [transport {:keys [port on-sse-connect on-sse-close] :as opts}]
  (case transport
    :sse (sse-server/create-server
          {:port port
           :on-sse-connect on-sse-connect
           :on-sse-close on-sse-close})
    :stdio (stdio-server/create-server
            (into {} (filter (comp some? val) (select-keys opts [:num-threads]))))
    (throw (ex-info "Unsupported transport type" {:transport transport}))))

(defn create-server
  "Create MCP server instance.

  Options:
  - :transport - Transport type (:sse or :stdio). Defaults based on presence of :port
  - :port - Port for SSE server (implies :transport :sse)
  - :num-threads - Number of threads for request handling
  - :tools - Map of tool name to tool definition
  - :prompts - Map of prompt name to prompt definition
  - :resources - Map of resource name to resource definition"
  [{:keys [transport port num-threads tools prompts resources]
    :or {tools tools/default-tools
         prompts prompts/default-prompts
         resources resources/default-resources}
    :as opts}]
  (doseq [tool (vals tools)]
    (when-not (tools/valid-tool? tool)
      (throw (ex-info "Invalid tool in constructor" {:tool tool}))))
  (doseq [prompt (vals prompts)]
    (when-not (prompts/valid-prompt? prompt)
      (throw (ex-info "Invalid prompt in constructor" {:prompt prompt}))))
  (let [actual-transport (determine-transport opts)
        session-id->session (atom {})
        tool-registry (atom tools)
        prompt-registry (atom prompts)
        resource-registry (atom resources)
        rpc-server-prom (promise)
        server (->MCPServer
                rpc-server-prom
                session-id->session
                tool-registry
                prompt-registry
                resource-registry)
        json-rpc-server (create-json-rpc-server
                         actual-transport
                         {:port port
                          :num-threads num-threads
                          :on-sse-connect (partial on-sse-connect server)
                          :on-sse-close (partial on-sse-close server)})
        server (assoc server
                      :stop #(do (stop! server)
                                 (json-rpc-protocols/stop! json-rpc-server)))
        handlers (create-handlers server)]
    (json-rpc-protocols/set-handlers! json-rpc-server handlers)
    (deliver rpc-server-prom json-rpc-server)
    (log/info :server/started {})
    server))
