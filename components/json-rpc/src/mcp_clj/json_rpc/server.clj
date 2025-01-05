(ns mcp-clj.json-rpc.server
  "JSON-RPC 2.0 server with Server-Sent Events (SSE) support"
  (:require
   [clojure.data.json :as json]
   [mcp-clj.http-server.ring-adapter :as http]
   [mcp-clj.json-rpc.protocol :as protocol]
   [ring.util.response :as response])
  (:import
   [com.sun.net.httpserver HttpServer]
   [java.io OutputStreamWriter BufferedWriter]))

(defn- write-sse-message
  "Write a Server-Sent Event message"
  [^BufferedWriter writer message]
  (doto writer
    (.write (str "data: " message "\n\n"))
    (.flush)))

(defn- handle-json-rpc
  "Process a single JSON-RPC request and return response"
  [handlers request]
  (if-let [validation-error (protocol/validate-request request)]
    validation-error
    (let [{:keys [method params id]} request
          handler                    (get handlers method)]
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

(defn- handle-sse-request
  "Handle SSE stream setup and message processing"
  [handlers message-stream]
  (fn [^java.io.OutputStream output-stream]
    (with-open [writer (-> output-stream
                           (OutputStreamWriter. "UTF-8")
                           BufferedWriter.)]
      (try
        (let [control-ch message-stream]
          (loop []
            (when-let [message @control-ch]
              (let [[request parse-error] (protocol/parse-json message)
                    response              (if parse-error
                                            parse-error
                                            (handle-json-rpc handlers request))
                    [json-response error] (protocol/write-json response)]
                (when json-response
                  (write-sse-message writer json-response))
                (when error
                  (write-sse-message writer
                                     (json/write-str
                                      (protocol/error-response
                                       (get protocol/error-codes :internal-error)
                                       "Response encoding error")))))
              (reset! control-ch nil)
              (recur))))
        (catch Exception e
          (write-sse-message writer
                             (json/write-str
                              (protocol/error-response
                               (get protocol/error-codes :internal-error)
                               "Stream error"))))))))

(defn get-server-port
  "Get the actual port a server is listening on"
  [^HttpServer server]
  (.getPort (.getAddress server)))

(defn create-server
  "Create a new JSON-RPC SSE server.

   Configuration options:
   - :port           Port number (default: 0 for auto-assignment)
   - :handlers       Map of method names to handler functions
   - :message-stream Shared atom for message passing

   Returns map with:
   - :server   The server instance
   - :port     The actual port the server is running on
   - :stop     Function to stop the server"
  [{:keys [port handlers message-stream]
    :or   {port 0}
    :as   config}]
  (when-not (map? handlers)
    (throw (ex-info "Handlers must be a map" {:config config})))
  (when-not (instance? clojure.lang.Atom message-stream)
    (throw (ex-info "Message stream must be an atom" {:config config})))

  (let [{:keys [server stop]} (http/run-server
                               (fn [request]
                                 (-> (response/response
                                      (handle-sse-request handlers message-stream))
                                     (response/content-type "text/event-stream")
                                     (response/header "Cache-Control" "no-cache")
                                     (response/header "Connection" "keep-alive")))
                               {:port port})]
    {:server server
     :port   (get-server-port server)
     :stop   stop}))
