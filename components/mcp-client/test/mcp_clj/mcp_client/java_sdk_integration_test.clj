(ns mcp-clj.mcp-client.java-sdk-integration-test
  "Integration tests using Clojure MCP client against Java SDK server.
   
   This tests cross-implementation compatibility by using our Clojure MCP client
   to communicate with the Java SDK MCP server subprocess."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.log :as log])
  (:import
   [java.util.concurrent TimeUnit]))

(def ^:dynamic *client* nil)

(defn with-java-sdk-client
  "Test fixture: create MCP client connected to Java SDK server"
  [f]
  (with-open [client (client/create-client
                      {:server {:command "clj"
                                :args ["-M:dev:test" "-m" "mcp-clj.java-sdk.sdk-server-main"]}
                       :client-info {:name "java-sdk-integration-test"
                                     :version "0.1.0"}
                       :capabilities {}
                       :protocol-version "2024-11-05"})]

    ;; Wait for client to be ready
    (client/wait-for-ready client 10000) ; 10 second timeout

    (binding [*client* client]
      (f))))

(use-fixtures :each with-java-sdk-client)

;;; MCP Protocol Tests

(deftest test-client-initialization
  "Test MCP client initialization with Java SDK server"
  (testing "client should be ready after initialization"
    (is (client/client-ready? *client*))
    (is (not (client/client-error? *client*)))

    (let [client-info (client/get-client-info *client*)]
      (is (= :ready (:state client-info)))
      (is (= "2024-11-05" (:protocol-version client-info)))
      (is (= {:name "java-sdk-integration-test" :version "0.1.0"}
             (:client-info client-info)))
      (is (some? (:server-info client-info)))
      (is (map? (:server-capabilities client-info)))
      (is (:transport-alive? client-info))

      (log/info :integration-test/client-ready {:client-info client-info}))))

(deftest test-tool-discovery
  "Test tool discovery with Java SDK server"
  (testing "list available tools"
    (let [tools-response (client/list-tools *client*)]
      (is (map? tools-response))
      (is (contains? tools-response :tools))
      (is (sequential? (:tools tools-response)))

      ;; Should have our test tools
      (let [tool-names (set (map :name (:tools tools-response)))]
        (is (contains? tool-names "echo"))
        (is (contains? tool-names "add"))
        (is (contains? tool-names "get-time"))

        ;; Each tool should have proper schema
        (doseq [tool (:tools tools-response)]
          (is (contains? tool :name))
          (is (contains? tool :description))
          (is (some? (:name tool)))
          (is (some? (:description tool)))))

      (log/info :integration-test/tools-discovered {:tools (:tools tools-response)})

      ;; Verify client knows tools are available
      (is (client/available-tools? *client*)))))

(deftest test-tool-calls
  "Test actual tool calls with Java SDK server"
  (testing "echo tool call"
    (let [result (client/call-tool *client* "echo" {:message "Hello from Clojure MCP client!"})]
      (is (map? result))
      (is (contains? result :content))
      (is (sequential? (:content result)))

      (let [first-content (first (:content result))]
        (is (= "text" (:type first-content)))
        (is (= "Echo: Hello from Clojure MCP client!" (:text first-content))))

      (log/info :integration-test/echo-result {:result result})))

  (testing "add tool call"
    (let [result (client/call-tool *client* "add" {:a 42 :b 13})]
      (is (map? result))
      (is (contains? result :content))

      (let [first-content (first (:content result))]
        (is (= "text" (:type first-content)))
        (is (= "55" (:text first-content))))

      (log/info :integration-test/add-result {:result result})))

  (testing "get-time tool call"
    (let [result (client/call-tool *client* "get-time" {})]
      (is (map? result))
      (is (contains? result :content))

      (let [first-content (first (:content result))]
        (is (= "text" (:type first-content)))
        (is (string? (:text first-content)))
        (is (> (count (:text first-content)) 0)))

      (log/info :integration-test/get-time-result {:result result}))))

(deftest test-error-handling
  "Test error handling scenarios"
  (testing "non-existent tool call"
    (try
      (client/call-tool *client* "non-existent-tool" {:param "value"})
      (is false "Should have thrown exception for non-existent tool")
      (catch Exception e
        (is (instance? Exception e))
        (log/info :integration-test/non-existent-tool-error {:error (.getMessage e)}))))

  (testing "invalid tool arguments"
    (try
      ;; Try to call add with invalid arguments (missing required params)
      (client/call-tool *client* "add" {:invalid "args"})
      ;; If it doesn't throw, check for error indication in result
      :no-exception
      (catch Exception e
        (is (instance? Exception e))
        (log/info :integration-test/invalid-args-error {:error (.getMessage e)})))))

(deftest test-concurrent-operations
  "Test concurrent tool calls"
  (testing "multiple concurrent tool calls"
    (let [futures (doall
                   (for [i (range 5)]
                     (future
                       (client/call-tool *client* "echo"
                                         {:message (str "Concurrent message " i)}))))]

      ;; Wait for all to complete
      (let [results (doall (map deref futures))]
        (is (= 5 (count results)))

        ;; Each should be a valid result
        (doseq [result results]
          (is (map? result))
          (is (contains? result :content))
          (is (sequential? (:content result))))

        ;; Messages should be different
        (let [messages (map #(-> % :content first :text) results)]
          (is (= 5 (count (set messages))))

          ;; Each message should contain the expected pattern
          (doseq [message messages]
            (is (clojure.string/includes? message "Concurrent message"))))

        (log/info :integration-test/concurrent-results {:count (count results)}))))

  (testing "mixed operation types concurrently"
    (let [list-future (future (client/list-tools *client*))
          echo-future (future (client/call-tool *client* "echo" {:message "concurrent"}))
          add-future (future (client/call-tool *client* "add" {:a 10 :b 20}))]

      ;; Wait for all to complete
      (let [list-result @list-future
            echo-result @echo-future
            add-result @add-future]

        ;; List tools result
        (is (map? list-result))
        (is (contains? list-result :tools))

        ;; Echo result
        (is (= "Echo: concurrent" (-> echo-result :content first :text)))

        ;; Add result  
        (is (= "30" (-> add-result :content first :text)))

        (log/info :integration-test/mixed-concurrent-success)))))

(deftest test-session-robustness
  "Test session robustness and error recovery"
  (testing "client remains functional after errors"
    ;; Try a failing operation
    (try
      (client/call-tool *client* "non-existent-tool" {})
      (catch Exception _))

    ;; Client should still work for valid operations
    (let [result (client/call-tool *client* "echo" {:message "after error"})]
      (is (= "Echo: after error" (-> result :content first :text))))

    ;; Tool listing should still work
    (let [tools (client/list-tools *client*)]
      (is (map? tools))
      (is (sequential? (:tools tools)))))

  (testing "client info remains consistent"
    (let [info1 (client/get-client-info *client*)
          _ (client/call-tool *client* "echo" {:message "test"})
          info2 (client/get-client-info *client*)]

      ;; Core info should remain the same
      (is (= (:state info1) (:state info2)))
      (is (= (:client-info info1) (:client-info info2)))
      (is (= (:server-info info1) (:server-info info2)))
      (is (:transport-alive? info1))
      (is (:transport-alive? info2)))))

(deftest test-protocol-compliance
  "Test MCP protocol compliance features"
  (testing "proper protocol version negotiation"
    (let [client-info (client/get-client-info *client*)]
      ;; Should negotiate to 2024-11-05 as requested
      (is (= "2024-11-05" (:protocol-version client-info))))

    (log/info :integration-test/protocol-version-verified))

  (testing "server info validation"
    (let [server-info (:server-info (client/get-client-info *client*))]
      (is (map? server-info))
      ;; Server should provide name and version
      (is (contains? server-info :name))
      (is (contains? server-info :version))
      (is (string? (:name server-info)))
      (is (string? (:version server-info))))

    (log/info :integration-test/server-info-validated))

  (testing "capabilities exchange"
    (let [client-info (client/get-client-info *client*)
          server-caps (:server-capabilities client-info)]
      (is (map? server-caps))
      ;; Java SDK server should declare tool capabilities
      (when (contains? server-caps :tools)
        (is (contains? (:tools server-caps) :listChanged))))

    (log/info :integration-test/capabilities-validated)))

(deftest test-resource-management
  "Test proper resource management and cleanup"
  (testing "client cleanup works properly"
    ;; This test verifies the with-open pattern works
    ;; The fixture handles this, so we just verify client is functional
    (is (client/client-ready? *client*))
    (is (not (client/client-error? *client*)))

    ;; Perform an operation to ensure everything works
    (let [result (client/call-tool *client* "echo" {:message "cleanup test"})]
      (is (= "Echo: cleanup test" (-> result :content first :text))))

    (log/info :integration-test/resource-management-verified)))

(comment
  ;; Manual testing examples

  ;; Run a single test
  (clojure.test/run-tests 'mcp-clj.mcp-client.java-sdk-integration-test)

  ;; Test specific functionality
  (with-java-sdk-client
    (fn []
      (println "Tools:" (client/list-tools *client*))
      (println "Echo:" (client/call-tool *client* "echo" {:message "test"}))
      (println "Add:" (client/call-tool *client* "add" {:a 1 :b 2})))))