(ns mcp-clj.json-rpc.server
  "JSON-RPC 2.0 server with Server-Sent Events (SSE) support"
  (:require
   [clojure.data.json :as json]
   [mcp-clj.http :as http]
   [mcp-clj.http-server.adapter :as http-server]
   [mcp-clj.json-rpc.protocol :as protocol]
   [mcp-clj.log :as log]
   [mcp-clj.sse :as sse])
  (:import
   [java.util.concurrent
    ExecutorService
    Executors
    RejectedExecutionException
    ScheduledExecutorService
    ThreadPoolExecutor
    TimeUnit
    TimeUnit]))

;;; Executor Service

(def ^:private request-timeout-ms 30000)

(defn- wrap-log-throwables [f]
  (fn []
    (try
      (f)
      (catch Exception e
        (log/error :rpc/unexpected e)))))

(defn- create-executor
  "Create bounded executor service"
  [num-threads]
  (Executors/newScheduledThreadPool num-threads))

(defn- shutdown-executor
  "Shutdown executor service gracefully"
  [^ThreadPoolExecutor executor]
  (.shutdown executor)
  (try
    (when-not (.awaitTermination executor 5 TimeUnit/SECONDS)
      (.shutdownNow executor))
    (catch InterruptedException _
      (.shutdownNow executor))))

(defn- submit!
  ([executor f]
   (.submit ^ExecutorService executor ^Callable (wrap-log-throwables f)))
  ([executor f delay-millis]
   (.schedule
    ^ScheduledExecutorService executor
    ^Callable (wrap-log-throwables f)
    (long delay-millis)
    TimeUnit/MILLISECONDS)))

(defrecord Session
    [^String session-id
     reply!-fn
     close!-fn])

;;; Response Format

(defn- json-rpc-result
  "Wrap a handler result in a JSON-RPC response"
  [result id]
  {:jsonrpc "2.0"
   :id      id
   :result  result})

(defn- json-rpc-notification
  "Wrap a handler result in a JSON-RPC response"
  [method params]
  (cond-> {:jsonrpc "2.0"
           :method  method}
    params (assoc :params params)))

(defn- json-rpc-error
  "Wrap a handler error in a JSON-RPC error response"
  [code message & [id]]
  (cond-> {:jsonrpc "2.0"
           :error   {:code    (protocol/error-codes code code)
                     :message message}}
    id (assoc :id id)))

;;; Request Handling

(defn- request-session-id [request]
  (get ((:query-params request)) "session_id"))

(defn- handle-json-rpc
  "Process a JSON-RPC request"
  [handler {:keys [method params id]} request reply!-fn]
  (log/info :rpc/invoke {:method method :params params})
  (when-let [response (handler request params)]
    (log/info :server/handler-response response)
    (reply!-fn (json-rpc-result response id))))

(defn- dispatch-rpc-call
  [executor handler rpc-call request reply!-fn]
  ;; futures will cancel each other
  (let [cf (promise)
        f  (submit!
            executor
            #(do (handle-json-rpc handler rpc-call request reply!-fn)
                 (when (realized? cf)
                   (future-cancel @cf))))]
    (deliver
     cf
     (submit! executor #(future-cancel f) request-timeout-ms))))

(defn- handle-request
  "Handle a JSON-RPC request"
  [executor session-id->session handlers request]
  (try
    (let [session-id (request-session-id request)
          session    (session-id->session session-id)
          reply!-fn  (:reply!-fn session)
          rpc-call   (json/read-str (slurp (:body request)) :key-fn keyword)]
      (log/info :rpc/json-request
                {:json-request rpc-call
                 :session-id   session-id})
      (if-let [validation-error (protocol/validate-request rpc-call)]
        (http/json-response
         (json/write-str {:code    (:code validation-error)
                          :message (:message validation-error)})
         http/BadRequest)
        (if-let [handler (get handlers (:method rpc-call))]
          (do
            (dispatch-rpc-call executor handler rpc-call request reply!-fn)
            (http/text-response "Accepted" http/Accepted))
          (http/json-response
           (json-rpc-error
            :method-not-found
            (str "Method not found: " (:method rpc-call))
            (:id rpc-call))
           http/BadRequest))))
    (catch RejectedExecutionException _
      (log/warn :rpc/overload-rejection)
      (http/json-response
       (json-rpc-error :overloaded "Server overloaded")
       http/Unavailable))
    (catch Exception e
      (.printStackTrace e)
      (log/error :rpc/error {:e e})
      (http/json-response
       (json-rpc-error
        :internal-error
        (.getMessage e))
       http/InternalServerError))))

(defn data->str [v]
  (if (string? v)
    (pr-str v)
    (json/write-str v)))

(defn- uuid->hex
  [^java.util.UUID uuid]
  (let [msb (.getMostSignificantBits uuid)
        lsb (.getLeastSignificantBits uuid)]
    (format "%016x%016x" msb lsb)))

(defn create-server
  "Create JSON-RPC server with SSE support"
  [{:keys [num-threads
           port
           on-sse-connect
           on-sse-close]
    :or   {num-threads (* 2 (.availableProcessors (Runtime/getRuntime)))
           port        0}}]
  {:pre [(ifn? on-sse-connect) (ifn? on-sse-close)]}
  (let [executor            (create-executor num-threads)
        session-id->session (atom {})
        handlers            (atom {})
        handler             (fn [{:keys [request-method uri] :as request}]
                              (log/info :rpc/http-request
                                        {:method request-method :uri uri})
                              (case [request-method uri]
                                [:post "/messages"]
                                (handle-request
                                 executor
                                 @session-id->session
                                 @handlers
                                 request)

                                [:get "/sse"]
                                (let [id      (uuid->hex (random-uuid))
                                      uri     (str "/messages?session_id=" id)
                                      {:keys [reply! close! response]}
                                      (sse/handler request)
                                      session (->Session
                                               id
                                               (fn [rpc-response]
                                                 (reply!
                                                  (sse/message
                                                   (data->str rpc-response))))
                                               (fn []
                                                 (on-sse-close id)
                                                 (close!)))]
                                  (swap! session-id->session assoc id session)
                                  (log/info :rpc/sse-connect {:id id})
                                  (update response
                                          :body
                                          (fn [f]
                                            (fn [& args]
                                              (log/info :rpc/on-sse-connect {})
                                              (apply f args)
                                              (reply!
                                               {:event "endpoint" :data uri})
                                              (on-sse-connect id)))))
                                (do
                                  (log/warn :rpc/invalid
                                            {:method request-method :uri uri})
                                  (http/text-response
                                   "Not Found"
                                   http/NotFound))))

        {:keys [server stop]}
        (http-server/run-server handler {:executor executor :port port})
        server {:server              server
                :port                (.getPort (.getAddress server))
                :handlers            handlers
                :stop                (fn []
                                       (stop)
                                       (shutdown-executor executor))
                :session-id->session session-id->session}]
    server))

(defn set-handlers!
  [server handlers]
  (when-not (map? handlers)
    (throw (ex-info "Handlers must be a map"
                    {:handlers handlers})))
  (update server :handlers swap! (constantly handlers)))

(defn close!
  [server id]
  (let [session (@(:session-id->session server) id)]
    ((:close!-fn session))
    (swap! (:session-id->session server) dissoc id)))

(defn notify-all!
  "Send a notification to all active sessions"
  [server method params]
  (log/info :rpc/notify-all! {:method method :params params})
  (doseq [{:keys [reply!-fn] :as session} (vals @(:session-id->session server))]
    (log/info :rpc/notify-all! {:session-id (:session-id session)})
    (reply!-fn (json-rpc-notification method params))))

(defn notify!
  "Send a notification to all active sessions"
  [server id method params]
  (log/info :rpc/notify-all! {:id id :method method :params params})
  (when-let [{:keys [reply!-fn]} (@(:session-id->session server) id)]
    (reply!-fn (json-rpc-notification method params))))
