(ns mcp-clj.mcp-server.logging-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.mcp-server.logging :as logging]))

(deftest valid-level-test
  (testing "valid-level?"
    (testing "accepts all RFC 5424 log levels"
      (is (logging/valid-level? :debug))
      (is (logging/valid-level? :info))
      (is (logging/valid-level? :notice))
      (is (logging/valid-level? :warning))
      (is (logging/valid-level? :error))
      (is (logging/valid-level? :critical))
      (is (logging/valid-level? :alert))
      (is (logging/valid-level? :emergency)))

    (testing "rejects invalid log levels"
      (is (not (logging/valid-level? :invalid)))
      (is (not (logging/valid-level? :warn))) ; note: function name is 'warn' but level is 'warning'
      (is (not (logging/valid-level? :fatal)))
      (is (not (logging/valid-level? nil)))
      (is (not (logging/valid-level? "error"))))))

(deftest get-session-log-level-test
  (testing "get-session-log-level"
    (testing "returns session log level when set"
      (is (= :debug (logging/get-session-log-level {:log-level :debug})))
      (is (= :error (logging/get-session-log-level {:log-level :error}))))

    (testing "returns default :error level when not set"
      (is (= :error (logging/get-session-log-level {})))
      (is (= :error (logging/get-session-log-level {:log-level nil}))))))

(deftest should-send-to-session-test
  ;; Test log level hierarchy:
  ;; :emergency (0) > :alert (1) > :critical (2) > :error (3) > :warning (4) > :notice (5) > :info (6) > :debug (7)
  ;; Lower numbers are more severe

  (testing "should-send-to-session?"
    (testing "with :error threshold (default)"
      (let [session {:log-level :error}]
        (is (logging/should-send-to-session? session :emergency) "emergency >= error")
        (is (logging/should-send-to-session? session :alert) "alert >= error")
        (is (logging/should-send-to-session? session :critical) "critical >= error")
        (is (logging/should-send-to-session? session :error) "error >= error")
        (is (not (logging/should-send-to-session? session :warning)) "warning < error")
        (is (not (logging/should-send-to-session? session :notice)) "notice < error")
        (is (not (logging/should-send-to-session? session :info)) "info < error")
        (is (not (logging/should-send-to-session? session :debug)) "debug < error")))

    (testing "with :warning threshold"
      (let [session {:log-level :warning}]
        (is (logging/should-send-to-session? session :emergency))
        (is (logging/should-send-to-session? session :alert))
        (is (logging/should-send-to-session? session :critical))
        (is (logging/should-send-to-session? session :error))
        (is (logging/should-send-to-session? session :warning))
        (is (not (logging/should-send-to-session? session :notice)))
        (is (not (logging/should-send-to-session? session :info)))
        (is (not (logging/should-send-to-session? session :debug)))))

    (testing "with :debug threshold (most permissive)"
      (let [session {:log-level :debug}]
        (is (logging/should-send-to-session? session :emergency))
        (is (logging/should-send-to-session? session :alert))
        (is (logging/should-send-to-session? session :critical))
        (is (logging/should-send-to-session? session :error))
        (is (logging/should-send-to-session? session :warning))
        (is (logging/should-send-to-session? session :notice))
        (is (logging/should-send-to-session? session :info))
        (is (logging/should-send-to-session? session :debug))))

    (testing "with :emergency threshold (most restrictive)"
      (let [session {:log-level :emergency}]
        (is (logging/should-send-to-session? session :emergency))
        (is (not (logging/should-send-to-session? session :alert)))
        (is (not (logging/should-send-to-session? session :critical)))
        (is (not (logging/should-send-to-session? session :error)))
        (is (not (logging/should-send-to-session? session :warning)))
        (is (not (logging/should-send-to-session? session :notice)))
        (is (not (logging/should-send-to-session? session :info)))
        (is (not (logging/should-send-to-session? session :debug)))))

    (testing "with no threshold set (uses default :error)"
      (let [session {}]
        (is (logging/should-send-to-session? session :emergency))
        (is (logging/should-send-to-session? session :error))
        (is (not (logging/should-send-to-session? session :warning)))
        (is (not (logging/should-send-to-session? session :debug)))))))

(deftest log-message-validation-test
  (testing "log-message validation"
    (testing "throws exception for invalid log level"
      (let [mock-server {:session-id->session (atom {})
                         :json-rpc-server (atom nil)}]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Invalid log level"
              (logging/log-message mock-server :invalid-level {:data "test"})))

        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Invalid log level"
              (logging/log-message mock-server :warn {:data "test"})))))))
