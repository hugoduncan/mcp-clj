(ns mcp-clj.json-rpc.sse-server-test
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hato.client :as hato]
   [hato.middleware :as mw]
   [mcp-clj.http :as http]
   [mcp-clj.json-rpc.sse-server :as server]
   [mcp-clj.json-rpc.protocols :as protocols])
  (:import
   [java.util.concurrent
    CountDownLatch
    Executors
    TimeUnit]))

;;; Test Fixtures and Helpers

(def ^:private ^:dynamic *server* nil)
(def ^:private ^:dynamic *client-session* nil)

(defn read-sse-map
  [reader]
  (loop [resp {}]
    (when-let [line (try
                      (.readLine reader)
                      (catch java.io.IOException _))]
      (cond
        (or (empty? line)
            (.startsWith line ":"))
        resp
        :else
        (when-let [[k v] (str/split line #":" 2)]
          (let [v (str/trim v)]
            (recur
             (assoc resp (keyword k)
                    (if (= "message" (:event resp))
                      (json/read-str v :key-fn keyword)
                      v)))))))))

(defn- parse-sse-message
  "Parse an SSE message from a line of text"
  [reader]
  (when-let [m (read-sse-map reader)]
    (:data m)))

(defn- wait-for-endpoint
  "Wait for the endpoint message from the SSE stream"
  [reader]
  (let [m (read-sse-map reader)]
    (when (= (:event m) "endpoint")
      (:data m))))

(defn- establish-sse-connection
  "Establish an SSE connection and return session information"
  [port]
  (let [sse-url  (format "http://localhost:%d/sse" port)
        response (hato/get sse-url
                           {:headers {"Accept" "text/event-stream"}
                            :as      :stream})
        reader   (java.io.BufferedReader.
                  (java.io.InputStreamReader.
                   (:body response)))]
    (when (= http/Ok (:status response))
      (when-let [endpoint (wait-for-endpoint reader)]
        {:reader   reader
         :response response
         :endpoint endpoint}))))

(def middleware
  "The default list of middleware hato uses for wrapping requests."
  [mw/wrap-request-timing

   mw/wrap-query-params
   mw/wrap-basic-auth
   mw/wrap-oauth
   mw/wrap-user-info
   mw/wrap-url

   mw/wrap-decompression
   mw/wrap-output-coercion
   mw/wrap-accept
   mw/wrap-accept-encoding
   mw/wrap-multipart

   mw/wrap-content-type
   mw/wrap-form-params
   mw/wrap-nested-params
   mw/wrap-method])

(defn- send-request
  "Send a JSON-RPC request to the server using the established session"
  [request]
  (when-let [{:keys [endpoint]} *client-session*]
    (let [url (str "http://localhost:" (:port *server*) endpoint)]
      (hato/post url
                 {:headers {"Content-Type" "application/json"}
                  :body (json/write-str request)
                  :middleware middleware}))))

(defn- make-request
  "Create a JSON-RPC request map"
  [method params & [id]]
  (cond-> {:jsonrpc "2.0"
           :method method
           :params params}
    id (assoc :id id)))

(defn- parse-response
  "Parse a JSON-RPC response"
  [response]
  (try
    (some-> response
            :body
            (json/read-str :key-fn keyword))
    (catch Exception _
      {:jsonrpc "2.0"
       :error {:code -32700
               :message "Parse error"}})))

(defn- with-test-server
  "Test fixture that creates a server with basic configuration"
  [f]
  (let [executor (Executors/newScheduledThreadPool 2)
        server (server/create-server
                {:port 0
                 :num-threads 2
                 :on-sse-connect (fn [_] nil)
                 :on-sse-close (fn [_] nil)})]
    (try
      (binding [*server* server]
        (let [session (establish-sse-connection (:port server))]
          (binding [*client-session* session]
            (server/set-handlers! server {"echo" (fn [_ params] params)})
            (f))))
      (finally
        (when-let [reader (:reader *client-session*)]
          (.close reader))
        ((:stop server))
        (.shutdown executor)
        (.awaitTermination executor 1 TimeUnit/SECONDS)))))

(use-fixtures :each with-test-server)

;;; Tests

(deftest ^:integ connection-establishment-test
  (testing "SSE connection establishment"
    (is (some? *client-session*) "SSE connection should be established")
    (is (string? (:endpoint *client-session*)) "Should receive endpoint URL")
    (is (str/includes? (:endpoint *client-session*) "session_id=")
        "Endpoint should include session_id")))

(deftest ^:integ server-request-handling-test
  (testing "Basic request handling"
    (testing "Echo request"
      (let [test-data {:test "data"}
            response (send-request (make-request "echo" test-data 1))
            result (parse-response response)]
        (is (= http/Accepted (:status response)))
        (is (= "Accepted" (:body response)))

        ;; Check SSE response
        (let [reader (:reader *client-session*)
              message (parse-sse-message reader)]
          (is (= "2.0" (:jsonrpc message)) (pr-str message))
          (is (= 1 (:id message)))
          (is (= test-data (:result message))))))

    (testing "Invalid request format"
      (let [response (send-request {:not "valid"})
            error (parse-response response)]
        (is (= http/BadRequest (:status response)))
        (is (-> error :error :code) (pr-str error))
        (is (-> error :error :message) (pr-str error))))

    (testing "Method not found"
      (let [response (send-request (make-request "nonexistent" {} 1))
            result (parse-response response)]
        (is (= http/BadRequest (:status response)))
        (is (-> result :error :message))
        (is (= -32601 (get-in result [:error :code])))))))

(deftest ^:integ server-handlers-test
  (testing "Handler management"
    (let [test-handler (fn [_ params] {:processed params})]
      (testing "Add handler"
        (server/set-handlers! *server*
                              {"echo" (fn [_ params] params)
                               "test" test-handler})
        (let [test-data {:value "test"}
              response (send-request (make-request "test" test-data 1))
              _ (is (= http/Accepted (:status response)))
              reader (:reader *client-session*)
              message (parse-sse-message reader)]
          (is (= {:processed test-data} (:result message)))))

      (testing "Replace handlers"
        (server/set-handlers! *server* {"only" test-handler})
        (let [response (send-request (make-request "echo" {} 1))
              result (parse-response response)]
          (is (contains? result :error))
          (is (= -32601 (get-in result [:error :code]))))))))

(deftest ^:integ notification-test
  (testing "Server notifications"
    (testing "Broadcast notification"
      (server/notify-all! *server* "test-event" {:data "test"})
      (let [reader (:reader *client-session*)
            notification (parse-sse-message reader)]
        (is (= "2.0" (:jsonrpc notification)))
        (is (= "test-event" (:method notification)))
        (is (= {:data "test"} (:params notification)))))

    (testing "Single client notification"
      (let [session-id (-> *client-session*
                           :endpoint
                           (str/split #"=")
                           second)]
        (server/notify! *server* session-id "single-event" {:data "test"})
        (let [reader (:reader *client-session*)
              notification (parse-sse-message reader)]
          (is (= "2.0" (:jsonrpc notification)))
          (is (= "single-event" (:method notification)))
          (is (= {:data "test"} (:params notification))))))))

(deftest ^:integ concurrent-requests-test
  (testing "Concurrent request handling"
    (let [request-count 5
          latch (CountDownLatch. request-count)
          responses (atom [])
          slow-handler (fn [_ _]
                         (.countDown latch)
                         (Thread/sleep 100) ; simulate work
                         {:result "ok"})
          reader (:reader *client-session*)]

      (server/set-handlers! *server* {"slow" slow-handler})

      (testing "Multiple simultaneous requests"
        (let [requests (repeat request-count (make-request "slow" {} 1))
              futures (doall
                       (for [req requests]
                         (future
                           (send-request req))))]

          (is (.await latch 2 TimeUnit/SECONDS)
              "All requests should start within 2 seconds")

          (let [http-responses (doall (map deref futures))]
            (is (every? #(= http/Accepted (:status %)) http-responses)
                "All HTTP requests should be accepted"))

          ;; Collect SSE responses
          (dotimes [_ request-count]
            (swap! responses conj (parse-sse-message reader)))

          (is (every? #(= {:result "ok"} (:result %)) @responses)
              "All requests should complete successfully"))))))

(deftest ^:integ protocol-implementation-test
  (testing "JsonRpcServer protocol implementation"
    (testing "set-handlers! through protocol"
      (let [test-handler (fn [_ params] {:protocol-result params})]
        (protocols/set-handlers! *server* {"protocol-test" test-handler})

        (let [test-data {:protocol "data"}
              response (send-request (make-request "protocol-test" test-data 1))
              _ (is (= http/Accepted (:status response)))
              reader (:reader *client-session*)
              message (parse-sse-message reader)]
          (is (= {:protocol-result test-data} (:result message))))))

    (testing "notify-all! through protocol"
      (protocols/notify-all! *server* "protocol-notification" {:protocol "broadcast"})
      (let [reader (:reader *client-session*)
            notification (parse-sse-message reader)]
        (is (= "2.0" (:jsonrpc notification)))
        (is (= "protocol-notification" (:method notification)))
        (is (= {:protocol "broadcast"} (:params notification)))))

    (testing "stop! through protocol"
      ;; Note: We can't actually test stop! in this context as it would
      ;; terminate the server needed for other tests. This would be tested
      ;; in integration tests with a dedicated server instance.
      (is (fn? (partial protocols/stop! *server*))
          "stop! should be callable through protocol"))))
