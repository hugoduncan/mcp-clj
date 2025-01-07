(ns mcp-clj.json-rpc.server
  "JSON-RPC 2.0 server with Server-Sent Events (SSE) support"
  (:require
   [clojure.data.json :as json]
   [mcp-clj.http-server.ring-adapter :as http]
   [mcp-clj.json-rpc.protocol :as protocol]
   [mcp-clj.log :as log]
   [mcp-clj.sse :as sse]
   [ring.util.response :as response])
  (:import
   [java.util.concurrent ExecutorService
    RejectedExecutionException
    ScheduledExecutorService
    TimeUnit]))


(def ^:private request-timeout-ms 30000)

(defn- wrap-log-throwables [f]
  (fn []
    (try
      (f)
      (catch Exception e
        (log/error :rpc/unexpected e)))))

(defn- submit!
  ([executor f]
   (.submit ^ExecutorService executor ^Callable (wrap-log-throwables f)))
  ([executor f delay-millis]
   (.submit
    ^ScheduledExecutorService executor
    ^Callable (wrap-log-throwables f)
    delay-millis
    TimeUnit/MILLISECONDS)))

(defn- handle-request-async
  "Handle request asynchronously with timeout"
  [executor f]
  (let [f (submit! executor ^Callable f)]
    (submit!
     executor
     (fn [] (future-cancel f))
     request-timeout-ms)
    f))

(defn- json-response
  "Create a JSON response with given status"
  [data status]
  (-> (response/response (json/write-str data))
      (response/status status)
      (response/content-type "application/json")))

(defn- text-response
  "Create a JSON response with given status"
  [body status]
  (-> (response/response body)
      (response/status status)
      (response/content-type "text/plain")))

(defn- handle-json-rpc
  "Process a JSON-RPC request"
  [handlers {:keys [method params id] :as json-rpc} request]
  (log/info :rpc/invoke {:method method :params params})
  (if-let [validation-error (protocol/validate-request json-rpc)]
    (assoc validation-error :id id)
    (if-let [handler (get handlers method)]
      (try
        (handler request id params)
        (catch Exception e
          (.printStackTrace e)
          {:jsonrpc "2.0"
           :id      id
           :error   {:code    (get protocol/error-codes :internal-error)
                     :message (.getMessage e)}}))
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    (get protocol/error-codes :method-not-found)
                 :message (str "Method not found: " method)}})))

(defn- handle-request
  "Handle a JSON-RPC request"
  [handlers request]
  (try
    (let [request-data (json/read-str (slurp (:body request)) :key-fn keyword)
          _            (log/info :rpc/json-request {:json-request request-data})
          response     (handle-json-rpc handlers request-data request)]
      (text-response response 202))
    (catch RejectedExecutionException _
      (json-response
       {:jsonrpc "2.0"
        :error   {:code    -32000
                  :message "Server overloaded"}}
       503))
    (catch Exception e
      (json-response
       {:jsonrpc "2.0"
        :error   {:code    -32603
                  :message (str "Unexpected error: " (.getMessage e))}}
       500))))

;; Previous SSE handler implementation...

(defn data->str [v]
  (if (string? v)
    (pr-str v)
    (json/write-str v)))

(defn create-server
  "Create JSON-RPC server with SSE support"
  [{:keys [port
           handlers
           executor
           on-sse-connect
           on-sse-close]
    :or   {port 0}}]
  {:pre [(ifn? on-sse-connect) (ifn? on-sse-close)]}
  (when-not (map? handlers)
    (throw (ex-info "Handlers must be a map"
                    {:handlers handlers})))


  (let [on-sse-connect (fn [request id reply!-fn close!-fn]
                         (on-sse-connect
                          request
                          id
                          (fn [response & {:keys [json-encode]
                                           :or   {json-encode true}}]
                            (reply!-fn
                             (cond-> response
                               (and json-encode (:data response))
                               (update :data data->str))))
                          close!-fn))
        handler        (fn [{:keys [request-method uri] :as request}]
                         (log/info :rpc/request
                           {:method request-method :uri uri})
                         (case [request-method uri]
                           [:post "/messages"]
                           (handle-request handlers request)

                           [:get "/sse"]
                           ((sse/handler-fn on-sse-connect on-sse-close) request)

                           (do
                             (log/warn :rpc/invalid
                               {:method request-method :uri uri})
                             (-> (response/response "Not Found")
                                 (response/status 404)
                                 (response/content-type "text/plain")))))

        {:keys [server stop]}
        (http/run-server handler {:executor executor :port port})]
    {:server server
     :port   (.getPort (.getAddress server))
     :stop   stop}))
