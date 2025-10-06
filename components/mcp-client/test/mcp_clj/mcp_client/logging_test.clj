(ns mcp-clj.mcp-client.logging-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.mcp-client.logging :as logging]
    [mcp-clj.mcp-client.subscriptions :as subs])
  (:import
    (java.util.concurrent
      CompletableFuture)))

(deftest log-level-validation-test
  ;; Tests validation of RFC 5424 log levels
  (testing "valid-level?"
    (testing "accepts all 8 RFC 5424 levels"
      (is (logging/valid-level? :emergency))
      (is (logging/valid-level? :alert))
      (is (logging/valid-level? :critical))
      (is (logging/valid-level? :error))
      (is (logging/valid-level? :warning))
      (is (logging/valid-level? :notice))
      (is (logging/valid-level? :info))
      (is (logging/valid-level? :debug)))

    (testing "rejects invalid levels"
      (is (not (logging/valid-level? :trace)))
      (is (not (logging/valid-level? :fatal)))
      (is (not (logging/valid-level? :warn)))
      (is (not (logging/valid-level? :invalid)))
      (is (not (logging/valid-level? nil))))))

(deftest level-conversion-test
  ;; Tests bidirectional conversion between keywords and strings
  (testing "keyword->string"
    (testing "converts keywords to strings"
      (is (= "error" (logging/keyword->string :error)))
      (is (= "warning" (logging/keyword->string :warning)))
      (is (= "debug" (logging/keyword->string :debug)))
      (is (= "emergency" (logging/keyword->string :emergency)))))

  (testing "string->keyword"
    (testing "converts strings to keywords"
      (is (= :error (logging/string->keyword "error")))
      (is (= :warning (logging/string->keyword "warning")))
      (is (= :debug (logging/string->keyword "debug")))
      (is (= :emergency (logging/string->keyword "emergency"))))

    (testing "roundtrip conversion"
      (doseq [level [:emergency :alert :critical :error :warning :notice :info :debug]]
        (is (= level (-> level logging/keyword->string logging/string->keyword)))))))

(deftest log-message-subscription-test
  ;; Tests log message subscription and unsubscription
  (testing "subscribe-log-messages!"
    (testing "adds callback to registry"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :called)]
        (subs/subscribe-log-messages! registry callback-fn)
        (is (contains? @(:log-message-subscriptions registry) callback-fn))))

    (testing "supports multiple callbacks"
      (let [registry (subs/create-registry)
            callback1 (fn [_] :first)
            callback2 (fn [_] :second)]
        (subs/subscribe-log-messages! registry callback1)
        (subs/subscribe-log-messages! registry callback2)
        (is (= 2 (count @(:log-message-subscriptions registry))))
        (is (contains? @(:log-message-subscriptions registry) callback1))
        (is (contains? @(:log-message-subscriptions registry) callback2)))))

  (testing "unsubscribe-log-messages!"
    (testing "removes callback and returns true"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :called)]
        (subs/subscribe-log-messages! registry callback-fn)
        (let [removed? (subs/unsubscribe-log-messages! registry callback-fn)]
          (is (true? removed?))
          (is (not (contains? @(:log-message-subscriptions registry) callback-fn))))))

    (testing "returns false for non-existent callback"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :never-added)
            removed? (subs/unsubscribe-log-messages! registry callback-fn)]
        (is (false? removed?))))))

(deftest dispatch-log-notification-test
  ;; Tests dispatching log message notifications
  (testing "dispatch-notification! for notifications/message"
    (testing "dispatches to all log callbacks with keyword level"
      (let [registry (subs/create-registry)
            received1 (atom nil)
            received2 (atom nil)
            callback1 (fn [params] (reset! received1 params))
            callback2 (fn [params] (reset! received2 params))]
        (subs/subscribe-log-messages! registry callback1)
        (subs/subscribe-log-messages! registry callback2)
        (let [count (subs/dispatch-notification!
                      registry
                      {:method "notifications/message"
                       :params {:level "error"
                                :logger "test"
                                :data {:msg "fail"}}})]
          (is (= 2 count))
          (is (= {:level :error :logger "test" :data {:msg "fail"}} @received1))
          (is (= {:level :error :logger "test" :data {:msg "fail"}} @received2)))))

    (testing "converts level string to keyword"
      (let [registry (subs/create-registry)
            received (atom nil)
            callback (fn [params] (reset! received params))]
        (subs/subscribe-log-messages! registry callback)
        (subs/dispatch-notification!
          registry
          {:method "notifications/message"
           :params {:level "warning" :data "msg"}})
        (is (= :warning (:level @received)))))

    (testing "handles optional logger field"
      (let [registry (subs/create-registry)
            received (atom nil)
            callback (fn [params] (reset! received params))]
        (subs/subscribe-log-messages! registry callback)
        (subs/dispatch-notification!
          registry
          {:method "notifications/message"
           :params {:level "info" :data "msg"}})
        (is (nil? (:logger @received)))
        (is (= :info (:level @received)))))

    (testing "handles callback exceptions without crashing"
      (let [registry (subs/create-registry)
            error-callback (fn [_] (throw (ex-info "test error" {})))
            success-callback (fn [_] :success)
            call-count (atom 0)]
        (subs/subscribe-log-messages! registry error-callback)
        (subs/subscribe-log-messages! registry (fn [_]
                                                 (swap! call-count inc)
                                                 (success-callback _)))
        (let [count (subs/dispatch-notification!
                      registry
                      {:method "notifications/message"
                       :params {:level "error" :data "msg"}})]
          (is (= 2 count))
          (is (= 1 @call-count)))))))

(deftest set-log-level-validation-test
  ;; Tests set-log-level-impl! validation
  (testing "set-log-level-impl!"
    (testing "throws for invalid log level"
      (let [client {:session (atom {:state :ready
                                    :server-info {:capabilities {:logging {}}}})}]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Invalid log level"
              (logging/set-log-level-impl! client :invalid)))
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Invalid log level"
              (logging/set-log-level-impl! client :trace)))))))

(deftest subscribe-log-messages-impl-test
  ;; Tests subscribe-log-messages-impl! function
  (testing "subscribe-log-messages-impl!"
    (testing "returns CompletableFuture with unsubscribe function"
      (let [registry (subs/create-registry)
            client {:subscription-registry registry}
            callback (fn [_] :called)
            future (logging/subscribe-log-messages-impl! client callback)]
        (is (instance? CompletableFuture future))
        (let [unsub @future]
          (is (fn? unsub))
          (is (contains? @(:log-message-subscriptions registry) callback))
          (unsub)
          (is (not (contains? @(:log-message-subscriptions registry) callback))))))

    (testing "unsubscribe function works correctly"
      (let [registry (subs/create-registry)
            client {:subscription-registry registry}
            callback (fn [_] :called)
            unsub @(logging/subscribe-log-messages-impl! client callback)]
        (is (contains? @(:log-message-subscriptions registry) callback))
        (unsub)
        (is (not (contains? @(:log-message-subscriptions registry) callback)))))))
