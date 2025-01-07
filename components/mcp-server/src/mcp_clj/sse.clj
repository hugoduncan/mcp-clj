(ns mcp-clj.sse
  (:require
   [mcp-clj.log :as log])
  (:import
   [java.io Closeable
    OutputStream
    OutputStreamWriter]))

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

(defn- uuid->hex
  [^java.util.UUID uuid]
  (let [msb (.getMostSignificantBits uuid)
        lsb (.getLeastSignificantBits uuid)]
    (format "%016x%016x" msb lsb)))

(defn- writer
  [^OutputStream output-stream]
  (OutputStreamWriter. output-stream "UTF-8"))

(defn handler-fn
  [on-connect on-close]
  {:pre [(ifn? on-connect) (ifn? on-close)]}
  (fn [request]
    (let [output-stream     (:response-body request)
          writer            (writer output-stream)
          on-response-error (:on-response-error request)
          on-response-done  (:on-response-done request)
          id                (uuid->hex (random-uuid))
          response-headers  {"Cache-Control" "no-cache"
                             "Connection"    "keep-alive"
                             "Content-Type"  "text/event-stream"}]
      ((:set-response-headers request) response-headers)
      ((:send-response-headers request) 200 0)

      (on-connect
       request
       id
       (fn reply! [response]
         (try
           (send! writer response)
           true
           (catch Exception e
             (binding [*out* *err*]
               (println "Unexpected error writing SSE response")
               (println (ex-message e) (ex-data e))
               (.printStackTrace e)
               (on-response-error)
               (on-close id)
               (.close ^Closeable output-stream)
               (throw e)))))
       (fn close []
         (try
           (on-response-done)
           (catch Exception e
             (binding [*out* *err*]
               (on-response-error)
               (on-close id)
               (.close ^Closeable output-stream)
               (println "Unexpected error writing SSE response")
               (println (ex-message e) (ex-data e))
               (.printStackTrace e)
               (throw e)))))))
    {;; :status  200
     ;; :headers {"Cache-Control" "no-cache"
     ;;           "Connection"    "keep-alive"
     ;;           "Content-Type"  "text/event-stream"}
     :body (fn [& _])}))
