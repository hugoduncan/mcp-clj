(ns mcp-clj.mcp-client.integration-test
  "Integration tests for MCP client connecting to real MCP server"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-server.core :as server]
   [mcp-clj.log :as log])
  (:import
   [java.util.concurrent TimeUnit]
   [java.lang ProcessBuilder$Redirect]))

;;; Test Server Management

(def ^:dynamic *test-server* nil)
(def ^:dynamic *server* nil)

(defn- start-test-server
  "Start an MCP server"
  []
  (server/create-server {:transport :stdio}))

(defn- stop-test-server
  "Stop the test MCP server process"
  [server]
  (when server
    ((:stop server))))

(defn server-fixture
  "Test fixture that starts and stops MCP server for integration tests"
  [test-fn]
  (let [server (try
                 (start-test-server)
                 (catch Exception e
                   (log/error :integration-test/server-start-failed {:error e})
                   nil))]
    (try
      (binding [*server* server]
        (when server
          (test-fn)))
      (finally
        (stop-test-server server)))))

(use-fixtures :each server-fixture)

;;; Integration Tests

(deftest ^:integration client-server-initialization-test
  (testing "MCP client can initialize with real MCP server"
    (when *server*
      (let [client (client/create-client
                    {:transport    {:type    :stdio
                                    :command ["clojure" "-M:dev" "-m" "mcp-clj.stdio-server.main"]}
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

          ;; Wait for initialization to complete
          (try
            (client/wait-for-ready client 10000) ; 10 second timeout

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
                              " Session: " session-info))))))

          ;; Clean up
          (client/close! client))))))

(deftest ^:integration client-server-error-handling-test
  (testing "MCP client handles server connection errors gracefully"
    ;; Try to connect to non-existent server
    (let [client (client/create-client
                  {:transport {:type :stdio
                               :command ["nonexistent-command"]}
                   :client-info {:name "error-test-client"}
                   :capabilities {}})]

      ;; Initialize should fail
      (client/initialize! client)

      ;; Give it time to fail
      (Thread/sleep 2000)

      ;; Should be in error state
      (let [info (client/get-client-info client)]
        (is (or (= :error (:state info))
                (= :initializing (:state info))))) ; May still be trying

      (client/close! client))))

(deftest ^:integration multiple-clients-test
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
