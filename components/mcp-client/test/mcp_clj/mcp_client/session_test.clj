(ns mcp-clj.mcp-client.session-test
  "Tests for MCP client session management"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.session :as session]))

(deftest create-session-test
  (testing "Create session with defaults"
    (let [session (session/create-session {})]
      (is (= :disconnected (:state session)))
      (is (= "2025-06-18" (:protocol-version session)))
      (is (= {} (:capabilities session)))
      (is (some? (:client-info session)))
      (is (nil? (:server-info session)))
      (is (nil? (:server-capabilities session)))
      (is (nil? (:error-info session)))))

  (testing "Create session with custom values"
    (let [client-info {:name "test" :version "1.0.0"}
          capabilities {:test true}
          session (session/create-session
                   {:client-info client-info
                    :capabilities capabilities
                    :protocol-version "2024-11-05"})]
      (is (= :disconnected (:state session)))
      (is (= "2024-11-05" (:protocol-version session)))
      (is (= capabilities (:capabilities session)))
      (is (= client-info (:client-info session))))))

(deftest session-state-transitions-test
  (testing "Valid state transitions"
    (let [session (session/create-session {})

          ;; disconnected -> initializing
          session2 (session/transition-state! session :initializing)

          ;; initializing -> ready
          session3 (session/transition-state!
                    session2 :ready
                    :server-info {:name "test-server"}
                    :server-capabilities {:tools true})

          ;; ready -> error
          session4 (session/transition-state!
                    session3 :error
                    :error-info {:type :connection-lost})

          ;; error -> disconnected (clears server info)
          session5 (session/transition-state! session4 :disconnected)]

      (is (= :initializing (:state session2)))
      (is (= :ready (:state session3)))
      (is (= {:name "test-server"} (:server-info session3)))
      (is (= {:tools true} (:server-capabilities session3)))
      (is (= :error (:state session4)))
      (is (= {:type :connection-lost} (:error-info session4)))
      (is (= :disconnected (:state session5)))
      (is (nil? (:server-info session5)))
      (is (nil? (:server-capabilities session5)))
      (is (nil? (:error-info session5)))))

  (testing "Invalid state transitions throw exceptions"
    (let [session (session/create-session {})]

      ;; disconnected -> ready is invalid
      (is (thrown? Exception
                   (session/transition-state! session :ready)))

      ;; Create ready session
      (let [ready-session (-> session
                              (session/transition-state! :initializing)
                              (session/transition-state! :ready))]
        ;; ready -> initializing is invalid
        (is (thrown? Exception
                     (session/transition-state! ready-session :initializing)))))))

(deftest session-predicates-test
  (testing "Session state predicates"
    (let [session (session/create-session {})]

      ;; Initially not ready, not error
      (is (not (session/session-ready? session)))
      (is (not (session/session-error? session)))

      ;; Transition to ready
      (let [ready-session (-> session
                              (session/transition-state! :initializing)
                              (session/transition-state! :ready))]
        (is (session/session-ready? ready-session))
        (is (not (session/session-error? ready-session))))

      ;; Transition to error
      (let [error-session (session/transition-state! session :error)]
        (is (not (session/session-ready? error-session)))
        (is (session/session-error? error-session))))))

(deftest get-session-info-test
  (testing "Get comprehensive session information"
    (let [session (session/create-session
                   {:client-info {:name "test-client"}
                    :capabilities {:test true}
                    :protocol-version "2024-11-05"})
          ready-session (-> session
                            (session/transition-state! :initializing)
                            (session/transition-state!
                             :ready
                             :server-info {:name "test-server"}
                             :server-capabilities {:tools true}))
          info (session/get-session-info ready-session)]

      (is (= :ready (:state info)))
      (is (= "2024-11-05" (:protocol-version info)))
      (is (= {:name "test-client"} (:client-info info)))
      (is (= {:test true} (:client-capabilities info)))
      (is (= {:name "test-server"} (:server-info info)))
      (is (= {:tools true} (:server-capabilities info)))
      (is (nil? (:error-info info))))))

(deftest capability-checking-test
  ;; Test server capability checking
  (testing "server-supports? with various capability paths"
    (let [session (-> (session/create-session {})
                      (session/transition-state! :initializing)
                      (session/transition-state!
                       :ready
                       :server-capabilities {:tools {:listChanged true}
                                             :resources {:subscribe false}
                                             :prompts {}}))]
      (is (session/server-supports? session [:tools]))
      (is (session/server-supports? session [:tools :listChanged]))
      (is (session/server-supports? session [:prompts]))
      (is (not (session/server-supports? session [:resources :subscribe])))
      (is (not (session/server-supports? session [:sampling])))
      (is (not (session/server-supports? session [:nonexistent])))))

  (testing "server-supports-tools?"
    (let [session-with-tools (-> (session/create-session {})
                                 (session/transition-state! :initializing)
                                 (session/transition-state!
                                  :ready
                                  :server-capabilities {:tools {}}))
          session-without-tools (-> (session/create-session {})
                                    (session/transition-state! :initializing)
                                    (session/transition-state!
                                     :ready
                                     :server-capabilities {}))]
      (is (session/server-supports-tools? session-with-tools))
      (is (not (session/server-supports-tools? session-without-tools)))))

  (testing "server-supports-prompts?"
    (let [session-with-prompts (-> (session/create-session {})
                                   (session/transition-state! :initializing)
                                   (session/transition-state!
                                    :ready
                                    :server-capabilities {:prompts {}}))
          session-without-prompts (-> (session/create-session {})
                                      (session/transition-state! :initializing)
                                      (session/transition-state!
                                       :ready
                                       :server-capabilities {}))]
      (is (session/server-supports-prompts? session-with-prompts))
      (is (not (session/server-supports-prompts? session-without-prompts)))))

  (testing "server-supports-resources?"
    (let [session-with-resources (-> (session/create-session {})
                                     (session/transition-state! :initializing)
                                     (session/transition-state!
                                      :ready
                                      :server-capabilities {:resources {}}))
          session-without-resources (-> (session/create-session {})
                                        (session/transition-state! :initializing)
                                        (session/transition-state!
                                         :ready
                                         :server-capabilities {}))]
      (is (session/server-supports-resources? session-with-resources))
      (is (not (session/server-supports-resources? session-without-resources)))))

  (testing "server-supports-sampling?"
    (let [session-with-sampling (-> (session/create-session {})
                                    (session/transition-state! :initializing)
                                    (session/transition-state!
                                     :ready
                                     :server-capabilities {:sampling {}}))
          session-without-sampling (-> (session/create-session {})
                                       (session/transition-state! :initializing)
                                       (session/transition-state!
                                        :ready
                                        :server-capabilities {}))]
      (is (session/server-supports-sampling? session-with-sampling))
      (is (not (session/server-supports-sampling? session-without-sampling)))))

  ;; Test client capability checking
  (testing "client-supports? with various capability paths"
    (let [session (session/create-session
                   {:capabilities {:sampling {}
                                   :notifications {:enabled true}}})]
      (is (session/client-supports? session [:sampling]))
      (is (session/client-supports? session [:notifications :enabled]))
      (is (not (session/client-supports? session [:tools])))
      (is (not (session/client-supports? session [:nonexistent])))))

  (testing "client-supports-sampling?"
    (let [session-with-sampling (session/create-session
                                 {:capabilities {:sampling {}}})
          session-without-sampling (session/create-session {})]
      (is (session/client-supports-sampling? session-with-sampling))
      (is (not (session/client-supports-sampling? session-without-sampling)))))

  (testing "client-supports-notifications?"
    (let [session-with-notifications (session/create-session
                                      {:capabilities {:notifications {}}})
          session-without-notifications (session/create-session {})]
      (is (session/client-supports-notifications? session-with-notifications))
      (is (not (session/client-supports-notifications? session-without-notifications))))))
