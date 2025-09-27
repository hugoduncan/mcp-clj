(ns mcp-clj.sse
  (:require
    [mcp-clj.log :as log])
  (:import
    (java.io
      Closeable
      OutputStream
      OutputStreamWriter)))

(defn message
  [data]
  {:event "message"
   :data  data})

(defn send!
  "Send SSE message with error handling"
  [^java.io.Writer writer message]
  (log/info :sse/send! message)
  (locking writer
    (doseq [[k v] message]
      (log/trace :sse/write (str (name k) ": " v "\n"))
      (.write writer (str (name k) ": "  v "\n")))
    (.write writer "\n")
    (.flush writer)))

(defn- writer
  [^OutputStream output-stream]
  (OutputStreamWriter. output-stream "UTF-8"))

(defn handler
  [request]
  (log/info :sse/handler)
  (let [output-stream     (:response-body request)
        writer            (writer output-stream)
        on-response-error (:on-response-error request)
        on-response-done  (:on-response-done request)
        response-headers  {"Cache-Control" "no-cache"
                           "Connection"    "keep-alive"
                           "Content-Type"  "text/event-stream"}
        initialised       (promise)]
    {:reply!   (fn reply!
                 [response]
                 (log/info :sse/reply! response)
                 (try
                   @initialised
                   (send! writer response)
                   true
                   (catch Exception e
                     (binding [*out* *err*]
                       (println "Unexpected error writing SSE response")
                       (println (ex-message e) (ex-data e))
                       (.printStackTrace e)
                       (on-response-error)
                       (on-response-done)
                       (.close ^Closeable output-stream)
                       (throw e)))))
     :close!   (fn close
                 []
                 (log/info :sse/close!)
                 (try
                   (on-response-done)
                   (catch Exception e
                     (binding [*out* *err*]
                       (on-response-error)
                       (.close ^Closeable output-stream)
                       (println "Unexpected error closing SSE session")
                       (println (ex-message e) (ex-data e))
                       (.printStackTrace e)
                       (throw e)))))
     :response {:status  200
                :headers response-headers
                :body    (fn [& _] (deliver initialised :initialised))}}))
