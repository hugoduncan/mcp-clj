(ns mcp-clj.http-server.adapter-test
  "Tests for adapter for Java's HttpServer"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [mcp-clj.http :as http]
    [mcp-clj.http-server.adapter :as adapter])
  (:import
    (java.io
      OutputStream)
    (java.net
      HttpURLConnection
      URL)))

(defn test-handler
  [request]
  (case (:uri request)
    "/" (-> (http/response "Hello World")
            (http/content-type "text/plain"))

    "/stream" (-> (http/response
                    (fn []
                      (with-open [^OutputStream out (:response-body request)]
                        (doseq [n (range 3)]
                          (.write out (.getBytes (str "data: " n "\n\n"))))
                        (.flush out))))
                  (http/content-type "text/event-stream"))

    "/echo-headers" (-> (http/response
                          (pr-str {:headers (:headers request)}))
                        (http/content-type "application/edn"))

    "/echo-query" (-> (http/response
                        (pr-str {:query-string (:query-string request)
                                 :query-params ((:query-params request))}))
                      (http/content-type "application/edn"))

    "/post-echo" (-> (http/response
                       (slurp (:body request)))
                     (http/content-type "text/plain"))

    "/throw-error" (throw (RuntimeException. "Deliberate test error"))

    "/error" {:status  500
              :headers {"Content-Type" "text/plain"}
              :body    "Error"}))

(def ^:dynamic *server* nil)
(def ^:dynamic *port* nil)

(defn server-fixture
  [f]
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

(defn make-connection
  ^HttpURLConnection [method path]
  (let [url  (URL. (str "http://localhost:" *port* path))
        conn ^HttpURLConnection (.openConnection url)]
    (.setRequestMethod conn method)
    conn))

(defn http-get
  ^HttpURLConnection [path]
  (make-connection "GET" path))

(defn http-post
  ^HttpURLConnection [path ^String body]
  (let [conn (make-connection "POST" path)]
    (.setDoOutput conn true)
    (when body
      (.setRequestProperty conn "Content-Type" "text/plain")
      (with-open [w (.getOutputStream conn)]
        (.write w (.getBytes body))))
    conn))

(deftest query-string-test
  (testing "query string parsing"
    (testing "empty query string"
      (let [conn     (http-get "/echo-query")
            response (read-string (slurp (.getInputStream conn)))]
        (is (nil? (:query-string response)))
        (is (empty? (:query-params response)))))

    (testing "single parameter"
      (let [conn     (http-get "/echo-query?name=value")
            response (read-string (slurp (.getInputStream conn)))]
        (is (= "name=value" (:query-string response)))
        (is (= {"name" "value"} (:query-params response)))))

    (testing "multiple parameters"
      (let [conn     (http-get "/echo-query?a=1&b=2")
            response (read-string (slurp (.getInputStream conn)))]
        (is (= "a=1&b=2" (:query-string response)))
        (is (= {"a" "1" "b" "2"} (:query-params response)))))

    (testing "URL encoded parameters"
      (let [conn     (http-get "/echo-query?message=hello%20world&type=greeting%21")
            response (read-string (slurp (.getInputStream conn)))]
        (is (= {"message" "hello world" "type" "greeting!"} (:query-params response)))))

    (testing "missing value parameter"
      (let [conn     (http-get "/echo-query?key=")
            response (read-string (slurp (.getInputStream conn)))]
        (is (= {"key" ""} (:query-params response)))))

    (testing "duplicate parameters - last value wins"
      (let [conn     (http-get "/echo-query?key=1&key=2")
            response (read-string (slurp (.getInputStream conn)))]
        (is (= {"key" "2"} (:query-params response)))))))

(deftest post-request-test
  (testing "POST with body"
    (let [test-body "Hello Server"
          conn      (http-post "/post-echo" test-body)]
      (is (= 200 (.getResponseCode conn)))
      (is (= test-body (slurp (.getInputStream conn)))))))

(deftest error-handling-test
  (testing "handler throwing runtime exception"
    (let [conn (http-get "/throw-error")]
      (is (= 500 (.getResponseCode conn))))))

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
    (let [conn (http-get "/stream")]
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
