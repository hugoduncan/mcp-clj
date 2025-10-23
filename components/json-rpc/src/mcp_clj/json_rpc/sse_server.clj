(ns mcp-clj.json-rpc.sse-server
  "JSON-RPC 2.0 server with Server-Sent Events (SSE) support"
  (:require
    [mcp-clj.http :as http]
    [mcp-clj.http-server.adapter :as http-server]
    [mcp-clj.json :as json]
    [mcp-clj.json-rpc.executor :as executor]
    [mcp-clj.json-rpc.json-protocol :as json-protocol]
    [mcp-clj.json-rpc.protocols :as protocols]
    [mcp-clj.log :as log]
    [mcp-clj.sse :as sse])
  (:import
    (java.util.concurrent
      RejectedExecutionException)))

;; Executor Service

;; Executor Service

;; Configuration

(def ^:private request-timeout-ms 30000)

(defrecord Session
  [^String session-id
   reply!-fn
   close!-fn])

;; Response Format

;; Request Handling

(defn- request-session-id
  [request]
  (get ((:query-params request)) "session_id"))

(defn- handle-json-rpc
  "Process a JSON-RPC request"
  [handler {:keys [method params id]} request reply!-fn]
  (log/info :rpc/invoke {:method method :params params})
  (try
    (when-let [response (handler request params)]
      (log/info :server/handler-response response)
      (reply!-fn (json-protocol/json-rpc-result response id)))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (and (:code data) (:message data))
          (do
            (log/info :rpc/handler-json-rpc-error {:error data})
            (reply!-fn (json-protocol/json-rpc-error
                         (:code data)
                         (:message data)
                         id
                         (:data data))))
          (do
            (log/error :rpc/handler-error {:error e})
            (reply!-fn (json-protocol/json-rpc-error
                         :internal-error
                         (.getMessage e)
                         id))))))
    (catch Throwable e
      (log/error :rpc/handler-error {:error e})
      (reply!-fn (json-protocol/json-rpc-error
                   :internal-error
                   (.getMessage e)
                   id)))))

(defn- dispatch-rpc-call
  [executor handler rpc-call request reply!-fn]
  (executor/submit-with-timeout!
    executor
    #(handle-json-rpc handler rpc-call request reply!-fn)
    request-timeout-ms))

(defn- handle-request
  "Handle a JSON-RPC request"
  [executor session-id->session handlers request]
  (try
    (let [session-id (request-session-id request)
          session (session-id->session session-id)
          reply!-fn (:reply!-fn session)
          rpc-call (json/parse (slurp (:body request)))]
      (log/info :rpc/json-request
                {:json-request rpc-call
                 :session-id session-id})
      (if-let [validation-error (json-protocol/validate-request rpc-call)]
        (http/json-response
          (json-protocol/json-rpc-error
            (:code (:error validation-error))
            (:message (:error validation-error)))
          http/BadRequest)
        (if-let [handler (get handlers (:method rpc-call))]
          (do
            (dispatch-rpc-call executor handler rpc-call request reply!-fn)
            (http/text-response "Accepted" http/Accepted))
          (http/json-response
            (json-protocol/json-rpc-error
              :method-not-found
              (str "Method not found: " (:method rpc-call))
              (:id rpc-call))
            http/BadRequest))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (log/warn :rpc/json-parse-error {:error (.getMessage e)})
      (http/json-response
        (json-protocol/json-rpc-error
          :parse-error
          (str "Invalid JSON: " (.getMessage e)))
        http/BadRequest))
    (catch clojure.lang.ExceptionInfo e
      (if (= :parse-error (:type (ex-data e)))
        (do
          (log/warn :rpc/json-parse-error {:error (.getMessage e)})
          (http/json-response
            (json-protocol/json-rpc-error
              :parse-error
              (.getMessage e))
            http/BadRequest))
        (throw e)))
    (catch RejectedExecutionException _
      (log/warn :rpc/overload-rejection)
      (http/json-response
        (json-protocol/json-rpc-error :overloaded "Server overloaded")
        http/Unavailable))
    (catch Exception e
      (.printStackTrace e)
      (log/error :rpc/error {:e e})
      (http/json-response
        (json-protocol/json-rpc-error
          :internal-error
          (.getMessage e))
        http/InternalServerError))))

(defn data->str
  [v]
  (if (string? v)
    (pr-str v)
    (json/write v)))

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
    :or {num-threads (* 2 (.availableProcessors (Runtime/getRuntime)))
         port 0
         on-sse-connect (fn [& _])
         on-sse-close (fn [& _])}}]
  {:pre [(ifn? on-sse-connect) (ifn? on-sse-close)]}
  (let [executor (executor/create-executor num-threads)
        session-id->session (atom {})
        handlers (atom nil)
        handler (fn [{:keys [request-method uri] :as request}]
                  (log/info :rpc/http-request
                            {:method request-method :uri uri})
                  (case [request-method uri]
                    [:post "/messages"]
                    (if (nil? @handlers)
                      (http/json-response
                        (json-protocol/json-rpc-error
                          :internal-error
                          "Server not ready - handlers not initialized")
                        http/Unavailable)
                      (handle-request
                        executor
                        @session-id->session
                        @handlers
                        request))

                    [:get "/sse"]
                    (let [id (uuid->hex (random-uuid))
                          uri (str "/messages?session_id=" id)
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
                      (log/warn :rpc/invalid {:method request-method :uri uri})
                      (http/text-response "Not Found" http/NotFound))))

        {:keys [server port stop]}
        (http-server/run-server handler {:executor executor :port port})
        server {:server server
                :port port
                :handlers handlers
                :stop (fn []
                        (stop)
                        (executor/shutdown-executor executor))
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
    (when session
      ((:close!-fn session))
      (swap! (:session-id->session server) dissoc id))))

(defn notify-all!
  "Send a notification to all active sessions"
  [server method params]
  (log/info :rpc/notify-all! {:method method :params params})
  (doseq [{:keys [reply!-fn] :as session} (vals @(:session-id->session server))]
    (log/info :rpc/notify-all! {:session-id (:session-id session)})
    (reply!-fn (json-protocol/json-rpc-notification method params))))

(defn notify!
  "Send a notification to all active sessions"
  [server id method params]
  (log/info :rpc/notify-all! {:id id :method method :params params})
  (when-let [{:keys [reply!-fn]} (@(:session-id->session server) id)]
    (reply!-fn (json-protocol/json-rpc-notification method params))))

;; Protocol Implementation

(extend-type clojure.lang.PersistentArrayMap
  protocols/JsonRpcServer
  (set-handlers! [server handlers]
    (when-not (map? handlers)
      (throw (ex-info "Handlers must be a map"
                      {:handlers handlers})))
    (swap! (:handlers server) (constantly handlers)))

  (notify! [server id method params]
    (log/info :rpc/notify! {:id id :method method :params params})
    (when-let [{:keys [reply!-fn]} (@(:session-id->session server) id)]
      (reply!-fn (json-protocol/json-rpc-notification method params))))

  (notify-all! [server method params]
    (log/info :rpc/notify-all! {:method method :params params})
    (doseq [{:keys [reply!-fn] :as session} (vals @(:session-id->session server))]
      (log/info :rpc/notify-all! {:session-id (:session-id session)})
      (reply!-fn (json-protocol/json-rpc-notification method params))))

  (stop! [server]
    ((:stop server))))
