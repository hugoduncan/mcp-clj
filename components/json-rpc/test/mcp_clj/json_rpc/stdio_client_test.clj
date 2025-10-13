(ns mcp-clj.json-rpc.stdio-client-test
  "Tests for JSON-RPC stdio client functionality"
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.json-rpc.stdio-client :as stdio-client])
  (:import
    (java.io
      BufferedReader
      BufferedWriter
      StringReader
      StringWriter)
    (java.util.concurrent
      CompletableFuture
      ConcurrentHashMap
      TimeUnit)))

(defn create-test-client
  "Create a test JSONRPClient with string-based streams"
  []
  (let [input-stream (BufferedReader. (StringReader. ""))
        output-stream (BufferedWriter. (StringWriter.))]
    (stdio-client/create-json-rpc-client input-stream output-stream)))

(deftest json-rpc-client-creation-test
  (testing "JSONRPClient record creation and structure"
    (testing "creation with streams"
      (let [input-stream (BufferedReader. (StringReader. ""))
            output-stream (BufferedWriter. (StringWriter.))
            client (stdio-client/create-json-rpc-client input-stream output-stream)]
        (is (= (.getName (type client)) "mcp_clj.json_rpc.stdio_client.JSONRPClient"))
        (is (instance? ConcurrentHashMap (:pending-requests client)))
        (is (instance? clojure.lang.Atom (:request-id-counter client)))
        (is (some? (:executor client)))
        (is (= input-stream (:input-stream client)))
        (is (= output-stream (:output-stream client)))
        (is (= 0 @(:request-id-counter client)))
        (stdio-client/close-json-rpc-client! client)))

    (testing "creation with custom thread count"
      (let [input-stream (BufferedReader. (StringReader. ""))
            output-stream (BufferedWriter. (StringWriter.))
            client (stdio-client/create-json-rpc-client input-stream output-stream {:num-threads 4})]
        (is (= (.getName (type client)) "mcp_clj.json_rpc.stdio_client.JSONRPClient"))
        (is (= 0 @(:request-id-counter client)))
        (stdio-client/close-json-rpc-client! client)))))

(deftest request-id-generation-test
  (testing "request ID generation"
    (let [client (create-test-client)]

      (testing "sequential ID generation"
        (is (= 1 (stdio-client/generate-request-id client)))
        (is (= 2 (stdio-client/generate-request-id client)))
        (is (= 3 (stdio-client/generate-request-id client))))

      (testing "counter state is maintained"
        (is (= 3 @(:request-id-counter client))))

      (testing "concurrent ID generation produces unique IDs"
        (let [ids (atom #{})
              futures (doall
                        (for [_ (range 100)]
                          (future
                            (let [id (stdio-client/generate-request-id client)]
                              (swap! ids conj id)
                              id))))]
          ;; Wait for all futures to complete
          (doseq [f futures] @f)
          ;; All IDs should be unique
          (is (= 100 (count @ids)))
          ;; Counter should be at 103 (3 + 100)
          (is (= 103 @(:request-id-counter client)))))

      (stdio-client/close-json-rpc-client! client))))

(deftest response-handling-test
  (testing "JSON-RPC response handling"
    (let [client (create-test-client)]

      (testing "successful response handling"
        (let [future (CompletableFuture.)
              request-id 42
              response {:id request-id :result {:success true}}]
          ;; Register pending request
          (.put (:pending-requests client) request-id future)

          ;; Handle response
          (stdio-client/handle-response client response)

          ;; Verify future completed with result
          (is (.isDone future))
          (is (= {:success true} (.get future 100 TimeUnit/MILLISECONDS)))

          ;; Verify request removed from pending
          (is (nil? (.get (:pending-requests client) request-id)))))

      (testing "error response handling"
        (let [future (CompletableFuture.)
              request-id 43
              error-response {:id request-id
                              :error {:code -32601 :message "Method not found"}}]
          ;; Register pending request
          (.put (:pending-requests client) request-id future)

          ;; Handle error response
          (stdio-client/handle-response client error-response)

          ;; Verify future completed exceptionally
          (is (.isDone future))
          (is (.isCompletedExceptionally future))

          ;; Verify exception details
          (try
            (.get future 100 TimeUnit/MILLISECONDS)
            (is false "Should have thrown exception")
            (catch Exception e
              (is (instance? java.util.concurrent.ExecutionException e))
              (let [cause (.getCause e)]
                (is (instance? clojure.lang.ExceptionInfo cause))
                (is (= "JSON-RPC error" (.getMessage cause)))
                (is (= {:code -32601 :message "Method not found"}
                       (ex-data cause))))))))

      (testing "orphan response handling"
        ;; This should log a warning but not throw
        (let [orphan-response {:id 999 :result "orphaned"}]
          (is (nil? (stdio-client/handle-response client orphan-response)))))

      (stdio-client/close-json-rpc-client! client))))

(deftest notification-handling-test
  (testing "JSON-RPC notification handling"
    (testing "notification logging"
      ;; This mainly tests that notifications are logged without error
      (let [notification {:jsonrpc "2.0" :method "progress" :params {:value 50}}
            client {:notification-handler nil}]
        (is (nil? (stdio-client/handle-notification client notification)))))))

(deftest json-io-wrapper-test
  (testing "JSON I/O wrapper functions"

    (testing "write-json-with-locking!"
      (let [sw (StringWriter.)
            bw (BufferedWriter. sw)
            message {:jsonrpc "2.0" :id 1 :method "test" :params {}}]

        (stdio-client/write-json-with-locking! bw message)
        (.close bw)

        (let [output (.toString sw)]
          (is (str/includes? output "jsonrpc"))
          (is (str/includes? output "test"))
          (is (str/ends-with? output "\n")))))

    (testing "read-json-with-logging success"
      (let [json-input "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"success\"}\n"
            sr (StringReader. json-input)
            br (BufferedReader. sr)
            result (stdio-client/read-json-with-logging br)]
        (is (= {:jsonrpc "2.0" :id 1 :result "success"} result))))

    (testing "read-json-with-logging EOF"
      (let [sr (StringReader. "")
            br (BufferedReader. sr)]

        (is (nil? (stdio-client/read-json-with-logging br)))))

    (testing "read-json-with-logging error"
      (let [invalid-json "invalid json\n"
            sr (StringReader. invalid-json)
            br (BufferedReader. sr)]

        (is (thrown? Exception (stdio-client/read-json-with-logging br)))))))

(deftest message-reader-loop-test
  (testing "message reader loop functionality"

    (testing "processes responses and notifications"
      (let [client (create-test-client)
            running (atom true)
            response-future (CompletableFuture.)
            request-id 1]

        ;; Register pending request
        (.put (:pending-requests client) request-id response-future)

        ;; Create input with response and notification
        (let [json-input (str
                           "{\"jsonrpc\":\"2.0\",\"id\":" request-id ",\"result\":\"test-result\"}\n"
                           "{\"jsonrpc\":\"2.0\",\"method\":\"notification\",\"params\":{}}\n")
              sr (StringReader. json-input)
              br (BufferedReader. sr)
              ;; Start reader loop in background
              reader-future (future
                              (stdio-client/message-reader-loop
                                br
                                running
                                client))]
          ;; Wait a bit for processing
          (Thread/sleep 100)

          ;; Stop the loop
          (reset! running false)

          ;; Wait for reader to finish
          @reader-future

          ;; Verify response was processed
          (is (.isDone response-future))
          (is (= "test-result"
                 (.get response-future 100 TimeUnit/MILLISECONDS))))

        (stdio-client/close-json-rpc-client! client)))

    (testing "stops when running atom is false"
      (let [client (create-test-client)
            running (atom false)
            sr (StringReader. "{\"jsonrpc\":\"2.0\",\"method\":\"test\"}\n")
            br (BufferedReader. sr)]

        ;; This should return immediately since running is false
        (is (nil? (stdio-client/message-reader-loop br running client)))

        (stdio-client/close-json-rpc-client! client)))))

(deftest client-cleanup-test
  (testing "JSON-RPC client cleanup and resource management"

    (testing "close-json-rpc-client! cancels pending requests"
      (let [client (create-test-client)
            future1 (CompletableFuture.)
            future2 (CompletableFuture.)
            request-id-1 1
            request-id-2 2]

        ;; Add pending requests
        (.put (:pending-requests client) request-id-1 future1)
        (.put (:pending-requests client) request-id-2 future2)

        ;; Close client
        (stdio-client/close-json-rpc-client! client)

        ;; Verify futures were cancelled
        (is (.isCancelled future1))
        (is (.isCancelled future2))

        ;; Verify pending requests map was cleared
        (is (= 0 (.size (:pending-requests client))))))

    (testing "executor shutdown"
      (let [client (create-test-client)]

        ;; Verify executor is running initially
        (is (not (.isShutdown (:executor client))))

        ;; Close client
        (stdio-client/close-json-rpc-client! client)

        ;; Verify executor was shut down
        (is (.isShutdown (:executor client)))))))

(deftest integration-test
  (testing "JSONRPClient integration scenarios"

    (testing "complete request-response cycle simulation"
      (let [client (create-test-client)
            request-id (stdio-client/generate-request-id client)
            future (CompletableFuture.)]

        ;; Simulate registering a request
        (.put (:pending-requests client) request-id future)

        ;; Simulate receiving a response
        (let [response {:id request-id :result {:data "test-data"}}]
          (stdio-client/handle-response client response))

        ;; Verify the cycle completed
        (is (.isDone future))
        (is (= {:data "test-data"} (.get future)))

        (stdio-client/close-json-rpc-client! client)))

    (testing "multiple concurrent operations"
      (let [client (create-test-client)
            num-operations 50
            futures (atom [])]

        ;; Create multiple pending requests
        (doseq [_ (range num-operations)]
          (let [request-id (stdio-client/generate-request-id client)
                future (CompletableFuture.)]
            (.put (:pending-requests client) request-id future)
            (swap! futures conj {:id request-id :future future})))

        ;; Simulate responses for all requests
        (doseq [{:keys [id]} @futures]
          (let [response {:id id :result {:index id}}]
            (stdio-client/handle-response client response)))

        ;; Verify all futures completed
        (doseq [{:keys [future]} @futures]
          (is (.isDone future)))

        ;; Verify no pending requests remain
        (is (= 0 (.size (:pending-requests client))))

        (stdio-client/close-json-rpc-client! client)))))

(deftest send-request-test
  (testing "JSONRPClient send-request! functionality"
    (let [sw (StringWriter.)
          bw (BufferedWriter. sw)
          client (stdio-client/create-json-rpc-client (BufferedReader. (StringReader. "")) bw)]

      (testing "successful request sending"
        (let [future (stdio-client/send-request! client "test-method" {:param "value"} 5000)]

          ;; Flush the writer to capture output
          (.flush bw)
          (let [output (.toString sw)]
            ;; Verify request was sent
            (is (str/includes? output "test-method"))
            (is (str/includes? output "\"id\":1"))
            (is (str/includes? output "param")))

          ;; Verify future is pending
          (is (not (.isDone future)))

          ;; Simulate response
          (let [response {:id 1 :result "success"}]
            (stdio-client/handle-response client response))

          ;; Verify future completed
          (is (.isDone future))
          (is (= "success" (.get future 100 TimeUnit/MILLISECONDS)))))

      (testing "request timeout handling"
        (let [future (stdio-client/send-request! client "timeout-method" {} 50)]

          ;; Wait for timeout
          (Thread/sleep 100)

          ;; Verify future completed with timeout
          (is (.isDone future))
          (is (.isCompletedExceptionally future))))

      (stdio-client/close-json-rpc-client! client))))

(deftest send-notification-test
  (testing "JSONRPClient send-notification! functionality"
    (let [sw (StringWriter.)
          bw (BufferedWriter. sw)
          client (stdio-client/create-json-rpc-client (BufferedReader. (StringReader. "")) bw)]

      (testing "notification sending"
        (stdio-client/send-notification! client "notify-method" {:data "test"})

        ;; Flush and verify notification was sent
        (.flush bw)
        (let [output (.toString sw)]
          (is (str/includes? output "notify-method"))
          (is (str/includes? output "data"))
          (is (not (str/includes? output "\"id\":"))))

        (testing "multiple notifications"
          ;; Clear previous output
          (.getBuffer sw) (.setLength (.getBuffer sw) 0)

          (stdio-client/send-notification! client "notify-1" {})
          (stdio-client/send-notification! client "notify-2" {:value 42})

          ;; Verify all notifications sent
          (.flush bw)
          (let [output (.toString sw)]
            (is (str/includes? output "notify-1"))
            (is (str/includes? output "notify-2"))
            (is (str/includes? output "42"))))

        (stdio-client/close-json-rpc-client! client)))))
