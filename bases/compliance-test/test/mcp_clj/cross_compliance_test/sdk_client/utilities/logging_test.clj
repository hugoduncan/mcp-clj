(ns mcp-clj.cross-compliance-test.sdk-client.utilities.logging-test
  "Cross-compliance tests for MCP logging using Java SDK client + Clojure server.

  Tests verify that logging functionality works correctly when:
  - Java SDK client connects to Clojure mcp-server
  - Multiple MCP protocol versions (2025-03-26+, not in 2024-11-05)

  This complements the Clojure client tests by verifying cross-implementation compatibility."
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.compliance-test.test-helpers :as helpers]
    [mcp-clj.java-sdk.interop :as java-sdk])
  (:import
    (java.util.concurrent
      CountDownLatch
      TimeUnit)))

;; Test Helpers

(defn create-sdk-client-with-logging-server
  "Create Java SDK client connected to Clojure server with logging enabled.
  Returns map with :client, :init-response, :log-messages atom, and :cleanup-fn."
  [_protocol-version]
  (let [;; Atom to collect log messages
        log-messages (atom [])

        ;; Create logging handler
        logging-handler (fn [notification]
                          (swap! log-messages conj notification))

        ;; Create Java SDK client with stdio transport to our test logging server
        transport (java-sdk/create-stdio-client-transport
                    {:command "clojure"
                     :args ["-M:dev:test"
                            "-m" "mcp-clj.cross-compliance-test.sdk-client.logging-server"]})

        client (java-sdk/create-java-client
                 {:transport transport
                  :async? true
                  :logging-handler logging-handler})

        ;; Initialize the client
        init-response (java-sdk/initialize-client client)]

    {:client client
     :init-response init-response
     :log-messages log-messages
     :cleanup-fn (fn []
                   (java-sdk/close-client client))}))

(defn create-latch-logging-handler
  "Create a logging handler that counts down a latch for each message received.
  Returns map with :handler (callback fn), :messages (atom), and :latch."
  [expected-count]
  (let [latch (CountDownLatch. expected-count)
        messages (atom [])]
    {:handler (fn [notification]
                (swap! messages conj notification)
                (.countDown latch))
     :messages messages
     :latch latch}))

(defn wait-for-messages
  "Wait for latch to count down with timeout. Returns true if completed, false if timeout."
  [^CountDownLatch latch timeout-ms]
  (.await latch timeout-ms TimeUnit/MILLISECONDS))

;; Compliance Tests

(deftest ^:integ sdk-client-logging-capability-declaration-test
  ;; Test that Java SDK client correctly receives logging capability from Clojure server
  (testing "Java SDK client receives logging capability declaration"
    (doseq [protocol-version (filter #(>= (compare % "2025-03-26") 0)
                                     helpers/test-protocol-versions)]
      (testing (str "protocol version " protocol-version)

        (testing "server with :logging {} declares logging capability to SDK client"
          (let [pair (create-sdk-client-with-logging-server protocol-version)
                init-response (:init-response pair)
                capabilities (:capabilities init-response)]
            (try
              ;; Verify initialization response contains logging capability
              (is (some? init-response) "Initialization response should be present")
              (is (map? capabilities) "Capabilities should be a map")
              (is (contains? capabilities :logging)
                  "Server should declare logging capability in initialize response")
              (is (map? (:logging capabilities))
                  "Logging capability should be a map")

              (finally
                ((:cleanup-fn pair))))))))))

(deftest ^:integ sdk-client-logging-notification-delivery-test
  ;; Test that log notifications from Clojure server reach Java SDK client
  (testing "Java SDK client receives log notifications from Clojure server"
    (doseq [protocol-version (filter #(>= (compare % "2025-03-26") 0)
                                     helpers/test-protocol-versions)]
      (testing (str "protocol version " protocol-version)

        (testing "client receives log messages triggered by tool execution"
          (let [;; Create handler with latch expecting 3 messages
                handler-setup (create-latch-logging-handler 3)

                ;; Create Java SDK client with stdio transport to our test logging server
                transport (java-sdk/create-stdio-client-transport
                            {:command "clojure"
                             :args ["-M:dev:test"
                                    "-m" "mcp-clj.cross-compliance-test.sdk-client.logging-server"]})

                client (java-sdk/create-java-client
                         {:transport transport
                          :async? true
                          :logging-handler (:handler handler-setup)})

                ;; Initialize the client
                _init-response (java-sdk/initialize-client client)]
            (try
              ;; Set log level to debug to receive all messages
              @(java-sdk/set-logging-level client :debug)

              ;; Trigger log messages via tool
              @(java-sdk/call-tool client "trigger-logs"
                                   {:levels ["error" "warning" "info"]
                                    :message "test message"})

              ;; Wait for messages with 5 second timeout
              (let [completed? (wait-for-messages (:latch handler-setup) 5000)
                    received @(:messages handler-setup)
                    levels (set (map :level received))]
                (is completed? "Should receive all expected messages within timeout")
                (is (>= (count received) 3) "Should receive at least 3 log messages")
                (is (contains? levels :error) "Should receive error level")
                (is (contains? levels :warning) "Should receive warning level")
                (is (contains? levels :info) "Should receive info level")
                (is (every? #(= "test message" (:data %)) received) "All messages should have correct data"))

              (finally
                (java-sdk/close-client client)))))

        (testing "client receives messages with logger name"
          (let [;; Create handler expecting 1 message
                handler-setup (create-latch-logging-handler 1)

                transport (java-sdk/create-stdio-client-transport
                            {:command "clojure"
                             :args ["-M:dev:test"
                                    "-m" "mcp-clj.cross-compliance-test.sdk-client.logging-server"]})

                client (java-sdk/create-java-client
                         {:transport transport
                          :async? true
                          :logging-handler (:handler handler-setup)})

                _init-response (java-sdk/initialize-client client)]
            (try
              @(java-sdk/set-logging-level client :debug)

              ;; Trigger log with logger name
              @(java-sdk/call-tool client "trigger-logs"
                                   {:levels ["error"]
                                    :message "test with logger"
                                    :logger "test-logger"})

              ;; Wait for message with 5 second timeout
              (let [completed? (wait-for-messages (:latch handler-setup) 5000)
                    received @(:messages handler-setup)
                    error-msg (first (filter #(= :error (:level %)) received))]
                (is completed? "Should receive message within timeout")
                (is (some? error-msg) "Should receive error message")
                (is (= "test-logger" (:logger error-msg)) "Should have correct logger name")
                (is (= "test with logger" (:data error-msg)) "Should have correct message data"))

              (finally
                (java-sdk/close-client client)))))

        (testing "log level filtering works correctly"
          (let [;; Create handler expecting 2 messages (warning and error only)
                handler-setup (create-latch-logging-handler 2)

                transport (java-sdk/create-stdio-client-transport
                            {:command "clojure"
                             :args ["-M:dev:test"
                                    "-m" "mcp-clj.cross-compliance-test.sdk-client.logging-server"]})

                client (java-sdk/create-java-client
                         {:transport transport
                          :async? true
                          :logging-handler (:handler handler-setup)})

                _init-response (java-sdk/initialize-client client)]
            (try
              ;; Set level to warning - should only receive warning and above
              @(java-sdk/set-logging-level client :warning)

              ;; Trigger messages at all levels
              @(java-sdk/call-tool client "trigger-logs"
                                   {:levels ["debug" "info" "warning" "error"]
                                    :message "filtering test"})

              ;; Wait for messages with 5 second timeout
              (let [completed? (wait-for-messages (:latch handler-setup) 5000)
                    received @(:messages handler-setup)
                    levels (set (map :level received))]
                (is completed? "Should receive expected messages within timeout")
                (is (contains? levels :warning) "Should receive warning")
                (is (contains? levels :error) "Should receive error")
                (is (not (contains? levels :debug)) "Should NOT receive debug")
                (is (not (contains? levels :info)) "Should NOT receive info")
                (is (every? #(= "filtering test" (:data %)) received) "All messages should have correct data"))

              (finally
                (java-sdk/close-client client)))))))))

(comment
  ;; TODO: Implement remaining cross-compliance tests once Java SDK client
  ;; supports in-memory transport or stdio subprocess management:

  ;; - sdk-client-logging-set-level-test
  ;;   Test that SDK client can set log level on Clojure server

  ;; - sdk-client-logging-level-filtering-test
  ;;   Test that log level filtering works correctly

  ;; - sdk-client-logging-rfc5424-compliance-test
  ;;   Test RFC 5424 level compliance across implementations
  )
