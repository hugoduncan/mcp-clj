(ns mcp-clj.json-rpc.stdio-server
  "JSON-RPC 2.0 server over stdio"
  (:require
   [clojure.data.json :as json]
   [mcp-clj.json-rpc.executor :as executor]
   [mcp-clj.json-rpc.protocol :as protocol]
   [mcp-clj.log :as log])
  (:import
   [java.util.concurrent RejectedExecutionException]))

;;; Configuration

(def ^:private request-timeout-ms 30000)

;;; JSON I/O

(defn- read-json
  [reader]
  (try
    (let [json-str (json/read reader :key-fn keyword)]
      (when json-str
        [json-str nil]))
    (catch java.io.EOFException _
      nil)
    (catch Exception e
      [:error e])))

(defn- write-json!
  "Write JSON response to output stream"
  [output-stream response]
  (try
    (let [json-str (json/write-str response)]
      (locking output-stream
        (binding [*out* output-stream]
          (println json-str)
          (flush))))
    (catch Exception e
      (binding [*out* *err*]
        (println "JSON write error:" (.getMessage e))))))

;;; JSON-RPC Request Handling

(defn- handle-json-rpc
  "Process a JSON-RPC request with simplified handler interface"
  [handler {:keys [method params id]}]
  (log/info :rpc/invoke {:method method :params params})
  (when-let [response (handler method params)]
    (log/info :server/handler-response response)
    (write-json! *out* (protocol/json-rpc-result response id))))

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
           (protocol/json-rpc-error
            :internal-error
            (.getMessage e)
            (:id rpc-call)))))
     request-timeout-ms)))

(defn- handle-request
  "Handle a JSON-RPC request"
  [executor handlers rpc-call]
  (try
    (log/info :rpc/json-request {:json-request rpc-call})
    (if-let [validation-error (protocol/validate-request rpc-call)]
      (write-json!
       *out*
       (protocol/json-rpc-error
        (:code (:error validation-error))
        (:message (:error validation-error))
        (:id rpc-call)))
      (if-let [handler (get handlers (:method rpc-call))]
        (dispatch-rpc-call executor handler rpc-call)
        (write-json!
         *out*
         (protocol/json-rpc-error
          :method-not-found
          (str "Method not found: " (:method rpc-call))
          (:id rpc-call)))))
    (catch RejectedExecutionException _
      (log/warn :rpc/overload-rejection)
      (write-json!
       *out*
       (protocol/json-rpc-error :overloaded "Server overloaded")))
    (catch Exception e
      (log/error :rpc/error {:error e})
      (write-json!
       *out*
       (protocol/json-rpc-error
        :internal-error
        (.getMessage e)
        (:id rpc-call))))))

;;; Server

(defrecord StdioServer
           [executor
            handlers
            server-future
            stop-fn])

(defn create-server
  "Create JSON-RPC server over stdio."
  [{:keys [num-threads handlers]
    :or   {num-threads (* 2 (.availableProcessors (Runtime/getRuntime)))
           handlers    {}}}]
  (let [executor (executor/create-executor num-threads)
        handlers (atom handlers)
        running  (atom true)
        out      *out*

        server-future
        (future
          (binding [*out* out]
            (try

              (loop []
                (when @running
                  (let [[rpc-call ex :as resp] (read-json *in*)
                        v
                        (cond
                          (nil? resp)
                          ::eof

                          ex
                          (binding [*out* *err*]
                            (println "JSON parse error:" (.getMessage ex)))

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

    (->StdioServer executor handlers server-future stop-fn)))

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

(defn stdio-server? [x]
  (instance? StdioServer x))
