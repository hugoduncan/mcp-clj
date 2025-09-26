(ns mcp-clj.mcp-client.core
  "MCP client implementation with initialization support"
  (:require
   [mcp-clj.client-transport.factory :as transport-factory]
   [mcp-clj.client-transport.protocol :as transport-protocol]
   [mcp-clj.log :as log]
   [mcp-clj.mcp-client.session :as session]
   [mcp-clj.mcp-client.tools :as tools]
   [mcp-clj.mcp-client.transport :as transport]
   [mcp-clj.mcp-server.version :as version])
  (:import
   [java.lang AutoCloseable]
   [java.util.concurrent CompletableFuture ExecutionException TimeUnit TimeoutException]))

;;; Client Record

(declare close!)

(defrecord MCPClient
           [transport ; Transport implementation (stdio)
            session ; Session state (atom)
            initialization-future] ; CompletableFuture for initialization process
  AutoCloseable
  (close [this] (close! this))) ; Session state (atom)

;;; Initialization Protocol

(defn- handle-initialize-response
  "Handle server response to initialize request"
  [session-atom response]
  (try
    (let [{:keys [protocolVersion capabilities serverInfo]} response]
      (log/info :client/initialize-response {:response response})

      ;; Validate protocol version
      (let [current-session @session-atom
            expected-version (:protocol-version current-session)]
        (when (not= protocolVersion expected-version)
          (throw (ex-info
                  "Protocol version mismatch"
                  {:expected expected-version
                   :received protocolVersion
                   :response response}))))

      ;; Transition to ready state with server info
      (swap! session-atom
             #(session/transition-state!
               %
               :ready
               :server-info serverInfo
               :server-capabilities capabilities))

      (log/info :client/session-ready
                {:server-info serverInfo
                 :capabilities capabilities}))

    (catch Exception e
      (log/error :client/initialize-error {:error e})
      (swap! session-atom
             #(session/transition-state!
               %
               :error
               :error-info {:type :initialization-failed
                            :error e})))))

(defn- send-initialized-notification
  "Send initialized notification after successful initialization"
  [transport]
  (try
    (transport/send-notification! transport "notifications/initialized" {})
    (log/info :client/initialized-sent)
    (catch Exception e
      (log/error :client/client {:error e})
      (throw e))))

(defn- start-initialization!
  "Start client initialization process and return CompletableFuture"
  [client]
  (let [session-atom (:session client)
        transport (:transport client)
        session @session-atom]

    (if (not= :disconnected (:state session))
      (let [error-future (CompletableFuture.)]
        (.completeExceptionally error-future
                                (ex-info "Client not in disconnected state"
                                         {:current-state (:state session)}))
        error-future)

      (do
        ;; Transition to initializing state
        (swap! session-atom #(session/transition-state! % :initializing))

        ;; Send initialize request
        (log/debug :client/initialize {:msg "Send initialize"})
        (let [init-params {:protocolVersion (:protocol-version session)
                           :capabilities (:capabilities session)
                           :clientInfo (:client-info session)}
              response-future (transport/send-request!
                               transport
                               "initialize"
                               init-params
                               30000)]

          (log/debug :mcp/initialize-sent {:params init-params})

          ;; Handle response asynchronously and return a future that completes when ready
          (.thenCompose response-future
                        (fn [response]
                          (let [ready-future (CompletableFuture.)]
                            (try
                              (log/debug :client/initialize
                                         {:msg "Received response"
                                          :response response})
                              (handle-initialize-response session-atom response)

                              ;; Send initialized notification if successful
                              (log/debug :client/initialize
                                         {:session-ready? (session/session-ready? @session-atom)})
                              (when (session/session-ready? @session-atom)
                                (send-initialized-notification transport)
                                (.complete ready-future true))
                              (catch Exception e
                                (.completeExceptionally ready-future e)))
                            ready-future))))))))

;;; Client Management

(defn create-client
  "Create MCP client with specified transport and automatically initialize"
  ^AutoCloseable [{:keys [client-info capabilities protocol-version]
                   :or {protocol-version (version/get-latest-version)}
                   :as config}]
  (let [transport (transport-factory/create-transport config)
        session (session/create-session
                 (cond->
                  {:client-info client-info
                   :capabilities capabilities}
                   protocol-version
                   (assoc :protocol-version protocol-version)))
        client (->MCPClient transport (atom session) nil)
        init-future (start-initialization! client)]
    (assoc client :initialization-future init-future)))

(defn close!
  "Close client connection and cleanup resources"
  [client]
  (log/info :client/client-closing)
  (let [session-atom (:session client)
        transport (:transport client)]
    ;; Transition session to disconnected
    (when-not (= :disconnected (:state @session-atom))
      (swap! session-atom #(session/transition-state! % :disconnected)))

    ;; Close transport using protocol
    (transport-protocol/close! transport)

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
  (let [session @(:session client)
        transport (:transport client)]
    (assoc (session/get-session-info session)
           :transport-alive? (transport-protocol/alive? transport))))

(defn wait-for-ready
  "Wait for client to be ready, with optional timeout (defaults to 30 seconds).

  Returns true when client is ready.
  Throws exception if client transitions to :error state or times out waiting."
  ([client] (wait-for-ready client 30000))
  ([client timeout-ms]
   (try
     (.get ^CompletableFuture (:initialization-future client)
           timeout-ms
           TimeUnit/MILLISECONDS)
     true
     (catch TimeoutException _
       (let [session-state (:state @(:session client))]
         (if (= :error session-state)
           (throw (ex-info "Client initialization failed"
                           {:session-state session-state}))
           (throw (ex-info "Client initialization timeout"
                           {:timeout-ms timeout-ms
                            :session-state session-state})))))
     (catch ExecutionException e
       (throw (.getCause e))))))

;;; Tool Calling API

(defn list-tools
  "Discover available tools from the server.

  Returns a map with :tools key containing vector of tool definitions.
  Each tool has :name, :description, and :inputSchema."
  [client]
  (tools/list-tools-impl client))

(defn call-tool
  "Execute a tool with the given name and arguments.

  Returns the value of the tool call, or throws on error.
  Content can be text, images, audio, or resource references."
  [client tool-name arguments]
  (tools/call-tool-impl client tool-name arguments))

(defn available-tools?
  "Check if any tools are available from the server.

  Returns true if tools are available, false otherwise.
  Uses cached tools if available, otherwise queries the server."
  [client]
  (tools/available-tools?-impl client))
