(ns mcp-clj.mcp-client.core
  "MCP client implementation with initialization support"
  (:require
   [mcp-clj.log :as log]
   [mcp-clj.mcp-client.session :as session]
   [mcp-clj.mcp-client.stdio :as stdio]))

;;; Client Record

(defrecord MCPClient
           [transport ; Transport implementation (stdio)
            session]) ; Session state (atom)

;;; Transport Creation

(defn- create-transport
  "Create transport based on configuration"
  [{:keys [server] :as _config}]
  (if (and (map? server) (:command server))
    ;; Claude Code MCP server configuration (map with :command)
    (stdio/create-transport server)
    (throw
     (ex-info
      "Unsupported server configuration"
      {:server    server
       :supported "Map with :command and :args"}))))

;;; Initialization Protocol

(defn- handle-initialize-response
  "Handle server response to initialize request"
  [session-atom response]
  (try
    (let [{:keys [protocolVersion capabilities serverInfo]} response]
      (log/info :client/initialize-response {:response response})

      ;; Validate protocol version
      (let [current-session  @session-atom
            expected-version (:protocol-version current-session)]
        (when (not= protocolVersion expected-version)
          (throw (ex-info "Protocol version mismatch"
                          {:expected expected-version
                           :received protocolVersion}))))

      ;; Transition to ready state with server info
      (swap! session-atom
             #(session/transition-state!
               %
               :ready
               :server-info serverInfo
               :server-capabilities capabilities))

      (log/info :client/session-ready
        {:server-info  serverInfo
         :capabilities capabilities}))

    (catch Exception e
      (log/error :client/initialize-error {:error e})
      (swap! session-atom
             #(session/transition-state!
               %
               :error
               :error-info {:type  :initialization-failed
                            :error e})))))

(defn- send-initialized-notification
  "Send initialized notification after successful initialization"
  [transport]
  (try
    (stdio/send-notification! transport "notifications/initialized" {})
    (log/info :client/initialized-sent)
    (catch Exception e
      (log/error :client/client {:error e})
      (throw e))))

(defn initialize!
  "Initialize MCP session with server"
  [client]
  (let [session-atom (:session client)
        transport    (:transport client)
        session      @session-atom]

    (when (not= :disconnected (:state session))
      (throw (ex-info "Client not in disconnected state"
                      {:current-state (:state session)})))

    ;; Transition to initializing state
    (swap! session-atom #(session/transition-state! % :initializing))

    ;; Send initialize request
    (log/debug :client/initialize
      {:msg            "Send initialize"
       :process-alive? (.isAlive ^Process (-> transport :process-info :process))})
    (let [init-params     {:protocolVersion (:protocol-version session)
                           :capabilities    (:capabilities session)
                           :clientInfo      (:client-info session)}
          response-future (stdio/send-request!
                           transport
                           "initialize"
                           init-params)]

      (log/info :mcp/initialize-sent {:params init-params})

      ;; Handle response asynchronously
      (.thenAccept response-future
                   (fn [response]
                     (log/debug :client/initialize {:msg "Receivcd response"})
                     (handle-initialize-response session-atom response)

                     ;; Send initialized notification if successful
                     (log/debug :client/initialize
                       {:session-ready? (session/session-ready? @session-atom)})
                     (when (session/session-ready? @session-atom)
                       (send-initialized-notification transport))))

      ;; Handle errors
      (.exceptionally response-future
                      (fn [throwable]
                        (log/error :client/initialize-failed
                          {:alive? (.isAlive ^Process (-> transport :process-info :process))
                           :error  throwable})
                        (swap! session-atom
                               #(session/transition-state!
                                 %
                                 :error
                                 :error-info {:type  :initialization-failed
                                              :error throwable}))
                        nil))

      response-future)))

;;; Client Management

(defn create-client
  "Create MCP client with specified transport"
  [{:keys [client-info capabilities protocol-version] :as config}]
  (let [transport (create-transport config)
        session   (session/create-session
                   (cond->
                       {:client-info      client-info
                        :capabilities     capabilities}
                     protocol-version
                     (assoc :protocol-version protocol-version)))]
    (->MCPClient transport (atom session))))

(defn close!
  "Close client connection and cleanup resources"
  [client]
  (log/info :client/client-closing)
  (let [session-atom (:session client)]
    ;; Transition session to disconnected
    (swap! session-atom #(session/transition-state! % :disconnected))

    ;; Close transport
    (stdio/close! (:transport client))
    (log/info :client/client-closed)))

(defn client-ready?
  "Check if client session is ready for requests"
  [client]
  (session/session-ready? @(:session client)))

(defn client-error?
  "Check if client session is in error state"
  [client]
  (session/session-error? @(:session client)))

(defn get-client-info
  "Get current client and session information"
  [client]
  (let [session @(:session client)]
    (assoc (session/get-session-info session)
           :transport-alive? (stdio/transport-alive? (:transport client)))))

(defn wait-for-ready
  "Wait for client to be ready, with timeout"
  [client timeout-ms]
  (let [start-time (System/currentTimeMillis)
        session-atom (:session client)]
    (loop []
      (let [session @session-atom
            elapsed (- (System/currentTimeMillis) start-time)]
        (cond
          (session/session-ready? session)
          true

          (session/session-error? session)
          (throw (ex-info "Client initialization failed"
                          {:session-info (session/get-session-info session)}))

          (> elapsed timeout-ms)
          (throw (ex-info "Client initialization timeout"
                          {:timeout-ms timeout-ms
                           :elapsed-ms elapsed
                           :session-state (:state session)}))

          :else
          (do
            (Thread/sleep 50) ; Poll every 50ms
            (recur)))))))
