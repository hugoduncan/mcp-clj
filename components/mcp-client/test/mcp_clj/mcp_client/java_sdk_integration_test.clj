(ns mcp-clj.mcp-client.java-sdk-integration-test
  "Integration tests using Clojure MCP client against Java SDK server.

   This tests cross-implementation compatibility by using our Clojure MCP client
   to communicate with the Java SDK MCP server subprocess."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.log :as log]
   [mcp-clj.mcp-client.core :as client])
  (:import
   [java.lang
    AutoCloseable]))

(defn- create-client
  ^AutoCloseable []
  (client/create-client
   {:transport {:type :stdio
                :command "clj"
                :args ["-M:dev:test" "-m" "mcp-clj.java-sdk.sdk-server-main"]}
    :client-info {:name "java-sdk-integration-test"
                  :version "0.1.0"}
    :capabilities {}
    :protocol-version "2024-11-05"}))

;;; MCP Protocol Tests

(deftest ^:integ test-client-initialization
  (testing "MCP client initialization with Java SDK server"
    (with-open [client (create-client)]
      ;; Wait for client to be ready
      (client/wait-for-ready client 10000) ; 10 second timeout

      (testing "client should be ready after initialization"
        (is (client/client-ready? client))
        (is (not (client/client-error? client)))

        (let [client-info (client/get-client-info client)]
          (is (= :ready (:state client-info)))
          (is (= "2024-11-05" (:protocol-version client-info)))
          (is (= {:name "java-sdk-integration-test" :version "0.1.0"}
                 (:client-info client-info)))
          (is (some? (:server-info client-info)))
          (is (map? (:server-capabilities client-info)))
          (is (:transport-alive? client-info)))))))

(deftest ^:integ test-tool-discovery
  (testing "tool discovery with Java SDK server"
    (with-open [client (create-client)]
      ;; Wait for client to be ready
      (client/wait-for-ready client 10000) ; 10 second timeout

      (testing "list available tools"
        (let [tools-response @(client/list-tools client)]
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

          (log/info :integration-test/tools-discovered
            {:tools (:tools tools-response)})

          ;; Verify client knows tools are available
          (is (client/available-tools? client)))))))

(deftest ^:integ test-tool-calls
  (testing "actual tool calls with Java SDK server"
    (with-open [client (create-client)]
      ;; Wait for client to be ready
      (client/wait-for-ready client 10000) ; 10 second timeout

      (testing "echo tool call"
        (let [future (client/call-tool
                      client
                      "echo"
                      {:message "Hello from Clojure MCP client!"})
              result (:content @future)]
          (is (sequential? result))

          (let [first-content (first result)]
            (is (= "text" (:type first-content)))
            (is (= "Echo: Hello from Clojure MCP client!"
                   (:text first-content))))

          (log/info :integration-test/echo-result {:result result})))

      (testing "add tool call"
        (let [future (client/call-tool client "add" {:a 42 :b 13})
              result (:content @future)]
          (is (sequential? result))

          (let [first-content (first result)]
            (is (= "text" (:type first-content)))
            (is (= "55" (:text first-content))))

          (log/info :integration-test/add-result {:result result})))

      (testing "get-time tool call"
        (let [future (client/call-tool client "get-time" {})
              result (:content @future)] ; Deref the CompletableFuture and get :content
          (is (sequential? result))

          (let [first-content (first result)]
            (is (= "text" (:type first-content)))
            (is (string? (:text first-content)))
            (is (> (count (:text first-content)) 0)))

          (log/info :integration-test/get-time-result {:result result}))))))

(deftest ^:integ test-error-handling
  (testing "error handling scenarios"
    (with-open [client (create-client)]
      ;; Wait for client to be ready
      (client/wait-for-ready client 10000) ; 10 second timeout

      (testing "non-existent tool call"
        (try
          @(client/call-tool client "non-existent-tool" {:param "value"}) ; Deref the CompletableFuture
          (is false "Should have thrown exception for non-existent tool")
          (catch Exception e
            (is (instance? Exception e))
            (log/info :integration-test/non-existent-tool-error {:error (.getMessage e)}))))

      (testing "invalid tool arguments"
        (try
          ;; Try to call add with invalid arguments (missing required params)
          @(client/call-tool client "add" {:invalid "args"}) ; Deref the CompletableFuture
          ;; If it doesn't throw, check for error indication in result
          :no-exception
          (catch Exception e
            (is (instance? Exception e))
            (log/info :integration-test/invalid-args-error {:error (.getMessage e)})))))))

(deftest ^:integ test-concurrent-operations
  (testing "concurrent tool calls"
    (with-open [client (create-client)]
      ;; Wait for client to be ready
      (client/wait-for-ready client 10000) ; 10 second timeout

      (testing "multiple concurrent tool calls"
        (let [futures (doall
                       (for [i (range 5)]
                         (future
                           (:content @(client/call-tool ; Deref the CompletableFuture and get :content
                                       client "echo"
                                       {:message (str "Concurrent message " i)})))))]

          ;; Wait for all to complete
          (let [results (mapv deref futures)]
            (is (= 5 (count results)))

            ;; Each should be a valid result
            (doseq [result results]
              (is (sequential? result)))

            ;; Messages should be different
            (let [messages (map #(-> % first :text) results)]
              (is (= 5 (count (set messages))))

              ;; Each message should contain the expected pattern
              (doseq [message messages]
                (is (str/includes? message "Concurrent message"))))

            (log/info :integration-test/concurrent-results {:count (count results)}))))

      (testing "mixed operation types concurrently"
        (let [list-future (future @(client/list-tools client)) ; Deref the CompletableFuture
              echo-future (future (:content @(client/call-tool client "echo" {:message "concurrent"}))) ; Deref and get :content
              add-future (future (:content @(client/call-tool client "add" {:a 10 :b 20})))] ; Deref and get :content

          ;; Wait for all to complete
          (let [list-result @list-future
                echo-result @echo-future
                add-result @add-future]

            ;; List tools result
            (is (map? list-result))
            (is (contains? list-result :tools))

            ;; Echo result
            (is (= "Echo: concurrent" (-> echo-result first :text)))

            ;; Add result
            (is (= "30" (-> add-result first :text)))

            (log/info :integration-test/mixed-concurrent-success)))))))

(deftest ^:integ test-session-robustness
  (testing "session robustness and error recovery"
    (with-open [client (create-client)]
      ;; Wait for client to be ready
      (client/wait-for-ready client 10000) ; 10 second timeout

      (testing "client remains functional after errors"
        ;; Try a failing operation
        (try
          (client/call-tool client "non-existent-tool" {})
          (catch Exception _))

        ;; Client should still work for valid operations
        (let [result @(client/call-tool client "echo" {:message "after error"})]
          (is (= "Echo: after error" (-> result :content first :text))))

        ;; Tool listing should still work
        (let [tools @(client/list-tools client)]
          (is (map? tools))
          (is (sequential? (:tools tools)))))

      (testing "client info remains consistent"
        (let [info1 (client/get-client-info client)
              _     @(client/call-tool client "echo" {:message "test"})
              info2 (client/get-client-info client)]

          ;; Core info should remain the same
          (is (= (:state info1) (:state info2)))
          (is (= (:client-info info1) (:client-info info2)))
          (is (= (:server-info info1) (:server-info info2)))
          (is (:transport-alive? info1))
          (is (:transport-alive? info2)))))))

(deftest ^:integ test-protocol-compliance
  (testing "MCP protocol compliance features"
    (with-open [client (create-client)]
      ;; Wait for client to be ready
      (client/wait-for-ready client 10000) ; 10 second timeout

      (testing "proper protocol version negotiation"
        (let [client-info (client/get-client-info client)]
          ;; Should negotiate to 2024-11-05 as requested
          (is (= "2024-11-05" (:protocol-version client-info))))

        (log/info :integration-test/protocol-version-verified))

      (testing "server info validation"
        (let [server-info (:server-info (client/get-client-info client))]
          (is (map? server-info))
          ;; Server should provide name and version
          (is (contains? server-info :name))
          (is (contains? server-info :version))
          (is (string? (:name server-info)))
          (is (string? (:version server-info))))

        (log/info :integration-test/server-info-validated))

      (testing "capabilities exchange"
        (let [client-info (client/get-client-info client)
              server-caps (:server-capabilities client-info)]
          (is (map? server-caps))
          ;; Java SDK server should declare tool capabilities
          (when (contains? server-caps :tools)
            (is (contains? (:tools server-caps) :listChanged))))

        (log/info :integration-test/capabilities-validated)))))

(deftest ^:integ test-resource-management
  (testing "proper resource management and cleanup"
    (with-open [client (create-client)]
      ;; Wait for client to be ready
      (client/wait-for-ready client 10000) ; 10 second timeout

      (testing "client cleanup works properly"
        ;; This test verifies the with-open pattern works
        ;; Each test creates its own client, so we just verify client is functional
        (is (client/client-ready? client))
        (is (not (client/client-error? client)))

        ;; Perform an operation to ensure everything works
        (let [result @(client/call-tool
                       client
                       "echo"
                       {:message "cleanup test"})]
          (is (= "Echo: cleanup test" (-> result :content first :text))))))))

(deftest ^:integ test-multiple-clients
  (testing "multiple client instances work independently"
    (testing "two clients can work simultaneously"
      (with-open [client1 (create-client)
                  client2 (create-client)]

        ;; Initialize both clients
        (client/wait-for-ready client1 10000)
        (client/wait-for-ready client2 10000)

        ;; Both should be ready
        (is (client/client-ready? client1))
        (is (client/client-ready? client2))

        ;; Both should be able to make calls independently
        (let [result1 (client/call-tool
                       client1
                       "echo"
                       {:message "from client 1"})
              result2 (client/call-tool
                       client2
                       "echo"
                       {:message "from client 2"})]

          (is (= "Echo: from client 1" (-> @result1 :content first :text)))
          (is (= "Echo: from client 2" (-> @result2 :content first :text))))))))
