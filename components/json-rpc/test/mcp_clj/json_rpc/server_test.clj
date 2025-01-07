(ns mcp-clj.json-rpc.server-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.data.json :as json]
   [mcp-clj.json-rpc.server :as server]
   [hato.client :as hato])
  (:import [java.util.concurrent Executors TimeUnit CountDownLatch]))

#_(ns mcp-clj.json-rpc.server-test
    (:require
     [clojure.test :refer :all]
     [mcp-clj.json-rpc.server :as server]
     [mcp-clj.json-rpc.protocol :as protocol])
    (:import
     [java.net HttpURLConnection URL]
     [java.io BufferedReader InputStreamReader]))

;; (defn- read-sse-events
;;   "Read SSE events from connection until it receives an event or times out"
;;   [^HttpURLConnection conn timeout-ms]
;;   (with-open [reader (-> conn
;;                          .getInputStream
;;                          InputStreamReader.
;;                          BufferedReader.)]
;;     (.setReadTimeout conn timeout-ms)
;;     (loop [events []]
;;       (let [line (.readLine reader)]
;;         (if (and line (.startsWith line "data: "))
;;           (let [event      (subs line 6)
;;                 [parsed _] (protocol/parse-json event)]
;;             (conj events parsed))
;;           events)))))

;; (deftest server-creation
;;   (testing "Server creation validation"
;;     (is (thrown-with-msg? clojure.lang.ExceptionInfo
;;                           #"Handlers must be a map"
;;                           (server/create-server {})))

;;     (is (thrown-with-msg? clojure.lang.ExceptionInfo
;;                           #"Message stream must be an atom"
;;                           (server/create-server {:handlers {}}))))

;;   (testing "Successful server creation"
;;     (let [{:keys [server port stop]} (server/create-server
;;                                       {:handlers       {"echo" (fn [params] params)}
;;                                        :message-stream (atom nil)})]
;;       (try
;;         (is (some? server))
;;         (is (pos-int? port))
;;         (is (fn? stop))
;;         (finally
;;           (stop))))))

;; (deftest server-functionality
;;   (testing "Echo method response over SSE"
;;     (let [test-data           {:message "Hello" :number 42}
;;           message-queue       (atom nil)
;;           {:keys [port stop]} (server/create-server
;;                                {:handlers       {"echo" (fn [params] params)}
;;                                 :message-stream message-queue})]
;;       (try
;;         (let [conn (doto (.openConnection (URL. (str "http://localhost:" port)))
;;                      (.setRequestMethod "GET")
;;                      (.setRequestProperty "Accept" "text/event-stream")
;;                      (.setReadTimeout 2000)
;;                      (.setDoInput true)
;;                      (.connect))]

;;           (Thread/sleep 100)  ; Wait for connection

;;                                         ; Send request via message queue
;;           (let [request      {:jsonrpc "2.0"
;;                               :method  "echo"
;;                               :params  test-data
;;                               :id      1}
;;                 json-request (first (protocol/write-json request))]
;;             (reset! message-queue json-request)

;;                                         ; Read and verify response
;;             (let [events   (read-sse-events conn 2000)
;;                   response (first events)]
;;               (is (= "2.0" (:jsonrpc response)) "Should have correct jsonrpc version")
;;               (is (= 1 (:id response)) "Should have correct id")
;;               (is (= test-data (:result response)) "Should echo test data"))))
;;         (finally
;;           (stop))))))



(deftest concurrent-requests-test
  (let [request-count 10
        latch         (CountDownLatch. request-count)
        slow-handler  (fn [_]
                        (.countDown latch)
                        (Thread/sleep 100)  ; simulate work
                        {:result "ok"})
        executor      (Executors/newScheduledThreadPool 4)
        server        (server/create-server
                       {:port     0
                        :handlers {"slow" slow-handler}
                        :executor executor})]
    (try
      (let [port     (get server :port)
            base-url (format "http://localhost:%d" port)
            requests (repeat request-count
                             {:jsonrpc "2.0"
                              :method  "slow"
                              :id      1})
            futures  (doall
                      (for [req requests]
                        (future
                          (hato/post (str base-url "/message")
                                     {:headers {"Content-Type" "application/json"}
                                      :body    (json/write-str req)}))))]

        ;; verify all requests started
        (is (.await latch 1 TimeUnit/SECONDS)
            "All requests should start within 1 second")

        ;; verify all requests complete
        (let [responses (doall (map deref futures))
              bodies    (map #(json/read-str (:body %) :key-fn keyword) responses)]
          (is (every?
               #(= {:result "ok"}
                   (get-in % [:result])) bodies)
              "All requests should complete successfully")))

      (finally
        ((:stop server))
        (.shutdown executor)
        (.awaitTermination executor 1 TimeUnit/SECONDS)))))

#_(deftest executor-shutdown-test
    (let [executor (Executors/newFixedThreadPool 2)
          server   (server/create-server
                    {:port     0
                     :handlers {"test" (fn [_] {:result "ok"})}
                     :executor executor})]
      ((:stop server))
      (is (.isShutdown executor)
          "Executor should be shutdown when server stops")))
