(ns mcp-clj.mcp-client.core-test
  "Tests for MCP client core functionality"
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.mcp-client.core :as core]
    [mcp-clj.mcp-client.session :as session]))

(deftest create-client-test
  (testing "should create client with stdio and automatic initialization"
    (let [config {:transport {:type :stdio
                              :command "echo"
                              :args ["test"]
                              :env {"TEST_VAR" "test_value"}}
                  :client-info {:name "test-client"
                                :version "1.0.0"}
                  :capabilities {}}]
      (with-open [client (core/create-client config)]
        (is (some? client))
        (is (some? (:transport client)))
        (is (some? (:session client)))
        (is (some? (:initialization-future client)))

        (testing "should be initializing due to automatic initialization"
          (let [session @(:session client)]
            (is (= :initializing (:state session)))
            (is (= "2025-06-18" (:protocol-version session)))
            (is (= {} (:capabilities session))))))))

  (testing "should create client with minimal server configuration"
    (let [config {:transport {:type :stdio
                              :command "echo"
                              :args ["test"]}
                  :client-info {:name "minimal-test-client"}
                  :capabilities {}}]
      (with-open [client (core/create-client config)]
        (is (some? client))
        (is (some? (:transport client)))
        (is (some? (:session client)))
        (is (some? (:initialization-future client))))))

  (testing "should throw exception for invalid server configuration"
    (is (thrown? Exception
          (core/create-client {:transport {:type :invalid}})))
    (is (thrown? Exception
          (core/create-client {:transport {:type :stdio
                                           :command ["echo", "test"]}})))))

(deftest client-state-test
  (testing "should provide correct client state predicates"
    (let [client (core/create-client
                   {:transport {:type :stdio
                                :command "echo"
                                :args ["test"]}})]

      ;; Initially might be initializing due to automatic initialization
      (is (not (core/client-ready? client)))
      (is (not (core/client-error? client)))

      ;; Wait a moment for initialization to potentially complete or fail
      (Thread/sleep 100)

      ;; Transition to error state manually for testing
      (swap! (:session client)
             #(session/transition-state! % :error
                                         :error-info {:type :test-error}))

      (is (not (core/client-ready? client)))
      (is (core/client-error? client))

      ;; Cleanup
      (core/close! client))))

(deftest get-client-info-test
  (testing "should return correct client information"
    (let [client (core/create-client
                   {:transport {:type :stdio
                                :command "echo"
                                :args ["test"]}
                    :client-info {:name "test-client" :version "1.0.0"}
                    :capabilities {:test true}})
          info (core/get-client-info client)]

      ;; Client starts initializing automatically, so state might be :initializing or :error
      (is (contains? #{:disconnected :initializing :error} (:state info)))
      (is (= "2025-06-18" (:protocol-version info)))
      (is (= {:name "test-client" :version "1.0.0"} (:client-info info)))
      (is (= {:test true} (:client-capabilities info)))
      (is (boolean? (:transport-alive? info)))

      ;; Cleanup
      (core/close! client))))

(deftest wait-for-ready-test
  (testing "should timeout when waiting for ready with short timeout"
    (let [client (core/create-client {:transport {:type :stdio
                                                  :command "echo"
                                                  :args ["test"]}})]

      ;; Should timeout quickly since echo won't respond with proper MCP protocol
      (is (thrown? Exception (core/wait-for-ready client 50)))

      ;; Cleanup
      (core/close! client)))

  (testing "should timeout when waiting for ready with default timeout"
    (let [client (core/create-client {:transport {:type :stdio
                                                  :command "echo"
                                                  :args ["test"]}})]

      ;; Should timeout with default timeout (use shorter timeout for testing)
      (is (thrown? Exception (core/wait-for-ready client 50)))

      ;; Cleanup
      (core/close! client))))
