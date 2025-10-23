(ns mcp-clj.json-rpc.stdio-client
  "JSON-RPC client utilities for stdio communication"
  (:require
    [mcp-clj.json-rpc.executor :as executor]
    [mcp-clj.json-rpc.protocols :as protocols]
    [mcp-clj.json-rpc.stdio :as stdio]
    [mcp-clj.log :as log])
  (:import
    (java.io
      BufferedReader
      BufferedWriter)
    (java.util.concurrent
      CompletableFuture
      ConcurrentHashMap
      Future
      TimeUnit)))

;; Forward declarations
(declare stdio-send-request! stdio-send-notification! close-json-rpc-client!)

;; JSONRPClient Record

(defrecord JSONRPClient
  [pending-requests ; ConcurrentHashMap of request-id -> CompletableFuture
   request-id-counter ; atom for generating unique request IDs
   executor ; executor for async operations
   input-stream ; BufferedReader for reading responses
   output-stream ; BufferedWriter for sending requests
   running ; atom for controlling message reader loop
   reader-future ; future for background message reader
   notification-handler] ; function to handle notifications

  protocols/JSONRPCClient

  (send-request!
    [this method params timeout-ms]
    (stdio-send-request! this method params timeout-ms))


  (send-notification!
    [this method params]
    (stdio-send-notification! this method params))


  (close!
    [this]
    (close-json-rpc-client! this))


  (alive?
    [this]
    @(:running this)))

;; JSONRPClient Functions

(declare message-reader-loop)

(defn create-json-rpc-client
  "Create a JSON-RPC client for managing requests and responses"
  ([input-stream output-stream]
   (create-json-rpc-client input-stream output-stream {}))
  ([input-stream output-stream {:keys [num-threads notification-handler]
                                :or {num-threads 2}}]
   (let [running (atom true)
         client (->JSONRPClient
                  (ConcurrentHashMap.)
                  (atom 0)
                  (executor/create-executor num-threads)
                  input-stream
                  output-stream
                  running
                  nil
                  notification-handler)
         ;; Start background message reader
         reader-future (executor/submit!
                         (:executor client)
                         #(message-reader-loop input-stream running client))]
     (assoc client :reader-future reader-future))))

(defn generate-request-id
  "Generate unique request ID using JSONRPClient counter"
  [json-rpc-client]
  (swap! (:request-id-counter json-rpc-client) inc))

(defn handle-response
  "Handle JSON-RPC response by completing the corresponding future"
  [json-rpc-client {:keys [id result error] :as response}]
  ;; Normalize ID to Long to handle Integer/Long mismatch from JSON parsing
  (let [normalized-id (long id)]
    (if-let [future (.remove ^ConcurrentHashMap (:pending-requests json-rpc-client) normalized-id)]
      (if error
        (.completeExceptionally ^CompletableFuture future (ex-info "JSON-RPC error" error))
        (.complete ^CompletableFuture future result))
      (log/warn :rpc/orphan-response {:response response}))))

(defn handle-notification
  "Handle JSON-RPC notification (no response expected)"
  [json-rpc-client notification]
  (if-let [handler (:notification-handler json-rpc-client)]
    (handler notification)
    (log/info :rpc/notification {:notification notification})))

(defn message-reader-loop
  "Background loop to read messages from JSONRPClient's input stream and dispatch to response/notification handlers"
  [^BufferedReader reader running-atom json-rpc-client]
  (try
    (loop []
      (when @running-atom
        (when-let [[message error] (stdio/read-json reader)]
          (cond
            error
            (log/error :rpc/read-error {:error error})

            (:id message)
            (handle-response json-rpc-client message)

            :else
            (handle-notification json-rpc-client message))
          (recur))))
    (catch Exception e
      (log/error :rpc/reader-error {:error e}))))

(defn write-json-with-locking!
  "Write JSON message with locking for thread safety"
  [^BufferedWriter writer message]
  (locking writer
    (try
      (stdio/write-json! writer message)
      (catch Exception e
        (log/error :rpc/write-error {:error e})
        (throw e)))))

(defn read-json-with-logging
  "Read JSON message with debug logging"
  [^BufferedReader reader]
  (when-let [[message error] (stdio/read-json reader)]
    (if error
      (do
        (log/error :rpc/read-error {:error error})
        (throw error))
      (do
        (log/debug :rpc/receive {:message message})
        message))))

(defn stdio-send-request!
  "Send JSON-RPC request using JSONRPClient's output stream"
  [json-rpc-client method params timeout-ms]
  (let [request-id (generate-request-id json-rpc-client)
        future (CompletableFuture.)
        request {:jsonrpc "2.0"
                 :id request-id
                 :method method
                 :params params}]

    ;; Register pending request
    (.put
      ^ConcurrentHashMap (:pending-requests json-rpc-client)
      request-id future)

    ;; Send request using JSONRPClient's output stream
    (try
      (write-json-with-locking! (:output-stream json-rpc-client) request)

      ;; Set timeout
      (.orTimeout future timeout-ms TimeUnit/MILLISECONDS)

      future
      (catch Exception e
        (.remove
          ^ConcurrentHashMap (:pending-requests json-rpc-client)
          request-id)
        (.completeExceptionally future e)
        future))))

(defn stdio-send-notification!
  "Send JSON-RPC notification using JSONRPClient's output stream"
  [json-rpc-client method params]
  (let [notification {:jsonrpc "2.0"
                      :method method
                      :params params}]
    (write-json-with-locking! (:output-stream json-rpc-client) notification)))

(defn close-json-rpc-client!
  "Close the JSON-RPC client and cancel all pending requests"
  [json-rpc-client]
  (log/debug :rpc/close-json-rpc-client)
  ;; Stop the message reader loop
  (reset! (:running json-rpc-client) false)

  ;; Cancel the reader future if it exists
  (when-let [reader-future (:reader-future json-rpc-client)]
    (.cancel ^Future reader-future true))

  ;; Cancel all pending requests
  (doseq [[_id future] (:pending-requests json-rpc-client)]
    (.cancel ^CompletableFuture future true))
  (.clear ^ConcurrentHashMap (:pending-requests json-rpc-client))

  ;; Shutdown executor
  (log/debug :rpc/close-json-rpc-client {:state :shutdown-exec})
  (executor/shutdown-executor (:executor json-rpc-client))
  (log/debug :rpc/close-json-rpc-client {:state :done}))

;; Public API Functions (for backward compatibility)

(defn send-request!
  "Send JSON-RPC request using JSONRPClient's output stream"
  [json-rpc-client method params timeout-ms]
  (protocols/send-request! json-rpc-client method params timeout-ms))

(defn send-notification!
  "Send JSON-RPC notification using JSONRPClient's output stream"
  [json-rpc-client method params]
  (protocols/send-notification! json-rpc-client method params))
