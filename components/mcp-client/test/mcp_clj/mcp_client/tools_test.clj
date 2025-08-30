(ns mcp-clj.mcp-client.tools-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.tools :as tools])
  (:import
   [java.util.concurrent CompletableFuture]))

(defn- create-mock-client
  "Create a mock client for testing"
  [transport-responses]
  (let [session (atom {})
        string-writer (java.io.StringWriter.)
        buffered-writer (java.io.BufferedWriter. string-writer)
        mock-json-rpc-client {:request-id-counter (atom 0)
                              :pending-requests (java.util.concurrent.ConcurrentHashMap.)
                              :output-stream buffered-writer}]
    {:session session
     :transport {:json-rpc-client mock-json-rpc-client}}))

(defn- create-mock-transport
  "Create a mock transport that returns predefined responses"
  [responses]
  (let [call-count (atom 0)]
    (fn [method params]
      (let [count (swap! call-count inc)
            response (get responses [method params]
                          (get responses method))]
        (if response
          (doto (CompletableFuture.)
            (.complete response))
          (doto (CompletableFuture.)
            (.completeExceptionally
             (ex-info "No mock response configured"
                      {:method method :params params}))))))))

(deftest call-tool-success-test
  (testing "successful tool execution returns content directly"
    (let [client (create-mock-client {"tools/call" {:content "success result" :isError false}})]
      (is (= "success result" (tools/call-tool-impl client "test-tool" {})))))

  (testing "tool execution with JSON content parsing"
    (let [client (create-mock-client {"tools/call" {:content "{\"key\": \"value\"}" :isError false}})]
      (is (= {"key" "value"} (tools/call-tool-impl client "test-tool" {}))))))

(deftest call-tool-error-test
  (testing "tool execution throws on error"
    (let [client (create-mock-client {"tools/call" {:content "error message" :isError true}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tool call failed: test-tool"
                            (tools/call-tool-impl client "test-tool" {}))))))

(deftest tools-cache-test
  (testing "tools cache creation and access"
    (let [client (create-mock-client {})]
      ;; Cache should be created on first access
      (is (nil? @(#'tools/get-tools-cache client)))

      ;; Cache tools
      (let [test-tools [{:name "test-tool" :description "A test tool"}]]
        (#'tools/cache-tools! client test-tools)
        (is (= test-tools (#'tools/get-cached-tools client)))))))

(deftest list-tools-impl-test
  (testing "successful tools list request"
    (let [mock-tools [{:name "echo" :description "Echo tool"}
                      {:name "calc" :description "Calculator tool"}]
          mock-transport (create-mock-transport
                          {"tools/list" {:tools mock-tools}})
          client (assoc (create-mock-client {})
                        :transport {:send-request! mock-transport})]

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
    (let [client (create-mock-client {})]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (throw (ex-info "Connection failed" {})))]
        (is (thrown-with-msg?
             Exception #"Connection failed"
             (tools/list-tools-impl client)))))))

(deftest call-tool-impl-test
  (testing "successful tool call"
    (let [client (create-mock-client {})]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (CompletableFuture/completedFuture
                       {:content "tool result"
                        :isError false}))]
        (let [result (tools/call-tool-impl client "test-tool" {:input "test"})]
          (is (= "tool result" result))))))

  (testing "tool call with error response"
    (let [client (create-mock-client {})]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (CompletableFuture/completedFuture
                       {:content "Tool execution failed"
                        :isError true}))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Tool execution failed"
             (tools/call-tool-impl client "failing-tool" {}))))))

  (testing "tool call with transport error"
    (let [client (create-mock-client {})]
      (with-redefs [mcp-clj.json-rpc.stdio-client/send-request!
                    (fn [json-rpc-client method params timeout-ms]
                      (throw (ex-info "Transport error" {})))]
        (is (thrown-with-msg?
             Exception #"Transport error"
             (tools/call-tool-impl client "test-tool" {})))))))

(deftest available-tools?-impl-test
  (testing "returns true when cached tools exist"
    (let [client (create-mock-client {})]
      (#'tools/cache-tools! client [{:name "test-tool"}])
      (is (true? (tools/available-tools?-impl client)))))

  (testing "returns false when no cached tools"
    (let [client (create-mock-client {})]
      (is (false? (tools/available-tools?-impl client)))))

  (testing "queries server when no cached tools"
    (let [client (create-mock-client {})]
      (with-redefs [tools/list-tools-impl
                    (fn [_client]
                      {:tools [{:name "server-tool"}]})]
        (is (true? (tools/available-tools?-impl client))))))

  (testing "returns false on server error"
    (let [client (create-mock-client {})]
      (with-redefs [tools/list-tools-impl
                    (fn [_client]
                      (throw (ex-info "Server error" {})))]
        (is (false? (tools/available-tools?-impl client)))))))
