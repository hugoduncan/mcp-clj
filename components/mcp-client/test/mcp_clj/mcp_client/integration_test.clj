(ns mcp-clj.mcp-client.integration-test
  "Integration tests for MCP client connecting to real MCP server"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.core :as client]))

;;; Integration Tests

(deftest ^:integration client-server-initialization-test
  (testing "MCP client can initialize with real MCP server"
    (with-open [client (client/create-client
                        {:server       {:type    :stdio
                                        :command "clojure"
                                        :args    ["-M:stdio-server"]}
                         :client-info  {:name    "integration-test-client"
                                        :title   "Integration Test Client"
                                        :version "1.0.0"}
                         :capabilities {}})]

      ;; Client should start in disconnected state
      (is (= :disconnected (:state @(:session client))))
      (is (not (client/client-ready? client)))

      ;; Initialize the client
      (let [init-future (client/initialize! client)]

        ;; Should transition to initializing
        (is (= :initializing (:state @(:session client))))

        (try
          ;; Wait for initialization to complete
          ;; TODO add timeout
          @init-future
          ;; (client/wait-for-ready client 10000) ; 10 second timeout

          ;; Client should now be ready
          (is (client/client-ready? client))
          (is (not (client/client-error? client)))

          ;; Check session details
          (let [info (client/get-client-info client)]
            (is (= :ready (:state info)))
            (is (= "2025-06-18" (:protocol-version info)))
            (is (= {:name    "integration-test-client"
                    :title   "Integration Test Client"
                    :version "1.0.0"}
                   (:client-info info)))
            (is (= {} (:client-capabilities info)))
            (is (some? (:server-info info))) ; Server should provide info
            (is (map? (:server-capabilities info))) ; Server should declare capabilities
            (is (:transport-alive? info)))

          (catch Exception e
            (let [session-info (client/get-client-info client)]
              (is (not (str "Client initialization failed: "
                            (.getMessage e)
                            " Session: " session-info))))))))))

(deftest ^:integration client-server-error-handling-test
  (testing "MCP client handles server connection errors gracefully"
    ;; Try to connect to non-existent server
    (with-open [client (client/create-client
                        {:server       {:type    :stdio
                                        :command "cat"}
                         :client-info  {:name "error-test-client"}
                         :capabilities {}})]

      ;; Initialize should fail
      @(client/initialize! client)

      ;; Should be in error state
      (let [info (client/get-client-info client)]
        (is (or (= :error (:state info))
                (= :initializing (:state info))))) ; May still be trying

      (client/close! client))))

#_(deftest ^:integration multiple-clients-test
    (testing "Multiple clients can connect to server simultaneously"
      (when *server*
        (let [client1 (client/create-client
                       {:transport   {:type    :stdio
                                      :command ["clojure" "-M:dev" "-m" "mcp-clj.stdio-server.main"]}
                        :client-info {:name "client-1"}})
              client2 (client/create-client
                       {:transport   {:type    :stdio
                                      :command ["clojure" "-M:dev" "-m" "mcp-clj.stdio-server.main"]}
                        :client-info {:name "client-2"}})]

          (try
            ;; Both clients should initialize successfully
            (client/initialize! client1)
            (client/initialize! client2)

            ;; Wait for both to be ready
            (client/wait-for-ready client1 10000)
            (client/wait-for-ready client2 10000)

            ;; Both should be ready
            (is (client/client-ready? client1))
            (is (client/client-ready? client2))

            ;; Verify they have different client info
            (let [info1 (client/get-client-info client1)
                  info2 (client/get-client-info client2)]
              (is (= "client-1" (get-in info1 [:client-info :name])))
              (is (= "client-2" (get-in info2 [:client-info :name]))))

            (finally
              (client/close! client1)
              (client/close! client2)))))))
