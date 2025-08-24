(ns mcp-clj.mcp-client.core-test
  "Tests for MCP client core functionality"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.core :as core]
   [mcp-clj.mcp-client.session :as session]))

(deftest create-client-test
  (testing "Create client with stdio transport"
    (let [config {:transport {:type :stdio
                              :command ["echo", "test"]}}
          client (core/create-client config)]

      (is (some? client))
      (is (some? (:transport client)))
      (is (some? (:session client)))

      ;; Check initial session state
      (let [session @(:session client)]
        (is (= :disconnected (:state session)))
        (is (= "2025-06-18" (:protocol-version session)))
        (is (= {} (:capabilities session))))

      ;; Cleanup
      (core/close! client)))

  (testing "Create client with vector transport (backward compatibility)"
    (let [config {:transport ["echo", "test"]}
          client (core/create-client config)]

      (is (some? client))
      (is (some? (:transport client)))
      (is (some? (:session client)))

      ;; Cleanup
      (core/close! client)))

  (testing "Invalid transport type throws exception"
    (is (thrown? Exception
                 (core/create-client {:transport {:type :invalid}})))))

(deftest client-state-test
  (testing "Client state predicates"
    (let [client (core/create-client {:transport ["echo", "test"]})]

      ;; Initially not ready and not in error
      (is (not (core/client-ready? client)))
      (is (not (core/client-error? client)))

      ;; Transition to error state
      (swap! (:session client)
             #(session/transition-state! % :error
                                         :error-info {:type :test-error}))

      (is (not (core/client-ready? client)))
      (is (core/client-error? client))

      ;; Cleanup
      (core/close! client))))

(deftest get-client-info-test
  (testing "Get client information"
    (let [client (core/create-client
                  {:transport {:type :stdio :command ["echo", "test"]}
                   :client-info {:name "test-client" :version "1.0.0"}
                   :capabilities {:test true}})
          info (core/get-client-info client)]

      (is (= :disconnected (:state info)))
      (is (= "2025-06-18" (:protocol-version info)))
      (is (= {:name "test-client" :version "1.0.0"} (:client-info info)))
      (is (= {:test true} (:client-capabilities info)))
      (is (boolean? (:transport-alive? info)))

      ;; Cleanup
      (core/close! client))))

(deftest initialize-invalid-state-test
  (testing "Initialize throws exception when not in disconnected state"
    (let [client (core/create-client {:transport ["echo", "test"]})]

      ;; Transition to initializing state
      (swap! (:session client)
             #(session/transition-state! % :initializing))

      ;; Should throw exception
      (is (thrown? Exception (core/initialize! client)))

      ;; Cleanup
      (core/close! client))))
