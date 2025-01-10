(ns mcp-clj.http-server.adapter-test
  "Tests for adapter for Java's HttpServer"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [mcp-clj.http :as http]
   [mcp-clj.http-server.adapter :as adapter])
  (:import
   [java.net URL HttpURLConnection]))

(defn test-handler [request]
  (case (:uri request)
    "/" (-> (http/response "Hello World")
            (http/content-type "text/plain"))

    "/stream" (-> (http/response (fn []
                                   (with-open [out (:response-body request)]
                                     (doseq [n (range 3)]
                                       (.write out (.getBytes (str "data: " n "\n\n"))))
                                     (.flush out))))
                  (http/content-type "text/event-stream"))

    "/echo-headers" (-> (http/response
                         (pr-str {:headers (:headers request)}))
                        (http/content-type "application/edn"))

    "/error" {:status  500
              :headers {"Content-Type" "text/plain"}
              :body    "Error"}))

(def ^:dynamic *server* nil)
(def ^:dynamic *port* nil)

(defn server-fixture [f]
  (println "Starting test server")
  (let [server-map (adapter/run-server test-handler {:port 0})
        port       (:port server-map)]
    (println "Server started:" server-map)
    (try
      (println "Running test with server")
      (binding [*server* server-map
                *port*   port]
        (f))
      (finally
        (println "Stopping server")
        ((:stop server-map))
        (println "Server stopped")))))

(use-fixtures :each server-fixture)

(defn http-get [path]
  (let [url  (URL. (str "http://localhost:" *port* path))
        _    (println "Connecting to URL:" url)
        conn ^HttpURLConnection (.openConnection url)]
    (.setRequestMethod conn "GET")
    (println "Created connection:" conn)
    conn))

(deftest basic-request-test
  (println "Starting basic-request-test")
  (testing "basic GET request"
    (try
      (let [conn     (http-get "/")
            _        (println "Got response code:" (.getResponseCode conn))
            _        (println "Got content type:" (.getHeaderField conn "Content-Type"))
            response (slurp (.getInputStream conn))]
        (println "Got response:" response)
        (is (= 200 (.getResponseCode conn)))
        (is (= "text/plain" (.getHeaderField conn "Content-Type")))
        (is (= "Hello World" response)))
      (catch Exception e
        (println "Error in test:" (.getMessage e))
        (.printStackTrace e))))

  (testing "streaming SSE response"
    (let [conn     (http-get "/stream")]
      (is (= 200 (.getResponseCode conn)))
      (is (= "text/event-stream" (.getHeaderField conn "Content-Type")))
      (.setReadTimeout conn 1000) ; ensure we don't hang
      (let [response (slurp (.getInputStream conn))]
        (is (= "data: 0\n\ndata: 1\n\ndata: 2\n\n" response)))))

  (testing "error response"
    (let [conn (http-get "/error")]
      (is (= 500 (.getResponseCode conn)))
      (is (= "text/plain" (.getHeaderField conn "Content-Type")))
      (is (= "Error" (slurp (.getErrorStream conn))))))

  (testing "header passing and case insensitivity"
    (let [conn (http-get "/echo-headers")]
      (.setRequestProperty conn "X-Test" "test-value")
      (.setRequestProperty conn "CONTENT-TYPE" "text/special")
      (let [response (read-string (slurp (.getInputStream conn)))]
        (is (= "test-value" (get-in response [:headers "x-test"])))
        (is (= "text/special" (get-in response [:headers "content-type"])))
        (is (= "application/edn" (.getHeaderField conn "Content-Type")))))))
