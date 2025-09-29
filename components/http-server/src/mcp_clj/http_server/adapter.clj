(ns mcp-clj.http-server.adapter
  "Adapter for Java's com.sun.net.httpserver.HttpServer with SSE support"
  (:require
    [clojure.string :as str]
    [mcp-clj.log :as log])
  (:import
    (com.sun.net.httpserver
      HttpExchange
      HttpHandler
      HttpServer)
    (java.net
      InetSocketAddress
      URLDecoder)))

(defn- set-response-header!
  [^HttpExchange exchange k v]
  (.add (.getResponseHeaders exchange) (name k) (str v)))

(defn- set-response-headers!
  [^HttpExchange exchange headers]
  (doseq [[k v] headers]
    (set-response-header! exchange k v)))

(defn- send-response-headers!
  [^HttpExchange exchange status num-bytes]
  (.sendResponseHeaders exchange status num-bytes))

(defn- flush-response!
  [^HttpExchange exchange]
  (let  [os (.getResponseBody exchange)]
    (.flush os)))

(defn- close-response-body!
  [^HttpExchange exchange]
  (.close (.getResponseBody exchange)))

(defn- parse-query
  [raw-query]
  (let [decode #(URLDecoder/decode ^String % "UTF-8")]
    (if (str/blank? raw-query)
      {}
      (into {}
            (map (fn [pair]
                   (let [[key value] (str/split pair #"=" 2)]
                     [(decode key) (decode (or value ""))]))
                 (str/split raw-query #"&"))))))

(defn- exchange->request-map
  "Convert HttpExchange to Ring request map"
  [^HttpExchange exchange]
  {:server-port       (.getPort (.getLocalAddress exchange))
   :server-name       (.getHostName (.getLocalAddress exchange))
   :remote-addr       (-> exchange .getRemoteAddress .getAddress .getHostAddress)
   :uri               (.getPath (.getRequestURI exchange))
   :query-string      (.getRawQuery (.getRequestURI exchange))
   :query-params      (fn query-params
                        []
                        (parse-query
                          (.getRawQuery (.getRequestURI exchange))))
   :scheme            :http
   :request-method    (-> exchange .getRequestMethod .toLowerCase keyword)
   :headers           (into {}
                            (for [k    (.keySet (.getRequestHeaders exchange))
                                  :let [vs (.get (.getRequestHeaders exchange) k)]]
                              [(str/lower-case k) (str (first vs))]))
   :body              (.getRequestBody exchange)
   :on-response-done  (fn [] (close-response-body! exchange))
   :on-response-error (fn [] (close-response-body! exchange))
   :response-body     (.getResponseBody exchange)})

(defn- send-streaming-response
  "Handle streaming response for SSE"
  [^HttpExchange exchange response]
  (let [{:keys [body status headers]} response]
    (set-response-headers! exchange headers)
    (send-response-headers! exchange status 0)
    (body)))

(defn- send-ring-response
  "Send Ring response, detecting streaming vs normal response"
  [^HttpExchange exchange response]
  (if (fn? (:body response))
    (send-streaming-response exchange response)
    (let [{:keys [status headers body]}
          response
          _                  (log/info :http/response {:body-type (type body)})
          ^bytes  body-bytes (if (string? body)
                               (.getBytes ^String body)
                               body)
          n                  (if body-bytes
                               (alength body-bytes)
                               0)]
      (set-response-headers! exchange headers)
      (send-response-headers! exchange status (if (pos? n) n -1))
      (if (pos? n)
        (with-open [os (.getResponseBody exchange)]
          (.write os body-bytes)
          (.flush os))
        (close-response-body! exchange)))))

(defn run-server
  "Start an HttpServer instance with the given Ring handler.
   Returns a server map containing :server and :stop fn."
  [handler {:keys [executor port]
            :or   {port 8080}}]
  (let [server     (HttpServer/create (InetSocketAddress. port) 0)
        handler-fn (reify HttpHandler
                     (handle
                       [_ exchange]
                       (try
                         (let [request  (exchange->request-map exchange)
                               response (handler request)]
                           (log/info
                             :http/request
                             {:request
                              (select-keys
                                request
                                [:uri :method :headers])
                              :response response})
                           (if (fn? (:body response))
                             (send-streaming-response exchange response)
                             (send-ring-response exchange response)))
                         (catch Exception e
                           (.printStackTrace e)
                           (send-response-headers! exchange 500 0)
                           (flush-response! exchange))
                         ;; Removed exchange close from finally block
                         )))]
    (.createContext server "/" handler-fn)
    (.setExecutor server executor)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))
     :stop   (fn [] (.stop server 0))}))
