(ns mcp-clj.json-rpc.http-server-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [hato.client :as hato]
    [mcp-clj.json :as json]
    [mcp-clj.json-rpc.http-server :as http-server]
    [mcp-clj.json-rpc.protocols :as protocols]))

;; Test Fixtures and Helpers

(def ^:private ^:dynamic *server* nil)

(defn- http-get
  "Send HTTP GET request"
  [url & [headers]]
  (hato/get url {:headers (merge {"Accept" "application/json"} headers)
                 :throw-exceptions false}))

(defn- http-post
  "Send HTTP POST request"
  [url body & [headers]]
  (hato/post url {:headers (merge {"Content-Type" "application/json"} headers)
                  :body body
                  :throw-exceptions false}))

(defn- make-request
  "Create a JSON-RPC request map"
  [method params & [id]]
  (cond-> {:jsonrpc "2.0"
           :method method
           :params params}
    id (assoc :id id)))

(defn- with-test-server
  "Test fixture that creates a server with basic configuration"
  [f]
  (let [server (http-server/create-server {:port 0 :num-threads 2})]
    (try
      (binding [*server* server]
        (f))
      (finally
        ((:stop server))))))

(use-fixtures :each with-test-server)

;; Tests

(deftest ^:integ http-server-creation-test
  ;; Test creating an HTTP server with streamable transport
  (testing "create-server"
    (is (map? *server*))
    (is (contains? *server* :server))
    (is (contains? *server* :port))
    (is (contains? *server* :handlers))
    (is (contains? *server* :stop))
    (is (pos? (:port *server*)))))

(deftest ^:integ handler-management-test
  ;; Test handler setting and validation
  (testing "set-handlers!"
    (testing "sets valid handlers"
      (let [handlers {"test" (fn [_ _] {:result "ok"})}]
        (http-server/set-handlers! *server* handlers)
        (is (= handlers @(:handlers *server*)))))

    (testing "allows nil handlers"
      (http-server/set-handlers! *server* nil)
      (is (nil? @(:handlers *server*))))

    (testing "throws on invalid handlers"
      (is (thrown? Exception
            (http-server/set-handlers! *server* "invalid"))))))

(deftest ^:integ mcp-endpoint-discovery-test
  ;; Test GET /mcp endpoint returns transport capabilities
  (testing "GET /mcp without SSE accept header"
    (let [response (http-get (str "http://localhost:" (:port *server*) "/"))]
      (is (= 200 (:status response)))
      (is (= "application/json" (get (:headers response) "content-type")))
      (let [body (json/parse (:body response))]
        (is (= "streamable-http" (:transport body)))
        (is (= "2025-03-26" (:version body)))
        (is (:sse (:capabilities body)))
        (is (:batch (:capabilities body)))
        (is (:resumable (:capabilities body)))))))

(deftest ^:integ post-message-handling-test
  ;; Test POST requests to / endpoint
  (testing "POST /"
    (let [handlers {"echo" (fn [_ params] params)
                    "add" (fn [_ params] (+ (:a params) (:b params)))}]
      (http-server/set-handlers! *server* handlers)

      (testing "handles valid JSON-RPC request"
        (let [request (make-request "echo" {:message "hello"} 1)
              response (http-post (str "http://localhost:" (:port *server*) "/")
                                  (json/write request))]
          (is (= 200 (:status response)))
          (let [body (json/parse (:body response))]
            (is (= "2.0" (:jsonrpc body)))
            (is (= {:message "hello"} (:result body)))
            (is (= 1 (:id body))))))

      (testing "handles batch requests"
        (let [batch-request [(make-request "add" {:a 1 :b 2} 1)
                             (make-request "echo" {:test "batch"} 2)]
              response (http-post (str "http://localhost:" (:port *server*) "/")
                                  (json/write batch-request))]
          (is (= 200 (:status response)))
          (let [body (json/parse (:body response))]
            (is (vector? body))
            (is (= 2 (count body)))
            (is (= 3 (:result (first body))))
            (is (= {:test "batch"} (:result (second body)))))))

      (testing "returns method not found error"
        (let [request (make-request "unknown" {} 1)
              response (http-post (str "http://localhost:" (:port *server*) "/")
                                  (json/write request))]
          (is (= 200 (:status response)))
          (let [body (json/parse (:body response))]
            (is (= "2.0" (:jsonrpc body)))
            (is (:error body))
            (is (= -32601 (:code (:error body)))))))

      (testing "handles invalid JSON"
        (let [response (http-post (str "http://localhost:" (:port *server*) "/")
                                  "invalid json")]
          (is (= 400 (:status response)))))

      (testing "returns 503 when handlers not initialized"
        (let [unready-server (http-server/create-server {:port 0})]
          (try
            (let [request (make-request "test" {} 1)
                  response (http-post (str "http://localhost:" (:port unready-server) "/")
                                      (json/write request))]
              (is (= 503 (:status response))))
            (finally
              ((:stop unready-server)))))))))

(deftest ^:integ origin-validation-test
  ;; Test origin header validation for security
  (testing "origin validation"
    (let [allowed-origins ["https://example.com" "https://trusted.com"]
          server (http-server/create-server {:port 0 :allowed-origins allowed-origins})
          handlers {"test" (fn [_ _] {:result "ok"})}]
      (try
        (http-server/set-handlers! server handlers)

        (testing "allows requests from allowed origins"
          (let [request (make-request "test" {} 1)
                response (http-post (str "http://localhost:" (:port server) "/")
                                    (json/write request)
                                    {"Origin" "https://example.com"})]
            (is (= 200 (:status response)))))

        (testing "blocks requests from disallowed origins"
          (let [request (make-request "test" {} 1)
                response (http-post (str "http://localhost:" (:port server) "/")
                                    (json/write request)
                                    {"Origin" "https://malicious.com"})]
            (is (= 400 (:status response)))))

        (testing "allows requests without origin header"
          (let [request (make-request "test" {} 1)
                response (http-post (str "http://localhost:" (:port server) "/")
                                    (json/write request))]
            (is (= 200 (:status response)))))
        (finally
          ((:stop server)))))))

(deftest ^:integ session-management-test
  ;; Test session ID handling and management
  (testing "session management"
    (let [handlers {"test" (fn [_ _] {:result "ok"})}]
      (http-server/set-handlers! *server* handlers)

      (testing "accepts requests with session ID in header"
        (let [session-id "test-session-123"
              request (make-request "test" {} 1)
              response (http-post (str "http://localhost:" (:port *server*) "/")
                                  (json/write request)
                                  {"X-Session-ID" session-id})]
          (is (= 200 (:status response)))))

      (testing "accepts requests with session ID in query params"
        (let [session-id "test-session-456"
              request (make-request "test" {} 1)
              response (http-post (str "http://localhost:" (:port *server*) "/?session_id=" session-id)
                                  (json/write request))]
          (is (= 200 (:status response))))))))

(deftest ^:integ notification-test
  ;; Test server-side notifications (requires SSE)
  (testing "server notifications"
    (testing "notify-all! works without active sessions"
      (http-server/notify-all! *server* "test-notification" {:data "test"}))

    (testing "notify! works with unknown session"
      (http-server/notify! *server* "unknown-session" "test-notification" {:data "test"}))

    (testing "get-sessions returns empty initially"
      (is (empty? (http-server/get-sessions *server*))))))

(deftest ^:integ endpoint-routing-test
  ;; Test proper routing for different endpoints
  (testing "endpoint routing"
    (testing "returns 404 for unknown endpoints"
      (let [response (http-get (str "http://localhost:" (:port *server*) "/unknown"))]
        (is (= 404 (:status response)))))

    (testing "returns 404 for wrong HTTP method on /"
      (let [response (hato/put (str "http://localhost:" (:port *server*) "/")
                               {:body ""
                                :throw-exceptions false})]
        (is (= 404 (:status response)))))))

(deftest ^:integ protocol-implementation-test
  ;; Test JsonRpcServer protocol implementation
  (testing "JsonRpcServer protocol implementation"
    (testing "set-handlers! through protocol"
      (let [test-handler (fn [_ params] {:protocol-result params})]
        (protocols/set-handlers! *server* {"protocol-test" test-handler})
        (is (= {"protocol-test" test-handler} @(:handlers *server*)))))

    (testing "notify-all! through protocol"
      (protocols/notify-all! *server* "protocol-notification" {:protocol "broadcast"}))

    (testing "stop! through protocol is callable"
      (is (fn? (partial protocols/stop! *server*))
          "stop! should be callable through protocol"))))
