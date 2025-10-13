(ns mcp-clj.mcp-client.core
  "MCP client implementation with initialization support"
  (:require
    [mcp-clj.client-transport.factory :as transport-factory]
    [mcp-clj.client-transport.protocol :as transport-protocol]
    [mcp-clj.log :as log]
    [mcp-clj.mcp-client.logging :as logging]
    [mcp-clj.mcp-client.prompts :as prompts]
    [mcp-clj.mcp-client.resources :as resources]
    [mcp-clj.mcp-client.session :as session]
    [mcp-clj.mcp-client.subscriptions :as subscriptions]
    [mcp-clj.mcp-client.tools :as tools]
    [mcp-clj.mcp-client.transport :as transport]
    [mcp-clj.versions :as version])
  (:import
    (java.lang
      AutoCloseable)
    (java.util.concurrent
      CompletableFuture
      ExecutionException
      TimeUnit
      TimeoutException)))

;; Client Record

(declare close!)

(defrecord MCPClient
  [transport ; Transport implementation (stdio, http, etc)
   session ; Session state (atom)
   subscription-registry ; Subscription registry for notifications
   initialization-future] ; CompletableFuture for initialization process
  AutoCloseable

  (close [this] (close! this))) ; Session state (atom)

;; Initialization Protocol

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

(defn- create-notification-handler
  "Create notification handler that dispatches to subscription registry"
  [subscription-registry]
  (fn [notification]
    (log/debug :client/notification-received {:notification notification})
    (try
      (subscriptions/dispatch-notification! subscription-registry notification)
      (catch Exception e
        (log/error :client/notification-handler-error
                   {:notification notification
                    :error e})))))

;; Client Management

(defn create-client
  "Create MCP client with specified transport and automatically initialize.

  Config options:
  - :transport - Transport configuration map with :type and type-specific options
    - For HTTP: {:type :http :url \"http://...\" :num-threads 2}
    - For Stdio: {:type :stdio :command \"clojure\" :args [\"-M:stdio-server\"]}
  - :client-info - Client identification information
  - :capabilities - Client capabilities
  - :protocol-version - MCP protocol version (defaults to latest)"
  ^AutoCloseable [{:keys [_transport client-info capabilities protocol-version]
                   :or {protocol-version (version/get-latest-version)}
                   :as config}]
  (let [subscription-registry (subscriptions/create-registry)
        notification-handler (create-notification-handler subscription-registry)
        ;; Add notification handler to transport config
        transport-config (assoc-in config [:transport :notification-handler] notification-handler)
        transport (transport-factory/create-transport transport-config)
        session (session/create-session
                  (cond->
                    {:client-info client-info
                     :capabilities capabilities}
                    protocol-version
                    (assoc :protocol-version protocol-version)))
        client (->MCPClient transport (atom session) subscription-registry nil)
        init-future (start-initialization! client)]
    ;; Set up automatic cache invalidation for tools and prompts
    (tools/setup-cache-invalidation! client)
    (prompts/setup-cache-invalidation! client)
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

  Returns the server's initialization response containing:
  - :protocolVersion - Negotiated protocol version
  - :capabilities - Server capabilities map
  - :serverInfo - Server information (name, version, etc.)

  Throws exception if client transitions to :error state or times out waiting."
  ([client] (wait-for-ready client 30000))
  ([client timeout-ms]
   (try
     (.get ^CompletableFuture (:initialization-future client)
           timeout-ms
           TimeUnit/MILLISECONDS)
     ;; Return the initialization response from the session
     (let [session @(:session client)]
       {:protocolVersion (:protocol-version session)
        :capabilities (:server-capabilities session)
        :serverInfo (:server-info session)})
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

;; Tool Calling API

(defn list-tools
  "Discover available tools from the server.

  Returns a map with :tools key containing vector of tool definitions.
  Each tool has :name, :description, and :inputSchema."
  [client]
  (tools/list-tools-impl client))

(defn call-tool
  "Execute a tool with the given name and arguments.

  Returns a CompletableFuture that will contain the parsed tool result on success.
  The future will complete exceptionally on error.
  Content can be text, images, audio, or resource references."
  [client tool-name arguments]
  (tools/call-tool-impl client tool-name arguments))

(defn available-tools?
  "Check if any tools are available from the server.

  Returns true if tools are available, false otherwise.
  Uses cached tools if available, otherwise queries the server."
  [client]
  (tools/available-tools?-impl client))

;; Prompt Calling API

(defn list-prompts
  "Discover available prompts from the server.

  Returns a CompletableFuture that will contain a map with :prompts key
  containing vector of prompt definitions. Each prompt has :name, :description,
  and :arguments.

  Supports pagination with optional options map containing :cursor."
  ([client]
   (prompts/list-prompts-impl client))
  ([client options]
   (prompts/list-prompts-impl client options)))

(defn get-prompt
  "Get a specific prompt with optional arguments for templating.

  Returns a CompletableFuture that will contain the prompt result on success.
  The future will complete exceptionally on error.

  Arguments are used for template substitution in prompt messages."
  ([client prompt-name]
   (prompts/get-prompt-impl client prompt-name))
  ([client prompt-name arguments]
   (prompts/get-prompt-impl client prompt-name arguments)))

(defn available-prompts?
  "Check if any prompts are available from the server.

  Returns true if prompts are available, false otherwise.
  Uses cached prompts if available, otherwise queries the server."
  [client]
  (prompts/available-prompts?-impl client))

;; Resource API

(defn list-resources
  "Discover available resources from the server.

  Returns a CompletableFuture that will contain a map with :resources key
  containing vector of resource definitions. Each resource has :uri, :name,
  and optional :title, :description, :mimeType, :size, :annotations.

  Supports pagination with optional options map containing :cursor."
  ([client]
   (resources/list-resources-impl client))
  ([client options]
   (resources/list-resources-impl client options)))

(defn read-resource
  "Read a specific resource by URI.

  Returns a CompletableFuture that will contain the resource content on success.
  The future will complete exceptionally on error.

  Content can be text (with :text field) or binary (with :blob field containing base64)."
  [client resource-uri]
  (resources/read-resource-impl client resource-uri))

(defn available-resources?
  "Check if any resources are available from the server.

  Returns true if resources are available, false otherwise.
  Uses cached resources if available, otherwise queries the server."
  [client]
  (resources/available-resources?-impl client))

;; Subscription API

(defn subscribe-resource!
  "Subscribe to resource updates for a specific URI.

  Returns a CompletableFuture that resolves when the subscription is established.
  The callback-fn will be called with notification params when the resource changes.

  Example:
    (subscribe-resource! client \"file:///path/to/file\"
      (fn [notification]
        (println \"Resource updated:\" (:uri notification))))"
  [client uri callback-fn]
  (resources/subscribe-resource-impl! client uri callback-fn))

(defn unsubscribe-resource!
  "Unsubscribe from resource updates for a specific URI.

  Returns a CompletableFuture that resolves when the unsubscription is complete."
  [client uri]
  (resources/unsubscribe-resource-impl! client uri))

(defn subscribe-tools-changed!
  "Subscribe to tools list changed notifications.

  Returns a CompletableFuture that resolves immediately (no server request needed).
  The callback-fn will be called when the server sends tools/list_changed notifications.

  Example:
    (subscribe-tools-changed! client
      (fn [notification]
        (println \"Tools list changed\")))"
  [client callback-fn]
  (tools/subscribe-tools-changed-impl! client callback-fn))

(defn unsubscribe-tools-changed!
  "Unsubscribe from tools list changed notifications.

  Returns a CompletableFuture that resolves immediately."
  [client callback-fn]
  (tools/unsubscribe-tools-changed-impl! client callback-fn))

(defn subscribe-prompts-changed!
  "Subscribe to prompts list changed notifications.

  Returns a CompletableFuture that resolves immediately (no server request needed).
  The callback-fn will be called when the server sends prompts/list_changed notifications.

  Example:
    (subscribe-prompts-changed! client
      (fn [notification]
        (println \"Prompts list changed\")))"
  [client callback-fn]
  (prompts/subscribe-prompts-changed-impl! client callback-fn))

(defn unsubscribe-prompts-changed!
  "Unsubscribe from prompts list changed notifications.

  Returns a CompletableFuture that resolves immediately."
  [client callback-fn]
  (prompts/unsubscribe-prompts-changed-impl! client callback-fn))

(defn subscribe-resources-changed!
  "Subscribe to resources list changed notifications.

  Returns a CompletableFuture that resolves immediately (no server request needed).
  The callback-fn will be called when the server sends resources/list_changed notifications.

  Example:
    (subscribe-resources-changed! client
      (fn [notification]
        (println \"Resources list changed\")))"
  [client callback-fn]
  (resources/subscribe-resources-changed-impl! client callback-fn))

(defn unsubscribe-resources-changed!
  "Unsubscribe from resources list changed notifications.

  Returns a CompletableFuture that resolves immediately."
  [client callback-fn]
  (resources/unsubscribe-resources-changed-impl! client callback-fn))

(defn set-log-level!
  "Set the minimum log level for this client session.

  The server will only send log messages at or above the specified level.
  This controls server-side filtering - all subscribed callbacks receive
  the same filtered messages.

  Args:
    client - MCP client instance
    level - Log level keyword (:debug :info :notice :warning :error :critical :alert :emergency)

  Returns:
    CompletableFuture<EmptyObject> that completes when server acknowledges the level change.

  Throws:
    ExceptionInfo with :invalid-log-level if level is not one of the 8 RFC 5424 levels.

  Logs a warning if server doesn't declare logging capability.

  Example:
    @(set-log-level! client :warning)
    ;; Server will only send :warning, :error, :critical, :alert, :emergency"
  [client level]
  (logging/set-log-level-impl! client level))

(defn subscribe-log-messages!
  "Subscribe to log messages from the server.

  The server filters messages based on the level set via set-log-level!.
  All subscribers receive the same filtered messages.

  Args:
    client - MCP client instance
    callback - Function called with log message map: (fn [{:keys [level logger data]}] ...)

  The callback receives:
    :level - Keyword log level (:error, :warning, etc.)
    :logger - Optional string component name (may be nil)
    :data - Message data (map, string, or other value)

  Multiple subscribers are supported. Each will receive all log messages.

  Callback exceptions are caught and logged to avoid crashing the client.

  Returns:
    CompletableFuture<Function> that resolves to an unsubscribe function.
    Call the unsubscribe function to stop receiving messages: (unsub)

  Example:
    (-> (subscribe-log-messages!
          client
          (fn [{:keys [level logger data]}]
            (println level logger data)))
        (deref)
        (def unsub))
    ;; Later: (unsub)"
  [client callback]
  (logging/subscribe-log-messages-impl! client callback))
