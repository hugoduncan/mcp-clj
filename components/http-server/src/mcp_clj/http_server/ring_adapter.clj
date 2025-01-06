(ns mcp-clj.http-server.ring-adapter
  "Ring adapter for Java's com.sun.net.httpserver.HttpServer with SSE support"
  (:require
   [clojure.string :as str]
   [ring.util.response :as response])
  (:import
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.net InetSocketAddress]
   [java.io InputStream]))

(defn- exchange->ring-request
  "Convert HttpExchange to Ring request map"
  [^HttpExchange exchange]
  {:server-port    (.getPort (.getLocalAddress exchange))
   :server-name    (.getHostName (.getLocalAddress exchange))
   :remote-addr    (-> exchange .getRemoteAddress .getAddress .getHostAddress)
   :uri            (.getPath (.getRequestURI exchange))
   :query-string   (.getRawQuery (.getRequestURI exchange))
   :scheme         :http
   :request-method (-> exchange .getRequestMethod .toLowerCase keyword)
   :headers        (into {}
                         (for [k (.keySet (.getRequestHeaders exchange))
                               :let [vs (.get (.getRequestHeaders exchange) k)]]
                           [(str/lower-case k) (str (first vs))]))
   :body           (.getRequestBody exchange)})

(defn- send-streaming-response
  "Handle streaming response for SSE"
  [^HttpExchange exchange response]
  (let [{:keys [status headers body]} response]
    (doseq [[k v] headers]
      (.add (.getResponseHeaders exchange)
            (name k)
            (str v)))
    (.sendResponseHeaders exchange status 0)
    (with-open [os (.getResponseBody exchange)]
      (try
        (try
          (body os)
          (.flush os)
          (catch sun.net.httpserver.StreamClosedException _
            ;; Client disconnected - normal for SSE
            nil))
        (catch Exception e
          ;; Log other exceptions
          (.printStackTrace e))))))

(defn- send-ring-response
  "Send Ring response, detecting streaming vs normal response"
  [^HttpExchange exchange response]
  (if (fn? (:body response))
    (send-streaming-response exchange response)
    (let [{:keys [status headers body]}
          response
          body-bytes (cond
                       (string? body)               (.getBytes body)
                       (instance? InputStream body) (with-open [is body]
                                                      (.readAllBytes is))
                       :else                        body)]
      (doseq [[k v] headers]
        (.add (.getResponseHeaders exchange)
              (name k)
              (str v)))
      (.sendResponseHeaders exchange status (count body-bytes))
      (with-open [os (.getResponseBody exchange)]
        (.write os body-bytes)
        (.flush os)))))


(defn run-server
  "Start an HttpServer instance with the given Ring handler.
   Returns a server map containing :server and :stop fn."
  [handler {:keys [port join?]
            :or   {port  8080
                   join? false}}]
  (let [server     (HttpServer/create (InetSocketAddress. port) 0)
        handler-fn (reify HttpHandler
                     (handle [_ exchange]
                       (try
                         (let [request  (exchange->ring-request exchange)
                               response (handler request)]
                           (if (fn? (:body response))
                             (send-streaming-response exchange response)
                             (send-ring-response exchange response)))
                         (catch Exception e
                           (.printStackTrace e)
                           (.sendResponseHeaders exchange 500 0))
                         ;; Removed exchange close from finally block
                         )))]
    (.createContext server "/" handler-fn)
    (.setExecutor server nil)
    (.start server)
    (when join?
      (.awaitTermination (.getExecutor server) Long/MAX_VALUE java.util.concurrent.TimeUnit/SECONDS))
    {:server server
     :stop   (fn [] (.stop server 0))}))
