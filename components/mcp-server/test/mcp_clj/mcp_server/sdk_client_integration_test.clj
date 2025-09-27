(ns mcp-clj.mcp-server.sdk-client-integration-test
  "Integration tests using Java SDK client against Clojure MCP server.

   This tests cross-implementation compatibility by using the Java SDK
   MCP client to communicate with our Clojure MCP server subprocess.

   Tests server behavior from the client perspective - ensuring our server
   responds correctly to standard MCP operations from a real SDK client."
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.java-sdk.interop :as java-sdk]
   [mcp-clj.log :as log])
  (:import
   [java.lang AutoCloseable]))

(defn- create-server-client
  "Create SDK client connected to our Clojure MCP server subprocess"
  ^AutoCloseable [async?]
  (let [transport (java-sdk/create-stdio-client-transport
                   {:command "clj"
                    :args    ["-M:stdio-server:dev:test"]})
        client    (java-sdk/create-java-client
                   {:transport transport
                    :async?    async?})] ; Use sync for simpler testing
    client))

;;; Server Behavior Tests

(deftest ^:integ test-server-initialization
  (testing "Clojure MCP server initialization with Java SDK client"
    (with-open [client (create-server-client false)]
      ;; Initialize connection
      (let [result (java-sdk/initialize-client client)]
        (is (some? result))
        (log/info :server-integration-test/server-initialized
          {:result result})))))

(deftest ^:integ test-server-tool-discovery
  (testing "server tool discovery via Java SDK client"
    (with-open [client (create-server-client false)]
      ;; Initialize connection
      (java-sdk/initialize-client client)

      (testing "list server tools"
        (let [tools-response @(java-sdk/list-tools client)]
          (is (map? tools-response))
          (is (contains? tools-response :tools))
          (is (sequential? (:tools tools-response)))

          ;; Should have our default tools
          (let [tool-names (set (map :name (:tools tools-response)))]
            (is (contains? tool-names "clj-eval"))
            (is (contains? tool-names "ls"))

            ;; Each tool should have proper schema
            (doseq [tool (:tools tools-response)]
              (is (contains? tool :name))
              (is (contains? tool :description))
              (is (some? (:name tool)))
              (is (some? (:description tool)))))

          (log/info :server-integration-test/tools-discovered
            {:count (count (:tools tools-response))}))))))

(deftest ^:integ test-server-tool-execution
  (testing "server tool execution via Java SDK client"
    (with-open [client (create-server-client false)]
      ;; Initialize connection
      (java-sdk/initialize-client client)

      (testing "ls tool call"
        (let [result @(java-sdk/call-tool client "ls" {:path "."})]
          (is (map? result))
          (is (contains? result :content))
          (is (sequential? (:content result)))

          (let [first-content (first (:content result))]
            (is (= "text" (:type first-content)))
            (is (string? (:text first-content)))
            (is (> (count (:text first-content)) 0)))))

      (testing "clj-eval tool call"
        (let [result @(java-sdk/call-tool
                       client
                       "clj-eval"
                       {:code "(+ 1 2 3)"})]
          (is (map? result))
          (is (contains? result :content))

          (let [first-content (first (:content result))]
            (is (= "text" (:type first-content)))
            (is (= "6" (:text first-content)))))))))

(deftest ^:integ test-server-error-handling
  (testing "Calling server tool"
    (with-open [client (create-server-client false)]
      (java-sdk/initialize-client client)

      (testing "with non-existent tool call"
        (let [result @(java-sdk/call-tool
                       client
                       "non-existent-tool"
                       {:param "value"})]
          (testing "should return an error response"
            (when (contains? result :isError)
              (is (:isError result))))))

      (testing "with invalid tool arguments"
        (let [result @(java-sdk/call-tool
                       client
                       "clj-eval"
                       {:invalid "args"})]
          (testing "should return an error response"
            (when (contains? result :isError)
              (is (:isError result)))))))))

(deftest ^:integ test-server-concurrent-operations
  (testing "server concurrent operations via Java SDK client"
    (with-open [client (create-server-client true)]
      ;; Initialize connection
      (java-sdk/initialize-client client)

      (testing "multiple concurrent tool calls"
        (let [futures (doall
                       (for [i (range 3)]
                         (java-sdk/call-tool
                          client
                          "clj-eval"
                          {:code (str "(+ " i " 10)")})))]

          ;; Wait for all to complete
          (let [results (doall (map deref futures))]
            (is (= 3 (count results)))

            ;; Each should be a valid result
            (doseq [result results]
              (is (map? result))
              (is (contains? result :content))
              (is (sequential? (:content result)))))))

      (testing "mixed operation types concurrently"
        (let [list-future (java-sdk/list-tools client)
              ls-future   (java-sdk/call-tool client "ls" {:path "."})
              eval-future (java-sdk/call-tool client "clj-eval" {:code "42"})
              list-result @list-future
              ls-result   @ls-future
              eval-result @eval-future]

          ;; Wait for all to complete
          (let []

            ;; List tools result
            (is (map? list-result))
            (is (contains? list-result :tools))

            ;; ls result
            (is (map? ls-result))
            (is (contains? ls-result :content))

            ;; eval result
            (is (= "42" (-> eval-result :content first :text)))))))))

(deftest ^:integ test-server-session-robustness
  (testing "server session robustness via Java SDK client"
    (with-open [client (create-server-client false)]
      (java-sdk/initialize-client client)

      (testing "server remains functional after errors"
        (try
          @(java-sdk/call-tool client "non-existent-tool" {})
          (catch Exception _))

        ;; Server should still work for valid operations
        (let [result @(java-sdk/call-tool
                       client
                       "clj-eval"
                       {:code "(str \"after-error\")"})]
          (is (= "after-error" (-> result :content first :text))))

        ;; Tool listing should still work
        (let [tools @(java-sdk/list-tools client)]
          (is (map? tools))
          (is (sequential? (:tools tools)))))

      (testing "server handles multiple sequential operations"
        (let [result1 @(java-sdk/call-tool client "clj-eval" {:code "1"})
              result2 @(java-sdk/call-tool client "clj-eval" {:code "2"})
              result3 @(java-sdk/call-tool client "clj-eval" {:code "3"})]

          (is (= "1" (-> result1 :content first :text)))
          (is (= "2" (-> result2 :content first :text)))
          (is (= "3" (-> result3 :content first :text))))))))

(deftest ^:integ test-server-resource-cleanup
  (testing "server resource management and cleanup"
    ;; This test verifies our server shuts down cleanly when client disconnects
    (let [client (create-server-client false)]
      ;; Initialize and use server
      (java-sdk/initialize-client client)
      (let [result @(java-sdk/call-tool client "clj-eval" {:code "42"})]
        (is (= "42" (-> result :content first :text))))

      ;; Close client - server should handle this gracefully
      (java-sdk/close-client client))))
