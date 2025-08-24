(ns mcp-clj.mcp-client.stdio
  "MCP client stdio transport - launches server process and communicates via stdin/stdout"
  (:require
   [clojure.data.json :as json]
   [mcp-clj.json-rpc.executor :as executor]
   [mcp-clj.json-rpc.json-protocol :as json-protocol]
   [mcp-clj.log :as log])
  (:import
   [java.io BufferedReader
    BufferedWriter
    InputStreamReader
    OutputStreamWriter]
   [java.lang ProcessBuilder$Redirect]
   [java.util.concurrent
    CompletableFuture
    ConcurrentHashMap
    TimeUnit]))

;;; Configuration

(def ^:private request-timeout-ms 30000)

;;; Process Management

(defn- start-server-process
  "Start MCP server process with given command"
  [server-command]
  (let [process-builder (ProcessBuilder. (into-array String server-command))
        _ (.redirectError process-builder ProcessBuilder$Redirect/INHERIT)
        process (.start process-builder)]
    {:process process
     :stdin (BufferedWriter. (OutputStreamWriter. (.getOutputStream process)))
     :stdout (BufferedReader. (InputStreamReader. (.getInputStream process)))}))

;;; JSON I/O

(defn- write-json!
  "Write JSON message to process stdin"
  [writer message]
  (try
    (let [json-str (json/write-str message)]
      (log/debug :stdio/send {:message message})
      (locking writer
        (.write writer json-str)
        (.newLine writer)
        (.flush writer)))
    (catch Exception e
      (log/error :stdio/write-error {:error e})
      (throw e))))

(defn- read-json
  "Read JSON message from process stdout"
  [reader]
  (try
    (when-let [line (.readLine reader)]
      (let [message (json/read-str line :key-fn keyword)]
        (log/debug :stdio/receive {:message message})
        message))
    (catch Exception e
      (log/error :stdio/read-error {:error e})
      (throw e))))

;;; Transport Implementation

(defrecord StdioTransport
           [server-command
            process-info
            executor
            pending-requests ; ConcurrentHashMap of request-id -> CompletableFuture
            request-id-counter
            reader-future
            running])

(defn- generate-request-id
  "Generate unique request ID"
  [transport]
  (swap! (:request-id-counter transport) inc))

(defn- handle-response
  "Handle JSON-RPC response by completing the corresponding future"
  [transport {:keys [id result error] :as response}]
  (if-let [future (.remove (:pending-requests transport) id)]
    (if error
      (.completeExceptionally future (ex-info "JSON-RPC error" error))
      (.complete future result))
    (log/warn :stdio/orphan-response {:response response})))

(defn- handle-notification
  "Handle JSON-RPC notification (no response expected)"
  [transport notification]
  (log/info :stdio/notification {:notification notification}))

(defn- message-reader-loop
  "Background loop to read messages from server"
  [transport]
  (let [{:keys [stdout]} (:process-info transport)]
    (try
      (loop []
        (when @(:running transport)
          (when-let [message (read-json stdout)]
            (if (:id message)
              (handle-response transport message)
              (handle-notification transport message))
            (recur))))
      (catch Exception e
        (log/error :stdio/reader-error {:error e})))))

(defn create-transport
  "Create stdio transport by launching MCP server process"
  [server-command]
  (let [process-info (start-server-process server-command)
        executor (executor/create-executor 2)
        pending-requests (ConcurrentHashMap.)
        request-id-counter (atom 0)
        running (atom true)
        transport (->StdioTransport
                   server-command
                   process-info
                   executor
                   pending-requests
                   request-id-counter
                   nil
                   running)]

    ;; Start background message reader
    (let [reader-future (executor/submit! executor #(message-reader-loop transport))]
      (assoc transport :reader-future reader-future))))

(defn send-request!
  "Send JSON-RPC request and return CompletableFuture of response"
  [transport method params]
  (let [request-id (generate-request-id transport)
        future (CompletableFuture.)
        request {:jsonrpc "2.0"
                 :id request-id
                 :method method
                 :params params}]

    ;; Register pending request
    (.put (:pending-requests transport) request-id future)

    ;; Send request
    (try
      (write-json! (get-in transport [:process-info :stdin]) request)

      ;; Set timeout
      (.orTimeout future request-timeout-ms TimeUnit/MILLISECONDS)

      future
      (catch Exception e
        (.remove (:pending-requests transport) request-id)
        (.completeExceptionally future e)
        future))))

(defn send-notification!
  "Send JSON-RPC notification (no response expected)"
  [transport method params]
  (let [notification {:jsonrpc "2.0"
                      :method method
                      :params params}]
    (write-json! (get-in transport [:process-info :stdin]) notification)))

(defn close!
  "Close transport and terminate server process"
  [transport]
  (reset! (:running transport) false)

  ;; Cancel all pending requests
  (doseq [[_id future] (:pending-requests transport)]
    (.cancel future true))

  ;; Close streams
  (let [{:keys [stdin stdout]} (:process-info transport)]
    (try (.close stdin) (catch Exception _))
    (try (.close stdout) (catch Exception _)))

  ;; Terminate process
  (let [process (get-in transport [:process-info :process])]
    (.destroy process)
    (when-not (.waitFor process 5 TimeUnit/SECONDS)
      (.destroyForcibly process)))

  ;; Shutdown executor
  (executor/shutdown-executor (:executor transport)))

(defn transport-alive?
  "Check if transport process is still alive"
  [transport]
  (and @(:running transport)
       (.isAlive (get-in transport [:process-info :process]))))
