(ns mcp-clj.mcp-server.core
  "MCP server implementation supporting the Anthropic Model Context Protocol"
  (:require
   [mcp-clj.json-rpc.sse-server :as json-rpc]
   [mcp-clj.log :as log]
   [mcp-clj.mcp-server.prompts :as prompts]
   [mcp-clj.mcp-server.resources :as resources]
   [mcp-clj.tools.core :as tools]))

(def ^:private server-protocol-version "2024-11-05")
(def ^:private required-client-version "2024-11-05")

(defrecord ^:private Session
           [^String session-id
            initialized?
            client-info
            client-capabilities])

(defrecord ^:private MCPServer
           [json-rpc-server
            session-id->session
            tool-registry
            prompt-registry
            resource-registry])

(defn- request-session-id [request]
  (get ((:query-params request)) "session_id"))

(defn- request-session
  [server request]
  (let [session-id (request-session-id request)
        session-id->session (:session-id->session server)]
    (get @session-id->session session-id)))

(defn- notify-tools-changed!
  "Notify all sessions that the tool list has changed"
  [server]
  (log/info :server/notify-tools-changed {:server server})
  (json-rpc/notify-all!
   @(:json-rpc-server server)
   "notifications/tools/list_changed"
   nil))

(defn- notify-prompts-changed!
  "Notify all sessions that the prompt list has changed"
  [server]
  (log/info :server/notify-prompts-changed {:server server})
  (json-rpc/notify-all!
   @(:json-rpc-server server)
   "notifications/prompts/list_changed"
   nil))

(defn- notify-resources-changed!
  "Notify all sessions that the resource list has changed"
  [server]
  (log/info :server/notify-resources-changed {:server server})
  (json-rpc/notify-all!
   @(:json-rpc-server server)
   "notifications/resources/list_changed"
   nil))

(defn- notify-resource-updated!
  "Notify all sessions that a resource has been updated"
  [server uri]
  (log/info :server/notify-resource-updated {:server server :uri uri})
  (json-rpc/notify-all!
   @(:json-rpc-server server)
   "notifications/resources/updated"
   {:uri uri}))

(defn- text-map [msg]
  {:type "text" :text msg})

(defn- validate-initialization!
  "Validate initialization request"
  [{:keys [protocolVersion capabilities]}]
  (when (not= protocolVersion required-client-version)
    {:isError true
     :content [(text-map "Unsupported MCP protocol version")
               (text-map (str "Expected: " required-client-version))
               (text-map (str "Client: " protocolVersion))]})
  #_(when-not (get-in capabilities [:tools])
      (throw (ex-info "Client must support tools capability"
                      {:code -32001
                       :data {:missing [:tools]}}))))

(defn- handle-initialize
  "Handle initialize request from client"
  [_server params]
  (log/info :server/initialize)
  (or (validate-initialization! params)
      {:serverInfo {:name "mcp-clj"
                    :version "0.1.0"}
       :protocolVersion server-protocol-version
       :capabilities {:tools {:listChanged true}
                      :resources {:listChanged false
                                  :subscribe false}
                      :prompts {:listChanged true}}
       :instructions "mcp-clj is used to interact with a clojure REPL."}))

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
  "Wrap a handler to support async responses"
  [server handler request params]
  (let [response (handler server params)]
    (if (fn? response)
      (let [session (request-session server request)]
        (if session
          (do
            (response session)
            nil)
          (log/error "missing mcp session")))
      response)))

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
  (let [session (->Session id false nil nil)]
    (log/info :server/sse-connect {:session-id id})
    (swap! (:session-id->session server) assoc id session)))

(defn- on-sse-close
  [server id]
  (swap! (:session-id->session server) dissoc id))

(defn- stop!
  [server]
  (doseq [session (vals @(:session-id->session server))]
    (json-rpc/close!
     @(:json-rpc-server server)
     (:session-id session))))

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
  "Create MCP server instance"
  [{:keys [port tools prompts resources]
    :or {tools tools/default-tools
         prompts prompts/default-prompts
         resources resources/default-resources}}]
  (doseq [tool (vals tools)]
    (when-not (tools/valid-tool? tool)
      (throw (ex-info "Invalid tool in constructor" {:tool tool}))))
  (doseq [prompt (vals prompts)]
    (when-not (prompts/valid-prompt? prompt)
      (throw (ex-info "Invalid prompt in constructor" {:prompt prompt}))))
  (let [session-id->session (atom {})
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
        json-rpc-server (json-rpc/create-server
                         {:port port
                          :on-sse-connect (partial on-sse-connect server)
                          :on-sse-close (partial on-sse-close server)})
        server (assoc server
                      :stop #(do (stop! server)
                                 ((:stop json-rpc-server))))
        handlers (create-handlers server)]
    (json-rpc/set-handlers! json-rpc-server handlers)
    (deliver rpc-server-prom json-rpc-server)
    server))
