(ns mcp-clj.mcp-client.smoke-test
  "Smoke tests to verify MCP client basic functionality without external processes"
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.mcp-client.core :as client]
    [mcp-clj.mcp-client.session :as session]))

(deftest smoke-client-creation-test
  (testing "Can create client without starting processes"
    (with-open [client (client/create-client
                         {:transport {:type :stdio :command "true"}
                          :client-info {:name "smoke-test"}
                          :capabilities {}})]

      (is (some? client))
      (is (some? (:transport client)))
      (is (some? (:session client)))

      ;; Check session details
      (let [session @(:session client)]
        (is (= :initializing (:state session)))
        (is (= "2025-06-18" (:protocol-version session)))
        (is (= {:name "smoke-test"} (:client-info session)))
        (is (= {} (:capabilities session)))))))

(deftest smoke-session-info-test
  (testing "Can get comprehensive session info"
    (with-open [client (client/create-client
                         {:transport {:type :stdio :command "true"}
                          :client-info {:name "info-test" :version "1.0"}
                          :capabilities {:test true}
                          :protocol-version "2024-11-05"})]
      (let [info (client/get-client-info client)]

        (is (= :initializing (:state info)))
        (is (= "2024-11-05" (:protocol-version info)))
        (is (= {:name "info-test" :version "1.0"} (:client-info info)))
        (is (= {:test true} (:client-capabilities info)))
        (is (nil? (:server-info info)))
        (is (nil? (:server-capabilities info)))
        (is (boolean? (:transport-alive? info)))))))

(deftest smoke-state-predicates-test
  (testing "Client state predicates work correctly"
    (with-open [client (client/create-client
                         {:transport {:type :stdio :command "true"}})]

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
      (is (client/client-error? client)))))

(deftest smoke-transport-configuration-test
  (testing "Different transport configurations work"
    ;; Map transport
    (with-open [client1 (client/create-client
                          {:transport {:type :stdio :command "echo" :args ["test"]}})]
      (is (some? client1)))

    ;; Vector transport
    (with-open [client2 (client/create-client
                          {:transport {:type :stdio :command "echo" :args ["test"]}})]
      (is (some? client2)))

    ;; Invalid transport should throw
    (is (thrown? Exception
          (client/create-client {:transport {:type :invalid}})))))
