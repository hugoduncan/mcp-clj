(ns mcp-clj.mcp-client.smoke-test
  "Smoke tests to verify MCP client basic functionality without external processes"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-client.session :as session]))

(deftest smoke-client-creation-test
  (testing "Can create client without starting processes"
    (let [client (client/create-client
                  {:transport {:type :stdio :command ["true"]}
                   :client-info {:name "smoke-test"}
                   :capabilities {}})]

      (is (some? client))
      (is (some? (:transport client)))
      (is (some? (:session client)))

      ;; Check session details
      (let [session @(:session client)]
        (is (= :disconnected (:state session)))
        (is (= "2025-06-18" (:protocol-version session)))
        (is (= {:name "smoke-test"} (:client-info session)))
        (is (= {} (:capabilities session))))

      ;; Clean up (this should not hang)
      (client/close! client))))

(deftest smoke-session-info-test
  (testing "Can get comprehensive session info"
    (let [client (client/create-client
                  {:transport ["echo"]
                   :client-info {:name "info-test" :version "1.0"}
                   :capabilities {:test true}
                   :protocol-version "2024-11-05"})
          info (client/get-client-info client)]

      (is (= :disconnected (:state info)))
      (is (= "2024-11-05" (:protocol-version info)))
      (is (= {:name "info-test" :version "1.0"} (:client-info info)))
      (is (= {:test true} (:client-capabilities info)))
      (is (nil? (:server-info info)))
      (is (nil? (:server-capabilities info)))
      (is (boolean? (:transport-alive? info)))

      (client/close! client))))

(deftest smoke-state-predicates-test
  (testing "Client state predicates work correctly"
    (let [client (client/create-client {:transport ["true"]})]

      ;; Initial state
      (is (not (client/client-ready? client)))
      (is (not (client/client-error? client)))

      ;; Manually transition to ready for testing
      (swap! (:session client) #(session/transition-state! % :ready))
      (is (client/client-ready? client))
      (is (not (client/client-error? client)))

      ;; Transition to error
      (swap! (:session client) #(session/transition-state! % :error))
      (is (not (client/client-ready? client)))
      (is (client/client-error? client))

      (client/close! client))))

(deftest smoke-transport-configuration-test
  (testing "Different transport configurations work"
    ;; Map transport
    (let [client1 (client/create-client
                   {:transport {:type :stdio :command ["echo", "test"]}})]
      (is (some? client1))
      (client/close! client1))

    ;; Vector transport
    (let [client2 (client/create-client {:transport ["echo", "test"]})]
      (is (some? client2))
      (client/close! client2))

    ;; Invalid transport should throw
    (is (thrown? Exception
                 (client/create-client {:transport {:type :invalid}})))))
