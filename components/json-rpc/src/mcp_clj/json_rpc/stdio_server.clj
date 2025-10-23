(ns mcp-clj.json-rpc.stdio-server
  "JSON-RPC 2.0 server over stdio"
  (:require
    [mcp-clj.json :as json]
    [mcp-clj.json-rpc.executor :as executor]
    [mcp-clj.json-rpc.json-protocol :as json-protocol]
    [mcp-clj.json-rpc.protocols :as protocols]
    [mcp-clj.json-rpc.stdio :as stdio]
    [mcp-clj.log :as log])
  (:import
    (java.io
      BufferedReader
      InputStreamReader)
    (java.util.concurrent
      RejectedExecutionException)))

;; Configuration

(def ^:private request-timeout-ms 30000)

;; JSON I/O

(defn- read-json
  "Read JSON from reader using unified stdio implementation"
  [reader]
  (stdio/read-json reader))

(defn- write-json!
  "Write JSON response to output stream with locking and error handling"
  [output-stream response]
  (try
    (locking output-stream
      (binding [*out* output-stream]
        ;; Use the original approach that worked with PrintWriter/*out*
        (let [json-str (json/write response)]
          (println json-str)
          (flush))))
    (catch Exception e
      (binding [*out* *err*]
        (log/error :rpc/write {:msg "JSON write error:"
                               :exception (.getMessage e)})))))

;; JSON-RPC Request Handling

(defn- handle-json-rpc
  "Process a JSON-RPC request with simplified handler interface"
  [handler {:keys [method params id]}]
  (log/info :rpc/invoke {:method method :params params})
  (when-let [response (handler method (or params {}))]
    (log/info :server/handler-response response)
    (when response
      (write-json! *out* (json-protocol/json-rpc-result response id)))))

(defn- dispatch-rpc-call
  "Dispatch RPC call with timeout handling"
  [executor handler rpc-call]
  (let [out *out*]
    (executor/submit-with-timeout!
      executor
      #(try
         (binding [*out* out]
           (handle-json-rpc handler rpc-call))
         (catch Throwable e
           (log/error :rpc/handler-error {:error e})
           (write-json!
             out
             (json-protocol/json-rpc-error
               :internal-error
               (.getMessage e)
               (:id rpc-call)))))
      request-timeout-ms)))

(defn- handle-request
  "Handle a JSON-RPC request"
  [executor handlers rpc-call]
  (try
    (log/info :rpc/json-request {:json-request rpc-call})
    (if-let [validation-error (json-protocol/validate-request rpc-call)]
      (write-json!
        *out*
        (json-protocol/json-rpc-error
          (:code (:error validation-error))
          (:message (:error validation-error))
          (:id rpc-call)))
      (if-let [handler (get handlers (:method rpc-call))]
        (dispatch-rpc-call executor handler rpc-call)
        (do
          (log/warn :rpc/no-such-method
                    {:method (:method rpc-call)
                     :available-handlers (keys handlers)})
          (write-json!
            *out*
            (json-protocol/json-rpc-error
              :method-not-found
              (str "Method not found: " (:method rpc-call))
              (:id rpc-call))))))
    (catch RejectedExecutionException _
      (log/warn :rpc/overload-rejection)
      (write-json!
        *out*
        (json-protocol/json-rpc-error :overloaded "Server overloaded")))
    (catch Exception e
      (log/error :rpc/error {:error e})
      (write-json!
        *out*
        (json-protocol/json-rpc-error
          :internal-error
          (.getMessage e)
          (:id rpc-call))))))

;; Server

(defrecord StdioServer
  [executor
   handlers
   out
   server-future
   stop-fn])

(defn- input-reader
  []
  (BufferedReader.
    (InputStreamReader. System/in) 1024))

(defn create-server
  "Create JSON-RPC server over stdio."
  [{:keys [num-threads handlers]
    :or {num-threads (* 2 (.availableProcessors (Runtime/getRuntime)))
         handlers nil}}]
  (log/debug :server/starting {:msg "Starting"})
  (let [executor (executor/create-executor num-threads)
        handlers (atom handlers)
        running (atom true)
        out *out*
        in (input-reader)
        #_(PushbackReader.
           (InputStreamReader. System/in) 1024)

        server-future
        (future
          (binding [*out* out]
            (try
              (log/debug :server/started {:msg "Running"})
              (loop []
                (loop [] (when (and (nil? @handlers) @running)
                           (Thread/sleep 10)
                           (recur)))
                (when @running
                  (let [[rpc-call ex :as resp] (read-json in)
                        _ (log/debug :rpc/call {:call rpc-call})
                        v
                        (cond
                          (nil? resp)
                          ::eof

                          ex
                          (binding [*out* *err*]
                            (println "JSON parse error:" (ex-message ex)))

                          :else
                          (handle-request executor @handlers rpc-call))]
                    (when (not= ::eof v)
                      (recur)))))
              (catch Throwable t
                (binding [*out* *err*]
                  (println t))))))

        stop-fn (fn []
                  (reset! running false)
                  (executor/shutdown-executor executor))]

    (->StdioServer executor handlers *out* server-future stop-fn)))

(defn set-handlers!
  "Set the handler map for the server"
  [server handlers]
  (when-not (map? handlers)
    (throw (ex-info "Handlers must be a map"
                    {:handlers handlers})))
  (reset! (:handlers server) handlers))

(defn stop!
  "Stop the stdio server"
  [server]
  ((:stop-fn server)))

(defn stdio-server?
  [x]
  (instance? StdioServer x))

;; Protocol Implementation

(extend-type StdioServer
  protocols/JsonRpcServer
  (set-handlers! [server handlers]
    (when-not (map? handlers)
      (throw (ex-info "Handlers must be a map"
                      {:handlers handlers})))
    (reset! (:handlers server) handlers))

  (notify! [server _session-id method params]
    (log/debug :server/notify! {:method method :params params})
    (write-json!
      (:out server)
      (json-protocol/json-rpc-notification method params)))

  (notify-all! [server method params]
    (log/debug :server/notify-all! {:method method :params params})
    (write-json!
      (:out server)
      (json-protocol/json-rpc-notification method params)))

  (stop! [server]
    ((:stop-fn server))))
