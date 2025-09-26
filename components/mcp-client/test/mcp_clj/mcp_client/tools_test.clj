(ns mcp-clj.mcp-client.tools-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.tools :as tools]
   [mcp-clj.json-rpc.stdio-client :as stdio-client])
  (:import
   [java.util.concurrent CompletableFuture ConcurrentHashMap]
   [java.io StringWriter BufferedWriter]))

(defn- create-mock-client
  "Create a mock client for testing"
  []
  (let [session (atom {})
        string-writer (StringWriter.)
        buffered-writer (BufferedWriter. string-writer)
        ;; Create a proper JSONRPClient record instance
        mock-json-rpc-client (stdio-client/->JSONRPClient
                              (ConcurrentHashMap.) ; pending-requests
                              (atom 0) ; request-id-counter
                              nil ; executor
                              nil ; input-stream
                              buffered-writer ; output-stream
                              (atom false) ; running
                              nil)] ; reader-future
    {:session session
     :transport {:json-rpc-client mock-json-rpc-client}}))

(deftest call-tool-success-test
  ;; Tests successful tool execution returns content directly
  (testing "successful tool execution returns content directly"
    (let [client (create-mock-client)]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (CompletableFuture/completedFuture
                       {:content "success result" :isError false}))]
        (is (= "success result"
               (tools/call-tool-impl client "test-tool" {}))))))

  (testing "tool execution with JSON content parsing"
    (let [client (create-mock-client)]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (CompletableFuture/completedFuture
                       {:content [{:type "text" :text "{\"key\": \"value\"}"}]
                        :isError false}))]
        ;; Should return the parsed content - access the data field from first item
        (let [result (tools/call-tool-impl client "test-tool" {})]
          (is (vector? result))
          (is (= 1 (count result)))
          (let [first-item (first result)]
            (is (= "text" (:type first-item)))
            (is (nil? (:text first-item))) ; text should be nil after parsing
            (is (= {:key "value"} (:data first-item)))))))))

(deftest call-tool-error-test
  ;; Tests tool execution throws on error
  (testing "tool execution throws on error"
    (let [client (create-mock-client)]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (CompletableFuture/completedFuture
                       {:content "error message" :isError true}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Tool execution failed: test-tool"
                              (tools/call-tool-impl client "test-tool" {})))))))

(deftest tools-cache-test
  ;; Tests tools cache creation and access
  (testing "tools cache creation and access"
    (let [client (create-mock-client)]
      ;; Cache should be created on first access
      (is (nil? @(#'tools/get-tools-cache client)))

      ;; Cache tools
      (let [test-tools [{:name "test-tool" :description "A test tool"}]]
        (#'tools/cache-tools! client test-tools)
        (is (= test-tools (#'tools/get-cached-tools client)))))))

(deftest list-tools-impl-test
  ;; Tests successful tools list request
  (testing "successful tools list request"
    (let [mock-tools [{:name "echo" :description "Echo tool"}
                      {:name "calc" :description "Calculator tool"}]
          client (create-mock-client)]

      ;; Mock stdio/send-request! to return our mock transport response
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (CompletableFuture/completedFuture
                       {:tools mock-tools}))]
        (let [result (tools/list-tools-impl client)]
          (is (= mock-tools (:tools result)))
          ;; Tools should be cached
          (is (= mock-tools (#'tools/get-cached-tools client)))))))

  (testing "error handling in tools list"
    (let [client (create-mock-client)]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (throw (ex-info "Connection failed" {})))]
        (is (thrown-with-msg?
             Exception #"Connection failed"
             (tools/list-tools-impl client)))))))

(deftest call-tool-impl-test
  ;; Tests successful tool call
  (testing "successful tool call"
    (let [client (create-mock-client)]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (CompletableFuture/completedFuture
                       {:content "tool result"
                        :isError false}))]
        (let [result (tools/call-tool-impl client "test-tool" {:input "test"})]
          (is (= "tool result" result))))))

  (testing "tool call with error response"
    (let [client (create-mock-client)]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (CompletableFuture/completedFuture
                       {:content "Tool execution failed"
                        :isError true}))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Tool execution failed"
             (tools/call-tool-impl client "failing-tool" {}))))))

  (testing "tool call with transport error"
    (let [client (create-mock-client)]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (throw (ex-info "Transport error" {})))]
        (is (thrown-with-msg?
             Exception #"Transport error"
             (tools/call-tool-impl client "test-tool" {})))))))

(deftest available-tools?-impl-test
  ;; Tests available tools checking logic
  (testing "returns true when cached tools exist"
    (let [client (create-mock-client)]
      (#'tools/cache-tools! client [{:name "test-tool"}])
      (is (true? (tools/available-tools?-impl client)))))

  (testing "returns false when no cached tools and no server tools"
    (let [client (create-mock-client)]
      (with-redefs [tools/list-tools-impl
                    (fn [_client]
                      {:tools []})]
        (is (false? (tools/available-tools?-impl client))))))

  (testing "queries server when no cached tools"
    (let [client (create-mock-client)]
      (with-redefs [tools/list-tools-impl
                    (fn [_client]
                      {:tools [{:name "server-tool"}]})]
        (is (true? (tools/available-tools?-impl client))))))

  (testing "returns false on server error"
    (let [client (create-mock-client)]
      (with-redefs [tools/list-tools-impl
                    (fn [_client]
                      (throw (ex-info "Server error" {})))]
        (is (false? (tools/available-tools?-impl client)))))))
