(ns mcp-clj.compliance-test.capabilities.tools-test
  "Compliance tests for MCP tools capability across implementations.

  Tests verify that tools functionality works correctly with:
  - mcp-client + mcp-server (Clojure-only, in-memory transport)
  - mcp-client + Java SDK server (Clojure client with Java server)
  - Java SDK client + mcp-server (Java client with Clojure server)

  Version-specific behavior is tested using conditional assertions."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.compliance-test.test-helpers :as helpers]
    [mcp-clj.java-sdk.interop :as java-sdk]
    [mcp-clj.mcp-client.core :as client]
    [mcp-clj.mcp-server.core :as mcp-server]))

;; Compliance Tests

(deftest ^:integ tools-list-compliance-test
  ;; Test that tools/list returns available tools with correct schema
  (testing "tools/list returns available tools"
    (helpers/run-test-across-implementations
      (fn [client-type protocol-version {:keys [client]}]
        (let [result (if (= client-type :clojure)
                       @(client/list-tools client)
                       @(java-sdk/list-tools client))
              tools (:tools result)]

          ;; Basic structure
          (is (vector? tools))
          (is (= 3 (count tools)))

          ;; Tool names
          (let [tool-names (set (map :name tools))]
            (is (contains? tool-names "echo"))
            (is (contains? tool-names "add"))
            (is (contains? tool-names "error")))

          ;; Required fields for all versions
          (doseq [tool tools]
            (is (string? (:name tool)))
            (is (string? (:description tool)))
            (is (map? (:inputSchema tool))))

          ;; Version-specific fields
          (when (>= (compare protocol-version "2025-03-26") 0)
            (testing "annotations present in 2025-03-26+"
              (let [echo-tool (first (filter #(= "echo" (:name %)) tools))]
                (is (map? (:annotations echo-tool))))))

          (when (>= (compare protocol-version "2025-06-18") 0)
            (testing "title field present in 2025-06-18+"
              (let [echo-tool (first (filter #(= "echo" (:name %)) tools))]
                (is (string? (:title echo-tool)))
                (is (= "Echo Tool" (:title echo-tool)))))

            (testing "outputSchema present in 2025-06-18+"
              (let [add-tool (first (filter #(= "add" (:name %)) tools))]
                (is (map? (:outputSchema add-tool)))))))))))

(deftest ^:integ tools-call-compliance-test
  ;; Test that tools/call executes tools with arguments and returns results
  (testing "tools/call executes tools correctly"
    (helpers/run-test-across-implementations
      (fn [client-type _protocol-version {:keys [client]}]
        (testing "echo tool execution"
          (let [result (if (= client-type :clojure)
                         @(client/call-tool client "echo" {:message "test"})
                         @(java-sdk/call-tool client "echo" {:message "test"}))
                content (:content result)]

            (is (vector? content))
            (is (= 1 (count content)))
            (is (= "text" (:type (first content))))
            (is (= "Echo: test" (:text (first content))))
            (is (false? (:isError result)))))

        (testing "add tool execution"
          (let [result (if (= client-type :clojure)
                         @(client/call-tool client "add" {:a 5 :b 7})
                         @(java-sdk/call-tool client "add" {:a 5 :b 7}))
                content (:content result)]

            (is (= "12" (:text (first content))))
            (is (false? (:isError result)))))))))

(deftest ^:integ tools-error-handling-compliance-test
  ;; Test error handling for invalid tool calls
  (testing "error handling for invalid tool calls"
    (helpers/run-test-across-implementations
      (fn [client-type _protocol-version {:keys [client]}]
        (testing "tool execution error"
          (let [result (if (= client-type :clojure)
                         @(client/call-tool client "error" {:message "test error"})
                         @(java-sdk/call-tool client "error" {:message "test error"}))]

            (is (true? (:isError result)))
            (is (vector? (:content result)))))

        (testing "non-existent tool returns JSON-RPC error"
          ;; Per MCP spec, errors in _finding_ the tool should be
          ;; reported as JSON-RPC protocol errors (code -32602),
          ;; not as tool execution errors with isError: true
          (try
            (if (= client-type :clojure)
              @(client/call-tool client "nonexistent" {:arg "value"})
              @(java-sdk/call-tool client "nonexistent" {:arg "value"}))
            ;; Should not reach here - should throw exception
            (is false "Expected exception for non-existent tool")
            (catch java.util.concurrent.ExecutionException e
              ;; Client wraps the error in ExecutionException
              (let [cause (.getCause e)
                    error-data (ex-data cause)]
                ;; Verify it's a JSON-RPC error response
                (is (some? (:error error-data))
                    "Error response should contain :error field")
                (when-let [error (:error error-data)]
                  ;; Verify error code is -32602 (INVALID_PARAMS)
                  (is (= -32602 (:code error))
                      "Error code should be -32602 for unknown tool")
                  ;; Verify error message mentions the tool name
                  (is (str/includes? (:message error) "nonexistent")
                      "Error message should include tool name"))))
            (catch Exception e
              ;; Other transports might throw directly
              (let [error-data (ex-data e)]
                (is (some? (:error error-data))
                    "Error response should contain :error field")
                (when-let [error (:error error-data)]
                  (is (= -32602 (:code error))
                      "Error code should be -32602 for unknown tool")
                  (is (str/includes? (:message error) "nonexistent")
                      "Error message should include tool name"))))))

        (testing "missing required arguments"
          (let [result (if (= client-type :clojure)
                         @(client/call-tool client "echo" {})
                         @(java-sdk/call-tool client "echo" {}))]
            ;; Should return error result
            (is (true? (:isError result)))))))))

(deftest ^:integ tools-list-changed-notification-test
  ;; Test that notifications/tools/list_changed is sent when tools change
  (testing "notifications/tools/list_changed sent when tools change"
    (helpers/run-test-across-implementations
      (fn [client-type _protocol-version {:keys [client server]}]
        (let [received-notifications (atom [])
              ;; Set up notification handler by subscribing to tools changes
              _ (when (= client-type :clojure)
                  (client/subscribe-tools-changed!
                    client
                    (fn [params]
                      (swap! received-notifications conj
                             {:method "notifications/tools/list_changed" :params params}))))
              ;; Add a new tool to trigger notification
              new-tool {:name "new-tool"
                        :description "A new test tool"
                        :inputSchema {:type "object"
                                      :properties {:value {:type "string"}}
                                      :required ["value"]}
                        :implementation (fn [_context {:keys [value]}]
                                          {:content [{:type "text" :text (str "New: " value)}]
                                           :isError false})}
              _ (mcp-server/add-tool! server new-tool)
              ;; Wait for notification to be processed
              _ (Thread/sleep 200)]

          ;; Verify notification was received
          (is (seq @received-notifications)
              "Should receive at least one notification")
          (is (some #(= "notifications/tools/list_changed" (:method %))
                    @received-notifications)
              "Should receive tools/list_changed notification"))))))
