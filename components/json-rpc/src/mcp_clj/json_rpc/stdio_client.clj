(ns mcp-clj.json-rpc.stdio-client
  "JSON-RPC client utilities for stdio communication"
  (:require
   [mcp-clj.json-rpc.executor :as executor]
   [mcp-clj.json-rpc.stdio :as stdio]
   [mcp-clj.log :as log])
  (:import
   [java.io BufferedReader
    BufferedWriter]
   [java.util.concurrent CompletableFuture
    ConcurrentHashMap
    TimeUnit]))

;;; JSONRPClient Record

(defrecord JSONRPClient
           [pending-requests ; ConcurrentHashMap of request-id -> CompletableFuture  
            request-id-counter ; atom for generating unique request IDs
            executor]) ; executor for async operations

;;; JSONRPClient Functions

(defn create-json-rpc-client
  "Create a JSON-RPC client for managing requests and responses"
  ([]
   (create-json-rpc-client {}))
  ([{:keys [num-threads]
     :or {num-threads 2}}]
   (->JSONRPClient
    (ConcurrentHashMap.)
    (atom 0)
    (executor/create-executor num-threads))))

(defn generate-request-id
  "Generate unique request ID using JSONRPClient counter"
  [json-rpc-client]
  (swap! (:request-id-counter json-rpc-client) inc))

(defn handle-response
  "Handle JSON-RPC response by completing the corresponding future"
  [json-rpc-client {:keys [id result error] :as response}]
  (if-let [future (.remove ^ConcurrentHashMap (:pending-requests json-rpc-client) id)]
    (if error
      (.completeExceptionally ^CompletableFuture future (ex-info "JSON-RPC error" error))
      (.complete ^CompletableFuture future result))
    (log/warn :stdio/orphan-response {:response response})))

(defn handle-notification
  "Handle JSON-RPC notification (no response expected)"
  [notification]
  (log/info :stdio/notification {:notification notification}))

(defn message-reader-loop
  "Background loop to read messages from reader and dispatch to response/notification handlers"
  [^BufferedReader reader running-atom json-rpc-client]
  (try
    (loop []
      (when @running-atom
        (when-let [[message error :as result] (stdio/read-json reader)]
          (cond
            error
            (log/error :stdio/read-error {:error error})

            (:id message)
            (handle-response json-rpc-client message)

            :else
            (handle-notification message))
          (recur))))
    (catch Exception e
      (log/error :stdio/reader-error {:error e}))))

(defn write-json-with-locking!
  "Write JSON message with locking for thread safety"
  [^BufferedWriter writer message]
  (locking writer
    (try
      (stdio/write-json! writer message)
      (catch Exception e
        (log/error :stdio/write-error {:error e})
        (throw e)))))

(defn read-json-with-logging
  "Read JSON message with debug logging"
  [^BufferedReader reader]
  (when-let [[message error :as result] (stdio/read-json reader)]
    (if error
      (do
        (log/error :client/read-error {:error error})
        (throw error))
      (do
        (log/debug :client/receive {:message message})
        message))))

(defn send-request!
  "Send JSON-RPC request using JSONRPClient and writer function"
  [json-rpc-client writer-fn method params timeout-ms]
  (let [request-id (generate-request-id json-rpc-client)
        future (java.util.concurrent.CompletableFuture.)
        request {:jsonrpc "2.0"
                 :id request-id
                 :method method
                 :params params}]

    ;; Register pending request
    (.put (:pending-requests json-rpc-client) request-id future)

    ;; Send request using provided writer function
    (try
      (writer-fn request)

      ;; Set timeout
      (.orTimeout future timeout-ms TimeUnit/MILLISECONDS)

      future
      (catch Exception e
        (.remove (:pending-requests json-rpc-client) request-id)
        (.completeExceptionally future e)
        future))))

(defn send-notification!
  "Send JSON-RPC notification using JSONRPClient and writer function"
  [json-rpc-client writer-fn method params]
  (let [notification {:jsonrpc "2.0"
                      :method method
                      :params params}]
    (writer-fn notification)))

(defn close-json-rpc-client!
  "Close the JSON-RPC client and cancel all pending requests"
  [json-rpc-client]
  ;; Cancel all pending requests
  (doseq [[_id future] (:pending-requests json-rpc-client)]
    (.cancel ^CompletableFuture future true))
  (.clear ^ConcurrentHashMap (:pending-requests json-rpc-client))

  ;; Shutdown executor
  (executor/shutdown-executor (:executor json-rpc-client)))
