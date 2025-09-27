(ns mcp-clj.mcp-client.simple-integration-test
  "Simplified integration test for MCP client initialization"
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.mcp-client.core :as client]))

(deftest ^:integ client-session-state-transitions-test
  (testing "Client session transitions through states correctly"
    (let [client (client/create-client
                   ;; cat will read but not respond properly
                   {:transport    {:type    :stdio
                                   :command "cat"}
                    :client-info  {:name "state-test-client"}
                    :capabilities {}})]

      ;; Client starts initializing
      (is (contains? #{:initializing :error} (:state @(:session client))))

      ;; wait-for-ready should throw an exception since cat can't handle MCP
      ;; protocol
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Client initialization failed"
            (client/wait-for-ready client 2000)))

      ;; Should be in error state after failed initialization
      (is (= :error (:state @(:session client))))

      (client/close! client))))

(deftest ^:integ client-configuration-test
  (testing "Client accepts various transport configurations"
    ;; Test map-style transport
    (let [client1 (client/create-client
                    {:transport {:type :stdio :command "echo" :args ["test"]}
                     :client-info {:name "config-test-1"}})]
      (is (some? client1))
      (is (= "config-test-1" (get-in @(:session client1) [:client-info :name])))
      (client/close! client1))

    ;; Test custom protocol version
    (let [client3 (client/create-client
                    {:transport {:type :stdio
                                 :command "echo"
                                 :args ["test"]}
                     :protocol-version "2024-11-05"})]
      (is (some? client3))
      (is (= "2024-11-05" (:protocol-version @(:session client3))))
      (client/close! client3))))
