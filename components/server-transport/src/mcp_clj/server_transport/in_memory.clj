(ns mcp-clj.server-transport.in-memory
  "In-memory transport server for unit testing MCP client/server communication"
  (:require
   [mcp-clj.json-rpc.protocols :as json-rpc-protocols]
   [mcp-clj.log :as log])
  (:import
   [java.util.concurrent TimeUnit CompletableFuture Executors]
   [java.util.concurrent.atomic AtomicBoolean]))

;;; Server Implementation

(defrecord InMemoryServer
           [shared-transport ; SharedTransport instance from client-transport.in-memory
            server-alive? ; AtomicBoolean for server status  
            handlers] ; Atom containing handler map

  json-rpc-protocols/JSONRPCServer
  (start! [_]
    (log/info :in-memory/server-started {})
    ;; Server is "started" immediately for in-memory transport
    )

  (stop! [_]
    (.set server-alive? false)
    (.set (:alive? shared-transport) false)
    (log/info :in-memory/server-stopped {}))

  (set-handlers! [_ handler-map]
    (reset! handlers handler-map)
    (log/debug :in-memory/handlers-set {:handler-count (count handler-map)}))

  (alive? [_]
    (and (.get server-alive?)
         (.get (:alive? shared-transport)))))

;;; Message Processing

(defn- handle-request
  "Process a client request and send response"
  [server request]
  (let [{:keys [handlers shared-transport]} server
        handler-map @handlers
        {:keys [id method params]} request]
    (if-let [handler (get handler-map method)]
      (try
        (let [result (handler params)]
          (when id ; Only send response for requests (not notifications)
            (let [response {:jsonrpc "2.0"
                            :id id
                            :result result}]
              (.offer (:server-to-client-queue shared-transport) response)
              (log/debug :in-memory/response-sent {:request-id id :method method}))))
        (catch Exception e
          (when id
            (let [error-response {:jsonrpc "2.0"
                                  :id id
                                  :error {:code -32603
                                          :message "Internal error"
                                          :data {:error (.getMessage e)}}}]
              (.offer (:server-to-client-queue shared-transport) error-response)
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
          (.offer (:server-to-client-queue shared-transport) error-response)
          (log/warn :in-memory/method-not-found {:method method}))))))

(defn- start-server-message-processor!
  "Start processing messages from client to server"
  [server]
  (let [{:keys [shared-transport server-alive?]} server
        {:keys [client-to-server-queue alive?]} shared-transport
        executor (Executors/newSingleThreadExecutor)]
    (.submit executor
             ^Runnable
             (fn []
               (loop []
                 (when (and (.get server-alive?) (.get alive?))
                   (try
                     (when-let [message (.poll client-to-server-queue 100 TimeUnit/MILLISECONDS)]
                       (log/debug :in-memory/server-received-message {:message message})
                       (handle-request server message))
                     (catch InterruptedException _
                       ;; Thread interrupted, exit
                       )
                     (catch Exception e
                       (log/error :in-memory/server-processor-error {:error (.getMessage e)})))
                   (recur)))))))

;;; Factory Function

(defn create-in-memory-server
  "Create in-memory server transport.
  
  Options:
  - :shared - SharedTransport instance (required)
  
  The shared transport should be the same instance used by the client."
  [options handlers]
  (let [{:keys [shared]} options]
    (when-not shared
      (throw (ex-info "Missing :shared transport in server configuration"
                      {:config options})))
    (let [server (->InMemoryServer
                  shared
                  (AtomicBoolean. true)
                  (atom handlers))]
      ;; Start message processing
      (start-server-message-processor! server)
      (log/info :in-memory/server-created {})
      server)))