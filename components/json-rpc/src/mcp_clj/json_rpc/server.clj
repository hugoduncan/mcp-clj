(ns mcp-clj.json-rpc.server
  "JSON-RPC 2.0 server implementation with EDN/JSON conversion"
  (:require
   [mcp-clj.json-rpc.protocol :as protocol])
  (:import
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.net InetSocketAddress]
   [java.io InputStreamReader BufferedReader]
   [java.util.concurrent Executors]))

(defn- read-request-body
  "Read the request body from an HttpExchange"
  [^HttpExchange exchange]
  (with-open [reader (-> exchange
                        .getRequestBody
                        InputStreamReader.
                        BufferedReader.)]
    (let [length (-> exchange .getRequestHeaders (get "Content-length") first Integer/parseInt)
          chars (char-array length)]
      (.read reader chars 0 length)
      (String. chars))))

(defn- send-response
  "Send a response through the HttpExchange"
  [^HttpExchange exchange ^String response]
  (let [bytes (.getBytes response)
        headers (.getResponseHeaders exchange)]
    (.add headers "Content-Type" "application/json")
    (.sendResponseHeaders exchange 200 (count bytes))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes)
      (.flush os))))

(defn- handle-json-rpc
  "Process a single JSON-RPC request and return response"
  [handlers request]
  (if-let [validation-error (protocol/validate-request request)]
    validation-error
    (let [{:keys [method params id]} request
          handler (get handlers method)]
      (if handler
        (try
          (let [result (handler params)]
            (protocol/result-response id result))
          (catch Exception e
            (protocol/error-response
             (get protocol/error-codes :internal-error)
             (.getMessage e)
             {:id id})))
        (protocol/error-response
         (get protocol/error-codes :method-not-found)
         (str "Method not found: " method)
         {:id id})))))

(defn- handle-request
  "Handle an incoming HTTP request"
  [handlers exchange]
  (try
    (let [body                  (read-request-body exchange)
          [request parse-error] (protocol/parse-json body)
          response              (if parse-error
                                  parse-error
                                  (cond
                                    (map? request)
                                    (handle-json-rpc handlers request)

                                    (sequential? request)
                                    (mapv #(handle-json-rpc handlers %) request)

                                    :else
                                    (protocol/error-response
                                     (get protocol/error-codes :invalid-request)
                                     "Invalid request format")))
          [json-response json-error] (protocol/write-json response)]
      (if json-error
        (send-response exchange
                       (protocol/write-json
                        (protocol/error-response
                         (get protocol/error-codes :internal-error)
                         "Response encoding error")))
        (send-response exchange json-response)))
    (catch Exception e
      (send-response exchange
                     (protocol/write-json
                      (protocol/error-response
                       (get protocol/error-codes :internal-error)
                       "Internal server error"))))))

(defn create-server
  "Create a new JSON-RPC server.

   Configuration options:
   - :port     Required. Port number to listen on
   - :handlers Required. Map of method names to handler functions

   Returns a map containing:
   - :server   The server instance
   - :stop     Function to stop the server

   Example:
   ```clojure
   (create-server
     {:port 8080
      :handlers {\"echo\" (fn [params] {:result params})}})
   ```"
  [{:keys [port handlers] :as config}]
  (when-not port
    (throw (ex-info "Port is required" {:config config})))
  (when-not (map? handlers)
    (throw (ex-info "Handlers must be a map" {:config config})))

  (let [server   (HttpServer/create (InetSocketAddress. port) 0)
        executor (Executors/newFixedThreadPool 10)
        handler  (reify HttpHandler
                   (handle [_ exchange]
                     (handle-request handlers exchange)))]

    (.setExecutor server executor)
    (.createContext server "/" handler)
    (.start server)

    {:server server
     :stop   #(do (.stop server 1)
                  (.shutdown executor))}))
