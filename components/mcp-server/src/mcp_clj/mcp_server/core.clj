(ns mcp-clj.mcp-server.core
  "MCP server implementation supporting the Anthropic Model Context Protocol"
  (:require
   [mcp-clj.json-rpc.server :as json-rpc]
   [mcp-clj.log :as log]
   [mcp-clj.mcp-server.tools :as tools])
  (:import
   [java.util.concurrent Executors
    ThreadPoolExecutor
    TimeUnit]))

(def protocol-version "2024-11-05")
(def required-client-version "2024-11-05")

(defrecord Session
    [^String session-id
     initialized?
     client-info
     client-capabilities
     reply!-fn
     close!-fn])

(defrecord MCPServer
    [json-rpc-server
     executor
     session-id->session])

(defn- create-executor
  "Create bounded executor service"
  [threads]
  (Executors/newScheduledThreadPool threads))

(defn- validate-initialization!
  "Validate initialization request"
  [{:keys [protocolVersion capabilities]}]
  (log/info :server/client
    {:protocolVersion protocolVersion
     :capabilities    capabilities})
  (when-not (= protocolVersion required-client-version)
    (throw (ex-info "Unsupported protocol version"
                    {:code -32001
                     :data {:supported required-client-version
                            :received  protocolVersion}})))
  #_(when-not (get-in capabilities [:tools])
      (throw (ex-info "Client must support tools capability"
                      {:code -32001
                       :data {:missing [:tools]}}))))

(defn- request-session-id [request]
  (get ((:query-params request)) "session_id"))

(defn- json-sse-response
  [id response-type data]
  {:event "message"
   :data
   (cond->       {:jsonrpc "2.0"}
     id
     (assoc :id id)
     response-type
     (assoc response-type data))})

(defn- json-response
  [request-params response-type data]
  (cond->       {:jsonrpc "2.0"
                 :id      (:id request-params)}
    response-type
    (assoc response-type data)))

(defn- request-session
  [server request]
  (let [session-id          (request-session-id request)
        session-id->session (:session-id->session server)]
    (get @session-id->session session-id)))

(defn- session-not-found
  []
  {:status 400
   :body   "session_id missing from query parameters"})

(defn- handle-initialize
  "Handle initialize request from client"
  [server request id params]
  (validate-initialization! params)
  (let [session (request-session server request)]
    (log/info :server/initialize {:session-id (:session-id session)})
    (if session
      (do
        (future
          ((:reply!-fn session)
           (json-sse-response
            id
            :result
            {:serverInfo      {:name    "mcp-clj"
                               :version "0.1.0"}
             :protocolVersion protocol-version
             :capabilities    {:tools {:listChanged false}}
             :instructions    "mcp-clj is used to interact with a clojure REPL."})))
        "Accepted")
      (session-not-found))))

(defn- handle-initialized
  "Handle initialized notification"
  [server request id _params]
  (let [session (request-session server request)]
    (log/info :server/initialized {:session-id (:session-id session)})
    "Accepted"))

(defn- ping
  "Handle ping request"
  [server request id _]
  (let [session (request-session server request)]
    (log/info :server/initialized {:session-id (:session-id session)})
    (if session
      (do
        (swap! (:session-id->session server)
               update (:session-id session)
               assoc :initialized? true)
        ((:reply!-fn session) (json-sse-response id :result {}))
        nil)
      (session-not-found))))

(defn- handle-list-tools
  "Handle tools/list request from client"
  [server request id params]
  (let [session (request-session server request)]
    (log/info :server/tools-list {:session-id (:session-id session)})
    (if session
      (do
        (future
          ((:reply!-fn session)
           (json-sse-response
            id
            :result
            (tools/list-tools params))))
        "Accepted")
      (session-not-found))))

(defn- handle-call-tool
  "Handle tools/call request from client"
  [server request id params]
  (let [session (request-session server request)]
    (log/info :server/tools-call {:session-id (:session-id session)})
    (if session
      (do
        (future
          ((:reply!-fn session)
           (json-sse-response
            id
            :result
            (tools/call-tool params))))
        "Accepted")
      (session-not-found))))

(defn create-handlers
  "Create request handlers with server reference"
  [server]
  {"initialize"                (partial handle-initialize server)
   "notifications/initialized" (partial handle-initialized server)
   "ping"                      (partial ping server)
   "tools/list"               (partial handle-list-tools server)
   "tools/call"               (partial handle-call-tool server)})

(defn- shutdown-executor
  "Shutdown executor service gracefully"
  [^ThreadPoolExecutor executor]
  (.shutdown executor)
  (try
    (when-not (.awaitTermination executor 5 TimeUnit/SECONDS)
      (.shutdownNow executor))
    (catch InterruptedException _
      (.shutdownNow executor))))

(defn on-sse-connect
  [server request id reply!-fn close!-fn]
  (let [session (->Session id false nil nil reply!-fn close!-fn)
        uri     (str "/messages?session_id=" id)]
    (log/info :server/sse-connect {:session-id id})
    (swap! (:session-id->session server) assoc id session)
    (reply!-fn {:event "endpoint" :data uri} {:json-encode false})))

(defn on-sse-close
  [server id]
  (swap! (:session-id->session server) dissoc id))

(defn stop!
  [server]
  (doseq [session (vals @(:session-id->session server))]
    ((:close!-fn session))))

(defn create-server
  "Create MCP server instance"
  [{:keys [port tools threads]
    :or   {threads (* 2 (.availableProcessors (Runtime/getRuntime)))}}]
  (let [session-id->session (atom {})
        executor            (create-executor threads)
        server              (->MCPServer nil executor session-id->session)
        handlers            (create-handlers server)
        json-rpc-server     (json-rpc/create-server
                             {:port           port
                              :handlers       handlers
                              :executor       executor
                              :on-sse-connect (partial on-sse-connect server)
                              :on-sse-close   (partial on-sse-close server)})]
    (assoc server
           :json-rpc-server json-rpc-server
           :stop #(do (stop! server)
                      ((:stop json-rpc-server))
                      (shutdown-executor executor)))))
