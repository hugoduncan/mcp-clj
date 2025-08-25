(ns mcp-clj.mcp-client.tools-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.tools :as tools])
  (:import
   [java.util.concurrent CompletableFuture]))

(defn- create-mock-client
  "Create a mock client for testing"
  [transport-responses]
  (let [session (atom {})]
    {:session session
     :transport (reify
                  Object
                  (toString [_] "MockTransport"))}))

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

(deftest tool-result-test
  (testing "ToolResult creation and access"
    (let [result (tools/->ToolResult "test content" false)]
      (is (= "test content" (:content result)))
      (is (= false (:isError result))))

    (let [error-result (tools/->ToolResult "error message" true)]
      (is (= "error message" (:content error-result)))
      (is (= true (:isError error-result))))))

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
      (with-redefs [mcp-clj.mcp-client.stdio/send-request!
                    (fn [transport method params]
                      (CompletableFuture/completedFuture
                       {:tools mock-tools}))]
        (let [result (tools/list-tools-impl client)]
          (is (= mock-tools (:tools result)))
          ;; Tools should be cached
          (is (= mock-tools (#'tools/get-cached-tools client)))))))

  (testing "error handling in tools list"
    (let [client (create-mock-client {})]
      (with-redefs [mcp-clj.mcp-client.stdio/send-request!
                    (fn [transport method params]
                      (throw (ex-info "Connection failed" {})))]
        (is (thrown-with-msg?
             Exception #"Connection failed"
             (tools/list-tools-impl client)))))))

(deftest call-tool-impl-test
  (testing "successful tool call"
    (let [client (create-mock-client {})]
      (with-redefs [mcp-clj.mcp-client.stdio/send-request!
                    (fn [transport method params]
                      (CompletableFuture/completedFuture
                       {:content "tool result"
                        :isError false}))]
        (let [result (tools/call-tool-impl client "test-tool" {:input "test"})]
          (is (instance? mcp_clj.mcp_client.tools.ToolResult result))
          (is (= "tool result" (:content result)))
          (is (= false (:isError result)))))))

  (testing "tool call with error response"
    (let [client (create-mock-client {})]
      (with-redefs [mcp-clj.mcp-client.stdio/send-request!
                    (fn [transport method params]
                      (CompletableFuture/completedFuture
                       {:content "Tool execution failed"
                        :isError true}))]
        (let [result (tools/call-tool-impl client "failing-tool" {})]
          (is (= "Tool execution failed" (:content result)))
          (is (= true (:isError result)))))))

  (testing "tool call with transport error"
    (let [client (create-mock-client {})]
      (with-redefs [mcp-clj.mcp-client.stdio/send-request!
                    (fn [transport method params]
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
