(ns mcp-clj.in-memory-transport.server
  "In-memory server transport for unit testing MCP communication"
  (:require
    [mcp-clj.in-memory-transport.atomic :as atomic]
    [mcp-clj.in-memory-transport.shared :as shared]
    [mcp-clj.json-rpc.protocols :as json-rpc-protocols]
    [mcp-clj.log :as log])
  (:import
    (java.util.concurrent
      Executors)))

(defrecord InMemoryServer
  [shared-transport server-alive? handlers])

(defn- handle-request
  "Process a client request and send response"
  [server request]
  (let [{:keys [handlers shared-transport]} server
        handler-map @handlers
        {:keys [id method params]} request]
    (if-let [handler (get handler-map method)]
      (try
        ;; Create a proper request object with session info for MCP server compatibility
        ;; The in-memory transport uses a single implicit session per connection
        (let [request-obj {:query-params {"session_id" "in-memory-session"}
                           :method method
                           :params params
                           :id id}
              result (handler request-obj params)]
          (when id ; Only send response for requests (not notifications)
            (let [response {:jsonrpc "2.0"
                            :id id
                            :result result}]
              (shared/offer-to-client! shared-transport response)
              (log/debug :in-memory/response-sent {:request-id id :method method}))))
        (catch Exception e
          (when id
            (let [error-response {:jsonrpc "2.0"
                                  :id id
                                  :error {:code -32603
                                          :message "Internal error"
                                          :data {:error (.getMessage e)}}}]
              (shared/offer-to-client! shared-transport error-response)
              (log/error :in-memory/handler-error
                         {:request-id id
                          :method method
                          :error (.getMessage e)})))))
      ;; Method not found
      (when id
        (let [error-response {:jsonrpc "2.0"
                              :id id
                              :error {:code -32601
                                      :message "Method not found"
                                      :data {:method method}}}]
          (shared/offer-to-client! shared-transport error-response)
          (log/warn :in-memory/method-not-found {:method method}))))))

(defn- start-server-message-processor!
  "Start processing messages from client to server"
  [server]
  (let [{:keys [shared-transport server-alive?]} server
        executor (Executors/newSingleThreadExecutor)]
    (.submit executor
             ^Runnable
             (fn []
               (loop []
                 (when (and (atomic/get-boolean server-alive?) (shared/transport-alive? shared-transport))
                   (try
                     (when-let [message (shared/poll-from-client! shared-transport 100)]
                       (log/debug :in-memory/server-received-message {:message message})
                       (handle-request server message))
                     (catch InterruptedException _
                            ;; Thread interrupted, exit
                            )
                     (catch Exception e
                       (log/error :in-memory/server-processor-error {:error (.getMessage e)})))
                   (recur)))))))

(defn create-in-memory-server
  "Create in-memory server transport.

  Options:
  - :shared - SharedTransport instance (required)
  - :on-connect - Function called when client connects (optional)
  - :on-disconnect - Function called when client disconnects (optional)

  The shared transport should be the same instance used by the client."
  [options handlers]
  (let [{:keys [shared]} options]
    (when-not shared
      (throw (ex-info "Missing :shared transport in server configuration"
                      {:config options})))
    (let [server (->InMemoryServer
                   shared
                   (atomic/create-atomic-boolean true)
                   (atom handlers))]
      ;; Start message processing
      (start-server-message-processor! server)
      (log/info :in-memory/server-created {})
      server)))

(defn start!
  "Start the in-memory server (no-op for in-memory transport)"
  [_server]
  (log/info :in-memory/server-started {}))

(defn stop!
  "Stop the in-memory server"
  [server]
  (atomic/set-boolean! (:server-alive? server) false)
  (shared/set-transport-alive! (:shared-transport server) false)
  (log/info :in-memory/server-stopped {}))

(defn alive?
  "Check if the in-memory server is alive"
  [server]
  (and (atomic/get-boolean (:server-alive? server))
       (shared/transport-alive? (:shared-transport server))))

;; Protocol Implementation

(extend-type InMemoryServer
  json-rpc-protocols/JsonRpcServer
  (set-handlers! [server handler-map]
    (reset! (:handlers server) handler-map)
    (log/debug :in-memory/handlers-set {:handler-count (count handler-map)}))

  (notify! [server _session-id method params]
    (log/debug :in-memory/notify {:method method :params params})
    ;; In-memory transport has single client, so notify! behaves same as notify-all!
    (let [notification {:jsonrpc "2.0"
                        :method method
                        :params params}]
      (shared/offer-to-client! (:shared-transport server) notification)
      (log/debug :in-memory/notification-sent {:method method})))

  (notify-all! [server method params]
    (log/debug :in-memory/notify-all {:method method :params params})
    ;; Send notification to client via shared transport
    (let [notification {:jsonrpc "2.0"
                        :method method
                        :params params}]
      (shared/offer-to-client! (:shared-transport server) notification)
      (log/debug :in-memory/notification-sent {:method method})))

  (stop! [server]
    (atomic/set-boolean! (:server-alive? server) false)
    (shared/set-transport-alive! (:shared-transport server) false)
    (log/info :in-memory/server-stopped {})))
