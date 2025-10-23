(ns mcp-clj.json-rpc.stdio-server-test
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.json-rpc.protocols :as protocols]
    [mcp-clj.json-rpc.stdio-server :as stdio-server])
  (:import
    (java.io
      BufferedReader
      ByteArrayOutputStream
      StringReader)))

(defn- capture-output
  "Capture output from a function that writes to *out*"
  [f]
  (with-out-str
    (f)))

(deftest test-read-json
  (testing "reads valid JSON"
    (let [reader (BufferedReader.
                   (StringReader.
                     "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"id\":1}")
                   1024)]
      (is (= [{:jsonrpc "2.0" :method "test" :id 1} nil]
             (#'stdio-server/read-json reader)))))

  (testing "handles EOF"
    (let [reader (BufferedReader. (StringReader. ""))]
      (is (nil? (#'stdio-server/read-json reader)))))

  (testing "handles malformed JSON"
    (let [reader (BufferedReader. (StringReader. "{invalid json}"))
          [result error] (#'stdio-server/read-json reader)]
      (is (= :error result))
      (is (instance? Exception error)))))

(deftest test-write-json
  (testing "writes JSON to output stream"
    (let [output (ByteArrayOutputStream.)
          writer (io/writer output)
          response {:jsonrpc "2.0" :result "success" :id 1}]
      (#'stdio-server/write-json! writer response)
      (is (= "{\"jsonrpc\":\"2.0\",\"result\":\"success\",\"id\":1}\n"
             (.toString output)))))

  (testing "handles write errors gracefully"
    (let [output (proxy [ByteArrayOutputStream] []
                   (write [bytes] (throw (RuntimeException. "Write error"))))
          response {:jsonrpc "2.0" :result "success" :id 1}
          stderr-output (capture-output
                          #(binding [*err* *out*]
                             (#'stdio-server/write-json! output response)))]
      (is (str/includes? stderr-output "JSON write error")))))

(deftest test-handle-json-rpc
  (testing "processes valid request and returns result"
    (let [handler (fn [method _params]
                    (when (= method "test")
                      {:result "success"}))
          request {:method "test" :params {:arg "value"} :id 1}
          response (with-out-str
                     (#'stdio-server/handle-json-rpc handler request))]
      (is (= {:jsonrpc "2.0" :result {:result "success"} :id 1}
             (json/parse-string response true)))))

  (testing "returns nil for handler returning nil"
    (let [handler (fn [_method _params] nil)
          request {:method "test" :params {} :id 1}
          response (#'stdio-server/handle-json-rpc handler request)]
      (is (nil? response))))

  (testing "uses simplified handler interface"
    (let [captured-args (atom [])
          handler (fn [method params]
                    (reset! captured-args [method params])
                    "result")
          request {:method "add" :params [1 2] :id 1}]
      (#'stdio-server/handle-json-rpc handler request)
      (is (= ["add" [1 2]] @captured-args)))))

(deftest ^:integ test-dispatch-rpc-call
  (testing "dispatches call successfully"
    (let [server (stdio-server/create-server {})
          handler (fn [_method params]
                    {:sum (+ (first params) (second params))})
          request {:method "add" :params [1 2] :id 1}
          response (with-out-str
                     @(#'stdio-server/dispatch-rpc-call
                       (:executor server) handler request))]
      (is (= {:jsonrpc "2.0" :result {:sum 3} :id 1}
             (json/parse-string response true)))
      (stdio-server/stop! server)))

  (testing "handles handler exceptions"
    (let [server (stdio-server/create-server {})
          handler (fn [_method _params]
                    (throw (RuntimeException. "Handler error")))
          request {:method "error" :params [] :id 1}
          response (with-out-str
                     @(#'stdio-server/dispatch-rpc-call
                       (:executor server) handler request))]
      (is (= {:jsonrpc "2.0"
              :error {:code -32603, :message "Handler error"}
              :id 1}
             (json/parse-string response true)))
      (stdio-server/stop! server))))

(deftest ^:integ test-handle-request
  (testing "handles valid request"
    (let [server (stdio-server/create-server {})
          handlers {"add" (fn [_method params]
                            {:sum (+ (first params) (second params))})}
          request {:jsonrpc "2.0" :method "add" :params [1 2] :id 1}
          output (with-out-str
                   @(#'stdio-server/handle-request
                     (:executor server)
                     handlers
                     request))]
      (is (str/includes? output "\"result\""))
      (is (str/includes? output "\"sum\":3"))
      (stdio-server/stop! server)))

  (testing "handles method not found"
    (let [server (stdio-server/create-server {})
          handlers {}
          request {:jsonrpc "2.0" :method "unknown" :params [] :id 1}
          output (with-out-str
                   (#'stdio-server/handle-request
                    (:executor server)
                    handlers
                    request))
          response (json/parse-string output true)]
      (is (= -32601 (-> response :error :code)))
      (is (str/includes? (-> response :error :message) "Method not found"))
      (stdio-server/stop! server)))

  (testing "handles validation errors"
    (let [server (stdio-server/create-server {})
          handlers {"test" (fn [_method _params] "result")}
          request {:method "test" :params []} ; missing jsonrpc field
          output (with-out-str
                   (#'stdio-server/handle-request
                    (:executor server)
                    handlers
                    request))
          response (json/parse-string output true)]
      (is (= -32600 (-> response :error :code)))
      (stdio-server/stop! server)))

  (testing "handles server overload"
    (let [server (stdio-server/create-server {:num-threads 1})
          handlers {"test" (fn [_method _params] (Thread/sleep 100) "result")}
          request {:jsonrpc "2.0" :method "test" :params [] :id 1}]
      (stdio-server/stop! server) ; shut down executor to cause rejection
      (let [output (with-out-str
                     (#'stdio-server/handle-request
                      (:executor server)
                      handlers
                      request))
            response (json/parse-string output true)]
        (is (= -32000 (-> response :error :code)))
        (is (str/includes? (-> response :error :message) "overloaded"))))))

(deftest ^:integ test-server-lifecycle
  (testing "creates server with default config"
    (let [server (stdio-server/create-server {})]
      (is (stdio-server/stdio-server? server))
      (is (some? (:executor server)))
      (is (some? (:handlers server)))
      (is (some? (:stop-fn server)))
      (stdio-server/stop! server)))

  (testing "creates server with custom config"
    (let [server (stdio-server/create-server {:num-threads 4})]
      (is (stdio-server/stdio-server? server))
      (stdio-server/stop! server))))

(deftest ^:integ test-set-handlers
  (testing "sets valid handlers"
    (let [server (stdio-server/create-server {})
          handlers {"add" (fn [_method params]
                            {:sum (+ (first params) (second params))})
                    "echo" (fn [_method params] params)}]
      (stdio-server/set-handlers! server handlers)
      (is (= handlers @(:handlers server)))
      (stdio-server/stop! server)))

  (testing "rejects invalid handlers"
    (let [server (stdio-server/create-server {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Handlers must be a map"
            (stdio-server/set-handlers! server "not-a-map")))
      (stdio-server/stop! server))))

(deftest ^:integ test-integration
  (testing "full request-response cycle"
    (let [requests [{:jsonrpc "2.0"
                     :method "add"
                     :params [1 2]
                     :id 1}
                    {:jsonrpc "2.0"
                     :method "echo"
                     :params {:message "hello"}
                     :id 2}]
          ;; Convert to JSON strings, one per line
          json-input (str/join
                       (mapv (comp #(str % "\n") json/generate-string) requests))
          response
          (with-out-str
            (with-redefs [mcp-clj.json-rpc.stdio-server/input-reader
                          (constantly
                            (BufferedReader.
                              (StringReader. json-input)))]
              (let [handlers {"add" (fn [_method params]
                                      {:sum
                                       (+ (first params) (second params))})
                              "echo" (fn [_method params]
                                       params)}
                    server (stdio-server/create-server
                             {:handlers handlers})]
                ;; server will EOF on input
                @(:server-future server))))]
      (is (str/includes? response "\"sum\":3"))
      (is (str/includes? response "\"message\":\"hello\"")))))

(deftest ^:integ test-error-handling
  ;; Test comprehensive error handling for JSON parse failures in stdio transport.
  ;; Note: stdio transport currently logs parse errors to stderr and continues
  ;; processing, which differs from HTTP/SSE that return error responses.
  (testing "handles malformed JSON gracefully"
    (let [server (stdio-server/create-server {})
          malformed-json "{invalid: json}"
          reader (BufferedReader. (StringReader. malformed-json))]

      (let [[result error] (#'stdio-server/read-json reader)]
        (is (= :error result))
        (is (instance? Exception error)))

      (stdio-server/stop! server)))

  (testing "handles various malformed JSON inputs"
    (let [test-cases ["{invalid json}"
                      "{\"a\":}"
                      "[unclosed"
                      ""
                      "not json"]]
      (doseq [malformed test-cases]
        (let [reader (BufferedReader. (StringReader. malformed))
              [result error] (#'stdio-server/read-json reader)]
          (is (= :error result)
              (str "Should return :error for: " malformed))
          (is (instance? Exception error)
              (str "Should return exception for: " malformed))))))

  (testing "continues processing after parse errors"
    (let [server (stdio-server/create-server {})
          handlers {"test" (fn [_method _params] {:result "ok"})}
          valid-request {:jsonrpc "2.0" :method "test" :params {} :id 1}
          input (str "{invalid json}\n"
                     (json/generate-string valid-request) "\n")
          reader (BufferedReader. (StringReader. input))]

      (stdio-server/set-handlers! server handlers)

      (let [[result1 error1] (#'stdio-server/read-json reader)]
        (is (= :error result1))
        (is (instance? Exception error1)))

      (let [[result2 error2] (#'stdio-server/read-json reader)]
        (is (nil? error2))
        (is (= valid-request result2)))

      (stdio-server/stop! server))))

(deftest ^:integ test-protocol-implementation
  (testing "JsonRpcServer protocol implementation"
    (testing "set-handlers! through protocol"
      (let [server (stdio-server/create-server {})
            test-handler (fn [_method params] {:protocol-result params})]
        (protocols/set-handlers! server {"protocol-test" test-handler})
        (is (= {"protocol-test" test-handler} @(:handlers server)))
        (stdio-server/stop! server)))

    (testing "notify-all! through protocol - no-op for stdio"
      (let [server (stdio-server/create-server {})]
        ;; This should not throw and should be a no-op
        (is (nil? (protocols/notify-all! server "test-notification" {:data "test"})))
        (stdio-server/stop! server)))

    (testing "stop! through protocol"
      (let [server (stdio-server/create-server {})]
        ;; Test that stop! can be called through protocol
        (is (nil? (protocols/stop! server)))))

    (testing "protocol functions work with server instance"
      (let [server (stdio-server/create-server {})
            handlers {"test" (fn [_method _params] {:test "result"})}]
        (is (satisfies? protocols/JsonRpcServer server))
        (protocols/set-handlers! server handlers)
        (is (= handlers @(:handlers server)))
        (protocols/stop! server)))))
