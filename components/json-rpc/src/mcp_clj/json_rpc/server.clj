(ns mcp-clj.json-rpc.server
  "JSON-RPC 2.0 server with Server-Sent Events (SSE) support"
  (:require
   [clojure.data.json :as json]
   [mcp-clj.http-server.ring-adapter :as http]
   [mcp-clj.json-rpc.protocol :as protocol]
   [ring.util.response :as response]))

(defn- handle-json-rpc
  "Process a JSON-RPC request and return response"
  [handlers request]
  (if-let [validation-error (protocol/validate-request request)]
    (assoc validation-error :id (:id request))
    (let [{:keys [method params id]} request
          handler                    (get handlers method)]
      (if handler
        (try
          {:jsonrpc "2.0"
           :id      id
           :result  (handler params)}
          (catch Exception e
            {:jsonrpc "2.0"
             :id      id
             :error   {:code    (get protocol/error-codes :internal-error)
                       :message (.getMessage e)}}))
        {:jsonrpc "2.0"
         :id      id
         :error   {:code    (get protocol/error-codes :method-not-found)
                   :message (str "Method not found: " method)}}))))

(defn- handle-post
  "Handle JSON-RPC POST request"
  [handlers {:keys [body] :as request}]
  (try
    (let [request-data (json/read-str (slurp body) :key-fn keyword)
          response     (handle-json-rpc handlers request-data)
          response-str (json/write-str response)]
      (-> (response/response response-str)
          (response/status 200)
          (response/content-type "application/json")))
    (catch Exception e
      (-> (response/response
           (json/write-str
            {:jsonrpc "2.0"
             :error   {:code    (get protocol/error-codes :internal-error)
                       :message (str "Unexpected error: " (ex-message e))}}))
          (response/status 500)
          (response/content-type "application/json")))))

(defn- handle-sse
  "Handle SSE stream setup"
  [response-stream]
  (fn [output]
    (let [writer (java.io.OutputStreamWriter. output "UTF-8")]
      (try
        (loop []
          (when-let [response @response-stream]
            (.write writer (str "data: " (json/write-str response) "\n\n"))
            (.flush writer)
            (reset! response-stream nil)
            (recur)))
        (catch Exception e
          (.printStackTrace e))))))

(defn create-server
  "Create JSON-RPC server with SSE support.

   Configuration options:
   - :port     Port number (default: 0 for auto-assignment)
   - :handlers Map of method names to handler functions"
  [{:keys [port handlers]
    :or   {port 0}}]
  (when-not (map? handlers)
    (throw (ex-info "Handlers must be a map" {:handlers handlers})))

  (let [response-stream (atom nil)
        handler         (fn [{:keys [request-method uri] :as request}]
                          (case [request-method uri]
                            [:post "/message"]
                            (handle-post handlers request)

                            [:get "/sse"]
                            (-> (response/response (handle-sse response-stream))
                                (response/status 200)
                                (response/content-type "text/event-stream")
                                (response/header "Cache-Control" "no-cache")
                                (response/header "Connection" "keep-alive"))

                            (-> (response/response "Not Found")
                                (response/status 404)
                                (response/content-type "text/plain"))))

        {:keys [server stop]} (http/run-server handler {:port port})]
    {:server          server
     :response-stream response-stream
     :port            (.getPort (.getAddress server))
     :stop            stop}))
