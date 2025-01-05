(ns mcp-clj.http-server.ring-adapter-test
  "Tests for Ring adapter for Java's HttpServer"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [mcp-clj.http-server.ring-adapter :as adapter]
   [ring.util.response :as response])
  (:import
   [java.net URL HttpURLConnection]))

(defn test-handler [request]
  (case (:uri request)
    "/" (-> (response/response "Hello World")
            (response/content-type "text/plain"))

    "/stream" (-> (response/response
                   #(doseq [n (range 3)]
                      (.write % (.getBytes (str "data: " n "\n\n")))
                      (.flush %)))
                  (response/content-type "text/event-stream"))

    "/echo-headers" (-> (response/response
                         (pr-str {:headers (:headers request)}))
                        (response/content-type "application/edn"))

    "/error" {:status  500
              :headers {"Content-Type" "text/plain"}
              :body    "Error"}))

(def ^:dynamic *server* nil)
(def test-port 8899)

(defn server-fixture [f]
  (let [server-map (adapter/run-server test-handler {:port test-port})]
    (try
      (binding [*server* server-map]
        (f))
      (finally
        ((:stop server-map))))))

(use-fixtures :each server-fixture)

(defn http-get [path]
  (let [url  (URL. (str "http://localhost:" test-port path))
        conn ^HttpURLConnection (.openConnection url)]
    (.setRequestMethod conn "GET")
    conn))

(deftest basic-request-test
  (testing "basic GET request"
    (let [conn     (http-get "/")
          response (slurp (.getInputStream conn))]
      (is (= 200 (.getResponseCode conn)))
      (is (= "text/plain" (.getHeaderField conn "Content-Type")))

      (is (= "Hello World" response))))

  (testing "streaming SSE response"
    (let [conn     (http-get "/stream")
          response (slurp (.getInputStream conn))]
      (is (= 200 (.getResponseCode conn)))
      (is (= "text/event-stream" (.getHeaderField conn "Content-Type")))
      (is (= "data: 0\n\ndata: 1\n\ndata: 2\n\n" response))))

  (testing "error response"
    (let [conn (http-get "/error")]
      (is (= 500 (.getResponseCode conn)))))

  (testing "header passing"
    (let [conn (http-get "/echo-headers")]
      (.setRequestProperty conn "X-Test" "test-value")
      (let [response (read-string (slurp (.getInputStream conn)))]
        (is (= "test-value" (get-in response [:headers "x-test"])))))))
