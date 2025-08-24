(ns mcp-clj.mcp-client.stdio
  "MCP client stdio transport - launches server process and communicates via stdin/stdout"
  (:require
   [clojure.data.json :as json]
   [clojure.java.process :as process]
   [mcp-clj.json-rpc.executor :as executor]
   [mcp-clj.log :as log])
  (:import
   [java.io BufferedReader
    BufferedWriter
    InputStreamReader
    OutputStreamWriter]
   [java.util.concurrent CompletableFuture
    ConcurrentHashMap
    TimeUnit]))

;;; Configuration

(def ^:private request-timeout-ms 30000)

;;; Process Management

(defn- build-process-command
  "Build process command from server configuration"
  [server-config]
  (cond
    ;; Claude Code MCP server configuration format
    (and (map? server-config) (:command server-config))
    {:command (into [(:command server-config)] (:args server-config []))
     :env (:env server-config)
     :dir (:cwd server-config)}

    :else
    (throw (ex-info "Invalid server configuration"
                    {:config server-config
                     :expected "Map with :command and :args"}))))

(defn- start-server-process
  "Start MCP server process with given configuration"
  [server-config]
  (let [{:keys [command env dir]} (build-process-command server-config)
        process-opts (cond-> {:in :pipe
                              :out :pipe
                              :err :inherit}
                       env (assoc :env env)
                       dir (assoc :dir dir))
        _ (log/debug :client/process
                     {:command command :env env :dir dir})
        process (apply process/start process-opts command)]

    (log/debug :stdio/process-started {:command command :env env :dir dir})

    {:process process
     :stdin (BufferedWriter. (OutputStreamWriter. (process/stdin process)))
     :stdout (BufferedReader. (InputStreamReader. (process/stdout process)))}))

;;; JSON I/O

(defn- write-json!
  "Write JSON message to process stdin"
  [writer message]
  (try
    (let [json-str (json/write-str message)]
      (log/debug :client/send {:message message})
      (locking writer
        (.write writer json-str)
        (.newLine writer)
        (.flush writer)))
    (catch Exception e
      (log/error :client/write-error {:error e})
      (throw e))))

(defn- read-json
  "Read JSON message from process stdout"
  [reader]
  (try
    (when-let [line (.readLine reader)]
      (let [message (json/read-str line :key-fn keyword)]
        (log/debug :client/receive {:message message})
        message))
    (catch Exception e
      (log/error :client/read-error {:error e})
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
                   running)
        ;; Start background message reader
        reader-future (executor/submit!
                       executor
                       #(message-reader-loop transport))]
    (assoc transport :reader-future reader-future)))

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
  (log/warn :client/cancel-pending-futures)
  (doseq [[_id future] (:pending-requests transport)]
    (.cancel future true))

  ;; Close streams
  (log/warn :client/closing-streams)
  (let [{:keys [stdin stdout]} (:process-info transport)]
    (log/warn :client/closing-streams {:stdin stdin :stdout stdout})
    (try (.close stdin) (catch Exception _))
    #_(try (.close stdout) (catch Exception _)))

  ;; Terminate process
  (log/warn :client/killing-process)
  (let [^Process process (get-in transport [:process-info :process])]
    (try
      (.destroy process)
      (when-not (.waitFor process 5000 TimeUnit/MILLISECONDS)
        (log/warn :client/process-force-kill))
      (catch Exception e
        (log/error :client/process-close-error {:error e}))))

  ;; Shutdown executor
  (executor/shutdown-executor (:executor transport)))

(defn transport-alive?
  "Check if transport process is still alive"
  [transport]
  (and @(:running transport)
       (.isAlive ^Process (get-in transport [:process-info :process]))))
