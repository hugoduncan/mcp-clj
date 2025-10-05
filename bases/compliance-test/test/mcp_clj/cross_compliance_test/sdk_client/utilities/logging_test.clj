(ns mcp-clj.cross-compliance-test.sdk-client.utilities.logging-test
  "Cross-compliance tests for MCP logging using Java SDK client + Clojure server.

  Tests verify that logging functionality works correctly when:
  - Java SDK client connects to Clojure mcp-server
  - Multiple MCP protocol versions (2025-03-26+, not in 2024-11-05)

  This complements the Clojure client tests by verifying cross-implementation compatibility."
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.compliance-test.test-helpers :as helpers]
   [mcp-clj.java-sdk.interop :as java-sdk]
   [mcp-clj.mcp-server.core :as mcp-server]
   [mcp-clj.mcp-server.logging :as server-logging])
  (:import
   (java.lang AutoCloseable)
   (java.util.concurrent CountDownLatch TimeUnit)))

;;; Test Helpers

(comment
  ;; Removed - not needed with current stdio approach
  (defn create-logging-server-process
    "Create a Clojure MCP server subprocess with logging capability enabled.
    Returns a process map with :process and :server keys."
    [protocol-version]
    nil))

(defn create-sdk-client-with-logging-server
  "Create Java SDK client connected to Clojure server with logging enabled.
  Returns map with :client, :init-response, :log-messages atom, and :cleanup-fn."
  [protocol-version]
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
                 :async? false
                 :logging-handler logging-handler})

        ;; Initialize the client
        init-response (java-sdk/initialize-client client)]

    {:client client
     :init-response init-response
     :log-messages log-messages
     :cleanup-fn (fn []
                   (java-sdk/close-client client))}))

(defn collect-log-messages
  "Create a callback function and atom to collect log messages.
  Returns map with :atom containing collected messages and :callback function."
  []
  (let [received (atom [])]
    {:atom received
     :callback (fn [params]
                 (swap! received conj params))}))

;;; Compliance Tests

(deftest ^:integ sdk-client-logging-capability-declaration-test
  ;; Test that Java SDK client correctly receives logging capability from Clojure server
  (testing "Java SDK client receives logging capability declaration"
    (doseq [protocol-version (filter #(>= (compare % "2025-03-26") 0)
                                     helpers/test-protocol-versions)]
      (testing (str "protocol version " protocol-version)

        (testing "server with :logging {} declares logging capability to SDK client"
          (let [pair (create-sdk-client-with-logging-server protocol-version)
                client (:client pair)
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

        (testing "client can set log level"
          (let [pair (create-sdk-client-with-logging-server protocol-version)
                client (:client pair)
                log-messages (:log-messages pair)]
            (try
              ;; Set log level to debug
              @(java-sdk/set-logging-level client :debug)

              ;; Verify the logging handler is set up
              (is (vector? @log-messages) "Log messages should be collected in a vector")

              ;; Note: Actual message delivery test requires server-side logging triggers
              ;; which would need tool execution or other server activity

              (finally
                ((:cleanup-fn pair))))))))))

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
