(ns mcp-clj.in-memory-transport.client
  "In-memory client transport for unit testing MCP communication"
  (:require
    [mcp-clj.client-transport.protocol :as transport-protocol]
    [mcp-clj.in-memory-transport.atomic :as atomic]
    [mcp-clj.in-memory-transport.shared :as shared]
    [mcp-clj.log :as log])
  (:import
    (java.util.concurrent
      CompletableFuture
      Executors
      TimeUnit)))

(defrecord InMemoryTransport
  [shared-transport ; SharedTransport instance
   client-alive? ; AtomicBoolean for client status
   notification-handler] ; Function to handle notifications

  transport-protocol/Transport

  (send-request!
    [_ method params timeout-ms]
    (if-not (shared/transport-alive? shared-transport)
      (CompletableFuture/failedFuture
        (ex-info "Transport is closed" {:transport :in-memory}))
      (let [request-id (shared/next-request-id! shared-transport)
            future (CompletableFuture.)
            request {:jsonrpc "2.0"
                     :id request-id
                     :method method
                     :params params}]
        ;; Register pending request
        (shared/add-pending-request! shared-transport request-id future)
        ;; Send request to server
        (try
          (shared/offer-to-server! shared-transport request)
          (log/debug :in-memory/request-sent {:request-id request-id :method method})
          ;; Set timeout if specified
          (when (and timeout-ms (pos? timeout-ms))
            (let [executor (Executors/newSingleThreadScheduledExecutor)]
              (.schedule
                executor
                ^Runnable
                (fn []
                  (when-not (.isDone future)
                    (shared/remove-pending-request! shared-transport request-id)
                    (.completeExceptionally
                      future
                      (ex-info "Request timeout"
                               {:request-id request-id
                                :timeout-ms timeout-ms}))))
                (long timeout-ms)
                TimeUnit/MILLISECONDS)))
          future
          (catch Exception e
            (shared/remove-pending-request! shared-transport request-id)
            (CompletableFuture/failedFuture e))))))


  (send-notification!
    [_ method params]
    (if-not (shared/transport-alive? shared-transport)
      (CompletableFuture/failedFuture
        (ex-info "Transport is closed" {:transport :in-memory}))
      (let [notification {:jsonrpc "2.0"
                          :method method
                          :params params}]
        (try
          (shared/offer-to-server! shared-transport notification)
          (log/debug :in-memory/notification-sent {:method method})
          (CompletableFuture/completedFuture nil)
          (catch Exception e
            (CompletableFuture/failedFuture e))))))


  (close!
    [_]
    (atomic/set-boolean! client-alive? false)
    (log/info :in-memory/client-closed {}))


  (alive?
    [_]
    (and (atomic/get-boolean client-alive?)
         (shared/transport-alive? shared-transport)))


  (get-json-rpc-client
    [_]
    ;; Return a minimal client-like object for compatibility
    (reify
      Object
      (toString [_] "InMemoryJSONRPCClient"))))

(defn- start-client-message-processor!
  "Start processing messages from server to client"
  [transport]
  (let [shared-transport (:shared-transport transport)
        executor (Executors/newSingleThreadExecutor)]
    (.submit executor
             ^Runnable
             (fn []
               (loop []
                 (when (shared/transport-alive? shared-transport)
                   (try
                     (when-let [message (shared/poll-from-server! shared-transport 100)]
                       (log/debug :in-memory/client-received-message {:message message})
                       (cond
                         ;; Response to request
                         (:id message)
                         (when-let [future (shared/remove-pending-request!
                                             shared-transport
                                             (:id message))]
                           (if (:error message)
                             (.completeExceptionally
                               future
                               (ex-info "Server error"
                                        {:error (:error message)}))
                             (.complete future (:result message))))

                         ;; Notification from server
                         (and (:method message) (:notification-handler transport))
                         (try
                           ((:notification-handler transport) message)
                           (catch Exception e
                             (log/error :in-memory/notification-handler-error
                                        {:method (:method message)
                                         :error (.getMessage e)})))))
                     (catch InterruptedException _
                            ;; Thread interrupted, exit
                            )
                     (catch Exception e
                       (log/error :in-memory/client-processor-error {:error (.getMessage e)})))
                   (recur)))))))

(defn create-transport
  "Create in-memory transport for MCP client.

  Options:
  - :shared - SharedTransport instance (required)
  - :notification-handler - Function to handle server notifications (optional)"
  [{:keys [shared notification-handler]}]
  (when-not shared
    (throw (ex-info "Missing :shared transport in configuration"
                    {:config {:shared shared}})))
  (let [transport (->InMemoryTransport
                    shared
                    (atomic/create-atomic-boolean true)
                    notification-handler)]
    ;; Start message processing
    (start-client-message-processor! transport)
    (log/info :in-memory/client-created {})
    transport))
