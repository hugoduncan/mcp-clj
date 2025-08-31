(ns mcp-clj.java-sdk.basic-integration-test
  "Basic integration test using Java SDK client with Clojure MCP server"
  (:require
   [clojure.test :refer [deftest testing is]]
   [mcp-clj.java-sdk.interop :as java-sdk]
   [mcp-clj.log :as log])
  (:import
   [java.util.concurrent TimeUnit]))

(deftest test-java-sdk-client-creation
  "Test that we can create a Java SDK client successfully"
  (testing "Java SDK client creation with stdio transport"
    (let [transport (java-sdk/create-stdio-transport "clj -M:stdio-server")
          client-map (java-sdk/create-java-client {:transport transport :async? false})]
      (is (some? (:client client-map)))
      (is (= false (:async? client-map)))
      (is (= io.modelcontextprotocol.client.McpSyncClient
             (type (:client client-map))))))

  (testing "Java SDK async client creation"
    (let [transport (java-sdk/create-stdio-transport "clj -M:stdio-server")
          client-map (java-sdk/create-java-client {:transport transport :async? true})]
      (is (some? (:client client-map)))
      (is (= true (:async? client-map))))))

(deftest ^:integration test-basic-client-server-interaction
  "Test basic interaction between Java SDK client and Clojure MCP server"
  (testing "Client initialization"
    ;; This test requires the stdio server to be available
    ;; For now, we'll just test that we can create the client without errors
    (let [transport (java-sdk/create-stdio-transport "echo 'mock server'")
          client-map (java-sdk/create-java-client {:transport transport :async? false})]
      (is (some? (:client client-map)))
      ;; Note: We don't actually initialize here since we don't have a real server
      ;; This would be: (java-sdk/initialize-client client-map)
      (java-sdk/close-client client-map))))

(comment
  ;; Run individual tests
  (test-java-sdk-client-creation)
  (test-basic-client-server-interaction)

  ;; Manual testing with real server
  ;; This would require starting the actual stdio server
  (let [transport (java-sdk/create-stdio-transport "clj -M:stdio-server")
        client-map (java-sdk/create-java-client {:transport transport :async? false})]
    (try
      (java-sdk/initialize-client client-map)
      (java-sdk/list-tools client-map)
      (finally
        (java-sdk/close-client client-map)))))