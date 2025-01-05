(ns mcp-clj.mcp-server.core
  "MCP server implementation supporting the Anthropic Model Context Protocol"
  (:require
   [mcp-clj.json-rpc.server :as json-rpc]))

(def protocol-version "0.1")
(def required-client-version "0.1")

(defrecord MCPServer [json-rpc-server state])

(defn handle-initialize
  "Handle initialize request from client.
   Returns server info, protocol version and capabilities."
  [server {:keys [capabilities clientInfo protocolVersion] :as params}]
  (when-not (= protocolVersion required-client-version)
    (throw (ex-info (str "Unsupported protocol version. Required " required-client-version)
                    {:code -32001
                     :data {:supported required-client-version
                            :received  protocolVersion}})))

  (when-not (get-in capabilities [:tools])
    (throw (ex-info "Client must support tools capability"
                    {:code -32001
                     :data {:missing [:tools]}})))

  (swap! (:state server) assoc
         :client-info clientInfo
         :client-capabilities capabilities)

  {:serverInfo      {:name    "mcp-clj"
                     :version "0.1.0"}
   :protocolVersion protocol-version
   :capabilities    {:tools {:listChanged false}}
   :instructions    "MCP server providing tool execution capabilities"})

(defn handle-initialized
  "Handle initialized notification from client."
  [server _params]
  (swap! (:state server) assoc :initialized? true)
  nil)

(defn ping
  "Handle ping request."
  [server _params]
  (when-not (:initialized? @(:state server))
    (throw (ex-info "Server not initialized"
                    {:code -32002
                     :data {:state "uninitialized"}})))
  {})

(defn create-handlers
  "Create handler functions with server reference."
  [server]
  {"initialize"                (partial handle-initialize server)
   "notifications/initialized" (partial handle-initialized server)
   "ping"                      (partial ping server)})

(defn create-server
  "Create MCP server instance.

   Options:
   - :port     Required. Port to listen on
   - :tools    Optional. Map of tool implementations"
  [{:keys [port tools] :as options}]
  (let [state           (atom {:initialized? false})
        handlers        (create-handlers {:state state})
        json-rpc-server (json-rpc/create-server
                         {:port     port
                          :handlers handlers})
        server          (->MCPServer json-rpc-server state)]
    (assoc server
           :stop #(do (reset! state {:initialized? false})
                      ((:stop json-rpc-server))))))
