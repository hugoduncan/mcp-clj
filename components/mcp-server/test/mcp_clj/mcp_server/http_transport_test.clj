(ns mcp-clj.mcp-server.http-transport-test
  (:require
   [clojure.data.json :as json]
   [clojure.test :refer [deftest is testing]]
   [hato.client :as hato]
   [mcp-clj.mcp-server.core :as mcp-core]))

;;; Test Helpers

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

(defn- make-json-rpc-request
  "Create a JSON-RPC request map"
  [method params & [id]]
  (cond-> {:jsonrpc "2.0"
           :method method
           :params params}
    id (assoc :id id)))

(defn- get-server-port
  "Extract port from a running server"
  [server]
  (-> server :json-rpc-server deref :port))

(defn- start-test-server
  "Start an MCP server with HTTP transport for testing"
  [& [opts]]
  (mcp-core/create-server (merge {:transport {:type :http :port 0}} opts)))

(defmacro with-test-server
  "Execute body with a test server, ensuring cleanup"
  [[server-binding & [server-opts]] & body]
  `(let [~server-binding (start-test-server ~server-opts)]
     (try
       ~@body
       (finally
         ((:stop ~server-binding))))))

;;; Tests

(deftest ^:integ http-transport-creation-test
  ;; Test creating MCP server with HTTP transport
  (with-test-server [server]
    (testing "HTTP transport server creation"
      (is (some? server))
      (is (pos? (get-server-port server))))))

(deftest ^:integ mcp-capabilities-endpoint-test
  ;; Test GET /mcp endpoint returns MCP transport capabilities
  (with-test-server [server]
    (testing "GET /mcp endpoint"
      (let [response (http-get (str "http://localhost:" (get-server-port server) "/"))]
        (is (= 200 (:status response)))
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (= "streamable-http" (:transport body)))
          (is (= "2025-03-26" (:version body)))
          (is (:sse (:capabilities body)))
          (is (:batch (:capabilities body)))
          (is (:resumable (:capabilities body))))))))

(deftest ^:integ mcp-initialize-test
  ;; Test MCP initialization over HTTP transport
  (with-test-server [server]
    (testing "MCP initialization"
      (let [port (get-server-port server)
            init-request (make-json-rpc-request "initialize"
                                                {:protocolVersion "2024-11-05"
                                                 :capabilities {}
                                                 :clientInfo {:name "test-client"
                                                              :version "1.0.0"}}
                                                1)
            response (http-post (str "http://localhost:" port "/")
                                (json/write-str init-request))]
        (is (= 200 (:status response)))
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (= "2.0" (:jsonrpc body)))
          (is (= 1 (:id body)))
          (is (some? (:result body)))
          (is (some? (get-in body [:result :serverInfo])))
          (is (= "mcp-clj" (get-in body [:result :serverInfo :name]))))))))

(deftest ^:integ mcp-ping-test
  ;; Test MCP ping over HTTP transport
  (with-test-server [server]
    (testing "MCP ping"
      (let [port (get-server-port server)
            ping-request (make-json-rpc-request "ping" {} 2)
            response (http-post (str "http://localhost:" port "/")
                                (json/write-str ping-request))]
        (is (= 200 (:status response)))
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (= "2.0" (:jsonrpc body)))
          (is (= 2 (:id body)))
          (is (= {} (:result body))))))))

(deftest ^:integ mcp-list-tools-test
  ;; Test MCP tools/list over HTTP transport
  (with-test-server [server]
    (testing "MCP tools/list"
      (let [port (get-server-port server)
            list-tools-request (make-json-rpc-request "tools/list" {} 3)
            response (http-post (str "http://localhost:" port "/")
                                (json/write-str list-tools-request))]
        (is (= 200 (:status response)))
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (= "2.0" (:jsonrpc body)))
          (is (= 3 (:id body)))
          (is (some? (:result body)))
          (is (vector? (get-in body [:result :tools]))))))))

(deftest ^:integ http-batch-request-test
  ;; Test JSON-RPC batch requests over HTTP transport
  (with-test-server [server]
    (testing "HTTP batch request"
      (let [port (get-server-port server)
            batch-request [(make-json-rpc-request "ping" {} 1)
                           (make-json-rpc-request "tools/list" {} 2)]
            response (http-post (str "http://localhost:" port "/")
                                (json/write-str batch-request))]
        (is (= 200 (:status response)))
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (vector? body))
          (is (= 2 (count body)))
          (is (every? #(= "2.0" (:jsonrpc %)) body))
          (is (= #{1 2} (set (map :id body)))))))))

(deftest ^:integ http-with-origin-validation-test
  ;; Test HTTP transport with origin validation
  (with-test-server [server-with-origins {:allowed-origins ["https://example.com"]}]
    (testing "HTTP transport with origin validation"
      (let [port (get-server-port server-with-origins)
            ping-request (make-json-rpc-request "ping" {} 1)]

        (testing "allows requests from allowed origins"
          (let [response (http-post (str "http://localhost:" port "/")
                                    (json/write-str ping-request)
                                    {"Origin" "https://example.com"})]
            (is (= 200 (:status response)))))

        (testing "blocks requests from disallowed origins"
          (let [response (http-post (str "http://localhost:" port "/")
                                    (json/write-str ping-request)
                                    {"Origin" "https://malicious.com"})]
            (is (= 400 (:status response)))))

        (testing "allows requests without origin header"
          (let [response (http-post (str "http://localhost:" port "/")
                                    (json/write-str ping-request))]
            (is (= 200 (:status response)))))))))
