(ns mcp-clj.compliance-test.utilities.logging-test
  "Compliance tests for MCP logging utility across protocol versions.

  Tests verify that logging functionality works correctly across:
  - Multiple MCP protocol versions (2025-03-26+, not in 2024-11-05)
  - Clojure client + Clojure server (in-memory transport)

  Version-specific behavior is tested using conditional assertions."
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.compliance-test.test-helpers :as helpers]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-server.logging :as server-logging]
   [mcp-clj.mcp-server.core :as mcp-server])
  (:import
   [java.util.concurrent CountDownLatch TimeUnit]))

;;; Test Helpers

(defn create-logging-server
  "Create server with logging capability enabled for given protocol version.
  Returns map with :client, :server, :init-response, and :cleanup-fn."
  [protocol-version]
  (let [shared-transport (mcp-clj.in-memory-transport.shared/create-shared-transport)
        server-atom (atom nil)
        test-tools (helpers/create-test-tools protocol-version :server-atom server-atom)

        ;; Create server with logging capability
        mcp-server (mcp-server/create-server
                    {:transport {:type :in-memory
                                 :shared shared-transport}
                     :tools test-tools
                     :server-info {:name "test-server"
                                   :version "1.0.0"}
                     :capabilities {:logging {}}})

        _ (reset! server-atom mcp-server)

        ;; Create client
        mcp-client (client/create-client
                    {:transport {:type :in-memory
                                 :shared shared-transport}
                     :client-info {:name "test-client"
                                   :version "1.0.0"}
                     :protocol-version protocol-version})

        ;; Wait for client to initialize and capture response
        init-response (client/wait-for-ready mcp-client 5000)]

    {:client mcp-client
     :server mcp-server
     :init-response init-response
     :cleanup-fn (fn []
                   (client/close! mcp-client)
                   ((:stop mcp-server)))}))

(defn create-non-logging-server
  "Create server WITHOUT logging capability for given protocol version.
  Returns map with :client, :server, :init-response, and :cleanup-fn."
  [protocol-version]
  (let [shared-transport (mcp-clj.in-memory-transport.shared/create-shared-transport)
        test-tools (helpers/create-test-tools protocol-version)

        ;; Create server WITHOUT logging capability
        mcp-server (mcp-server/create-server
                    {:transport {:type :in-memory
                                 :shared shared-transport}
                     :tools test-tools
                     :server-info {:name "test-server"
                                   :version "1.0.0"}})

        ;; Create client
        mcp-client (client/create-client
                    {:transport {:type :in-memory
                                 :shared shared-transport}
                     :client-info {:name "test-client"
                                   :version "1.0.0"}
                     :protocol-version protocol-version})

        ;; Wait for client to initialize and capture response
        init-response (client/wait-for-ready mcp-client 5000)]

    {:client mcp-client
     :server mcp-server
     :init-response init-response
     :cleanup-fn (fn []
                   (client/close! mcp-client)
                   ((:stop mcp-server)))}))

(defn collect-log-messages
  "Create a callback function and atom to collect log messages.
  Returns map with :atom containing collected messages and :callback function."
  []
  (let [received (atom [])]
    {:atom received
     :callback (fn [params]
                 (swap! received conj params))}))

(defn create-latch-log-collector
  "Create a log message collector with CountDownLatch for synchronization.
  Returns map with :callback (fn to pass to subscribe), :messages (atom), and :latch."
  [expected-count]
  (let [latch (CountDownLatch. expected-count)
        messages (atom [])
        call-count (atom 0)]
    {:callback (fn [params]
                 (let [count (swap! call-count inc)]
                   (swap! messages conj params)
                   (.countDown latch)))
     :messages messages
     :latch latch
     :call-count call-count}))

(defn wait-for-log-messages
  "Wait for latch to count down with timeout. Returns true if completed, false if timeout."
  [latch timeout-ms]
  (.await latch timeout-ms TimeUnit/MILLISECONDS))

;;; Compliance Tests

(deftest ^:integ logging-capability-declaration-test
  ;; Test that logging capability is properly declared in initialize response
  (testing "logging capability declaration"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        ;; Logging was added in 2025-03-26
        (if (>= (compare protocol-version "2025-03-26") 0)
          (do
            (testing "server with :logging {} declares logging capability"
              (let [pair (create-logging-server protocol-version)
                    init-response (:init-response pair)
                    capabilities (:capabilities init-response)]
                (try
                  (is (contains? capabilities :logging)
                      "Server should declare logging capability in initialize response")
                  (is (map? (:logging capabilities))
                      "Logging capability should be a map")
                  (finally
                    ((:cleanup-fn pair))))))

            (testing "server without :logging does not declare logging capability"
              (let [pair (create-non-logging-server protocol-version)
                    init-response (:init-response pair)
                    capabilities (:capabilities init-response)]
                (try
                  (is (not (contains? capabilities :logging))
                      "Server should not declare logging capability in initialize response")
                  (finally
                    ((:cleanup-fn pair)))))))

          (testing "logging not present in version 2024-11-05"
            (let [pair (create-non-logging-server protocol-version)
                  init-response (:init-response pair)
                  capabilities (:capabilities init-response)]
              (try
                (is (not (contains? capabilities :logging))
                    "Logging capability should not exist in 2024-11-05 initialize response")
                (finally
                  ((:cleanup-fn pair)))))))))))

(deftest ^:integ logging-set-level-request-test
  ;; Test logging/setLevel request handling
  (testing "logging/setLevel request"
    (doseq [protocol-version (filter #(>= (compare % "2025-03-26") 0)
                                     helpers/test-protocol-versions)]
      (testing (str "protocol version " protocol-version)
        (let [pair (create-logging-server protocol-version)
              client (:client pair)]
          (try
            (testing "valid log levels return empty response"
              (doseq [level [:debug :info :notice :warning :error :critical :alert :emergency]]
                (let [result @(client/set-log-level! client level)]
                  (is (= {} result)
                      (str "Setting level " level " should return empty map")))))

            (testing "invalid log level returns -32602 error"
              (try
                @(client/set-log-level! client :invalid)
                (is false "Should have thrown exception for invalid level")
                (catch Exception e
                  (let [data (ex-data e)]
                    (is (= :invalid-log-level (:error-type data))
                        "Should throw invalid-log-level error")))))

            (testing "level persists across multiple requests"
              @(client/set-log-level! client :warning)
              (let [result @(client/set-log-level! client :error)]
                (is (= {} result)
                    "Second set-level should succeed")))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ logging-level-filtering-test
  ;; Test log level hierarchy and filtering
  (testing "log level filtering"
    (doseq [protocol-version (filter #(>= (compare % "2025-03-26") 0)
                                     helpers/test-protocol-versions)]
      (testing (str "protocol version " protocol-version)
        (let [pair (create-logging-server protocol-version)
              client (:client pair)
              server (:server pair)
              collector (collect-log-messages)
              _ @(client/subscribe-log-messages! client (:callback collector))]
          (try
            (testing "warning level receives warning and above"
              (reset! (:atom collector) [])
              @(client/set-log-level! client :warning)
              (Thread/sleep 100)

              ;; Send messages at all levels
              (server-logging/debug server {:msg "debug message"})
              (server-logging/info server {:msg "info message"})
              (server-logging/notice server {:msg "notice message"})
              (server-logging/warn server {:msg "warning message"})
              (server-logging/error server {:msg "error message"})
              (server-logging/critical server {:msg "critical message"})
              (server-logging/alert server {:msg "alert message"})
              (server-logging/emergency server {:msg "emergency message"})

              (Thread/sleep 200)

              (let [received @(:atom collector)
                    levels (set (map (comp keyword :level) received))]
                (is (contains? levels :warning) "Should receive warning")
                (is (contains? levels :error) "Should receive error")
                (is (contains? levels :critical) "Should receive critical")
                (is (contains? levels :alert) "Should receive alert")
                (is (contains? levels :emergency) "Should receive emergency")
                (is (not (contains? levels :debug)) "Should NOT receive debug")
                (is (not (contains? levels :info)) "Should NOT receive info")
                (is (not (contains? levels :notice)) "Should NOT receive notice")))

            (testing "debug level receives all messages"
              (reset! (:atom collector) [])
              @(client/set-log-level! client :debug)
              (Thread/sleep 100)

              (server-logging/debug server {:msg "debug"})
              (server-logging/info server {:msg "info"})
              (server-logging/emergency server {:msg "emergency"})

              (Thread/sleep 200)

              (let [received @(:atom collector)
                    levels (set (map (comp keyword :level) received))]
                (is (= 3 (count received)) "Should receive all 3 messages")
                (is (contains? levels :debug) "Should receive debug")
                (is (contains? levels :info) "Should receive info")
                (is (contains? levels :emergency) "Should receive emergency")))

            (testing "emergency level only receives emergency"
              (reset! (:atom collector) [])
              @(client/set-log-level! client :emergency)
              (Thread/sleep 100)

              (server-logging/error server {:msg "error"})
              (server-logging/critical server {:msg "critical"})
              (server-logging/emergency server {:msg "emergency"})

              (Thread/sleep 200)

              (let [received @(:atom collector)
                    levels (set (map (comp keyword :level) received))]
                (is (= 1 (count received)) "Should receive only 1 message")
                (is (contains? levels :emergency) "Should receive emergency")
                (is (not (contains? levels :error)) "Should NOT receive error")
                (is (not (contains? levels :critical)) "Should NOT receive critical")))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ logging-notification-delivery-test
  ;; Test notifications/message delivery format and content by invoking a tool
  ;; that generates log messages at different levels
  (testing "notifications/message delivery"
    (doseq [protocol-version (filter #(>= (compare % "2025-03-26") 0)
                                     helpers/test-protocol-versions)]
      (testing (str "protocol version " protocol-version)
        (let [pair (create-logging-server protocol-version)
              client (:client pair)]
          (try
            @(client/set-log-level! client :debug)

            (testing "message format with logger"
              (let [collector (create-latch-log-collector 1)
                    _ @(client/subscribe-log-messages! client (:callback collector))]
                @(client/call-tool client "trigger-logs"
                                   {:levels ["error"]
                                    :message "test message"
                                    :logger "database"})

                (let [completed? (wait-for-log-messages (:latch collector) 2000)
                      msg (first @(:messages collector))
                      level (if (keyword? (:level msg)) (name (:level msg)) (:level msg))]
                  (is completed? "Should receive message within timeout")
                  (is (= "error" level) "Level should be error")
                  (is (= "database" (:logger msg)) "Logger should be present")
                  (is (= {:msg "test message"} (:data msg)) "Data should match"))))

            (testing "message format without logger"
              (let [collector (create-latch-log-collector 1)
                    _ @(client/subscribe-log-messages! client (:callback collector))]
                @(client/call-tool client "trigger-logs"
                                   {:levels ["info"]
                                    :message "status ok"})

                (let [completed? (wait-for-log-messages (:latch collector) 2000)
                      msg (first @(:messages collector))
                      level (if (keyword? (:level msg)) (name (:level msg)) (:level msg))]
                  (is completed? "Should receive message within timeout")
                  (is (= "info" level) "Level should be info")
                  (is (nil? (:logger msg)) "Logger should be nil")
                  (is (= {:msg "status ok"} (:data msg)) "Data should match"))))

            (testing "multiple log levels"
              (let [collector (create-latch-log-collector 3)
                    _ @(client/subscribe-log-messages! client (:callback collector))]
                @(client/call-tool client "trigger-logs"
                                   {:levels ["warning" "notice" "error"]
                                    :message "multi-level test"})

                (let [completed? (wait-for-log-messages (:latch collector) 2000)
                      messages @(:messages collector)
                      levels (set (map #(if (keyword? (:level %))
                                          (name (:level %))
                                          (:level %))
                                       messages))]
                  (is completed? "Should receive all messages within timeout")
                  (is (= 3 (count messages)) "Should receive 3 messages")
                  (is (contains? levels "warning") "Should have warning level")
                  (is (contains? levels "notice") "Should have notice level")
                  (is (contains? levels "error") "Should have error level")
                  (is (every? #(= {:msg "multi-level test"} (:data %)) messages)
                      "All messages should have same data"))))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ logging-default-level-test
  ;; Test default log level behavior when client doesn't set level
  (testing "default log level behavior"
    (doseq [protocol-version (filter #(>= (compare % "2025-03-26") 0)
                                     helpers/test-protocol-versions)]
      (testing (str "protocol version " protocol-version)
        (let [pair (create-logging-server protocol-version)
              client (:client pair)
              server (:server pair)
              collector (collect-log-messages)
              _ @(client/subscribe-log-messages! client (:callback collector))]
          (try
            (testing "default level is :error"
              ;; Don't call set-log-level! - use default
              (Thread/sleep 100)

              (server-logging/debug server {:msg "debug"})
              (server-logging/info server {:msg "info"})
              (server-logging/notice server {:msg "notice"})
              (server-logging/warn server {:msg "warning"})
              (server-logging/error server {:msg "error"})
              (server-logging/critical server {:msg "critical"})
              (server-logging/alert server {:msg "alert"})
              (server-logging/emergency server {:msg "emergency"})

              (Thread/sleep 200)

              (let [received @(:atom collector)
                    levels (set (map (comp keyword :level) received))]
                (is (contains? levels :error) "Should receive error with default level")
                (is (contains? levels :critical) "Should receive critical")
                (is (contains? levels :alert) "Should receive alert")
                (is (contains? levels :emergency) "Should receive emergency")
                (is (not (contains? levels :debug)) "Should NOT receive debug")
                (is (not (contains? levels :info)) "Should NOT receive info")
                (is (not (contains? levels :notice)) "Should NOT receive notice")
                (is (not (contains? levels :warning)) "Should NOT receive warning")))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ logging-rfc5424-compliance-test
  ;; Test RFC 5424 compliance: all 8 levels, correct ordering, lowercase strings
  (testing "RFC 5424 compliance"
    (doseq [protocol-version (filter #(>= (compare % "2025-03-26") 0)
                                     helpers/test-protocol-versions)]
      (testing (str "protocol version " protocol-version)
        (let [pair (create-logging-server protocol-version)
              client (:client pair)
              server (:server pair)
              collector (collect-log-messages)
              _ @(client/subscribe-log-messages! client (:callback collector))]
          (try
            @(client/set-log-level! client :debug)
            (Thread/sleep 100)

            (testing "all 8 RFC 5424 levels supported"
              (reset! (:atom collector) [])

              ;; Send one message at each level
              (server-logging/debug server {:level "debug"})
              (server-logging/info server {:level "info"})
              (server-logging/notice server {:level "notice"})
              (server-logging/warn server {:level "warning"})
              (server-logging/error server {:level "error"})
              (server-logging/critical server {:level "critical"})
              (server-logging/alert server {:level "alert"})
              (server-logging/emergency server {:level "emergency"})

              (Thread/sleep 200)

              (let [received @(:atom collector)
                    levels (map (comp name :level) received)]
                (is (= 8 (count received)) "Should receive all 8 levels")
                (is (= #{"debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"}
                       (set levels))
                    "All 8 RFC 5424 levels present")))

            (testing "level names are lowercase strings in notifications"
              (let [received @(:atom collector)]
                (doseq [msg received]
                  (let [level (if (keyword? (:level msg))
                                (name (:level msg))
                                (:level msg))]
                    (is (string? level) "Level should be string")
                    (is (= level (clojure.string/lower-case level))
                        "Level should be lowercase")))))

            (testing "severity ordering is correct"
              ;; Test that emergency is most severe, debug is least
              (reset! (:atom collector) [])

              ;; Set to critical - should only receive critical and above
              @(client/set-log-level! client :critical)
              (Thread/sleep 100)

              (server-logging/error server {:msg "error"})
              (server-logging/critical server {:msg "critical"})
              (server-logging/alert server {:msg "alert"})
              (server-logging/emergency server {:msg "emergency"})

              (Thread/sleep 200)

              (let [received @(:atom collector)
                    levels (set (map (comp name :level) received))]
                (is (= 3 (count received)) "Should receive 3 messages (critical and above)")
                (is (contains? levels "critical"))
                (is (contains? levels "alert"))
                (is (contains? levels "emergency"))
                (is (not (contains? levels "error")) "Error is below critical")))

            (finally
              ((:cleanup-fn pair)))))))))
