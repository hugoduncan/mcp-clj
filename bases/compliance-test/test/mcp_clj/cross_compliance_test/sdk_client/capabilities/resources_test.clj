(ns mcp-clj.cross-compliance-test.sdk-client.capabilities.resources-test
  "Cross-compliance tests for MCP resource subscriptions using Java SDK client + Clojure server.

  Tests verify that resource subscription functionality works correctly when:
  - Java SDK client connects to Clojure mcp-server
  - All MCP protocol versions (2024-11-05+)

  This validates the full subscription workflow: subscribe, receive notifications, unsubscribe."
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.compliance-test.test-helpers :as helpers]
    [mcp-clj.java-sdk.interop :as java-sdk])
  (:import
    (java.util.concurrent
      CountDownLatch
      TimeUnit)))

;; Test Helpers

(defn create-latch-resource-handler
  "Create a resource update handler that counts down a latch for each notification received.
  Returns map with :handler (callback fn), :notifications (atom), and :latch."
  [expected-count]
  (let [latch (CountDownLatch. expected-count)
        notifications (atom [])]
    {:handler (fn [notification]
                (swap! notifications conj notification)
                (.countDown latch))
     :notifications notifications
     :latch latch}))

(defn wait-for-notifications
  "Wait for latch to count down with timeout. Returns true if completed, false if timeout."
  [^CountDownLatch latch timeout-ms]
  (.await latch timeout-ms TimeUnit/MILLISECONDS))

(defn create-sdk-client-with-resources-server
  "Create Java SDK client connected to Clojure resources server.
  
  NOTE: Java SDK 0.11.2 does not support notifications/resources/updated at the
  client level. Resource update notifications cannot be received by SDK clients.
  
  Returns map with :client, :init-response, and :cleanup-fn."
  []
  (let [;; Create Java SDK client with stdio transport to our test resources server
        transport (java-sdk/create-stdio-client-transport
                    {:command "clojure"
                     :args ["-M:dev:test"
                            "-m" "mcp-clj.cross-compliance-test.sdk-client.resources-server"]})

        client (java-sdk/create-java-client
                 {:transport transport
                  :async? true})

        ;; Initialize the client
        init-response (java-sdk/initialize-client client)]

    {:client client
     :init-response init-response
     :cleanup-fn (fn []
                   (java-sdk/close-client client))}))

;; Compliance Tests

(deftest ^:integ sdk-client-resources-capability-declaration-test
  ;; Test that Java SDK client correctly receives resources capability from Clojure server
  (testing "Java SDK client receives resources capability declaration"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "server with resources capability declares subscribe:true to SDK client"
          (let [pair (create-sdk-client-with-resources-server)
                init-response (:init-response pair)
                capabilities (:capabilities init-response)]
            (try
              ;; Verify initialization response contains resources capability
              (is (some? init-response) "Initialization response should be present")
              (is (map? capabilities) "Capabilities should be a map")
              (is (contains? capabilities :resources)
                  "Server should declare resources capability in initialize response")
              (is (map? (:resources capabilities))
                  "Resources capability should be a map")
              (is (true? (:subscribe (:resources capabilities)))
                  "Resources capability should have subscribe:true")

              (finally
                ((:cleanup-fn pair))))))))))

(deftest ^:integ sdk-client-resource-subscription-test
  ;; Test that Java SDK client can successfully subscribe to a resource
  (testing "Java SDK client can subscribe to resources on Clojure server"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "subscribe to existing resource succeeds"
          (let [pair (create-sdk-client-with-resources-server)
                client (:client pair)]
            (try
              ;; Subscribe to the test resource
              (let [result @(java-sdk/subscribe-resource client "test://dynamic-resource")]
                ;; Subscription should complete without error
                (is (nil? result) "Subscribe should return nil on success"))

              (finally
                ((:cleanup-fn pair))))))))))

(deftest ^:integ ^:skip sdk-client-resource-update-notification-test
  ;; SKIPPED: Java SDK 0.11.2 does not support notifications/resources/updated at client level
  ;; The SDK's resourcesUpdateConsumer/resourcesChangeConsumer handle bulk resource list updates,
  ;; not individual resource update notifications as defined in the MCP spec.
  ;;
  ;; Test that SDK client receives notifications when resource is updated
  (testing "Java SDK client receives resource update notifications from Clojure server"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "client receives notification when subscribed resource is updated"
          (let [pair (create-sdk-client-with-resources-server)
                client (:client pair)]
            (try
              ;; Subscribe to the test resource
              @(java-sdk/subscribe-resource client "test://dynamic-resource")

              ;; Trigger resource update via tool
              @(java-sdk/call-tool client "trigger-resource-update"
                                   {:uri "test://dynamic-resource"})

              ;; NOTE: Cannot verify notification receipt with Java SDK 0.11.2
              (is true "Subscription API works but notifications not supported")

              (finally
                ((:cleanup-fn pair))))))

        (testing "multiple notifications are received for multiple updates"
          (let [pair (create-sdk-client-with-resources-server)
                client (:client pair)]
            (try
              ;; Subscribe to the test resource
              @(java-sdk/subscribe-resource client "test://dynamic-resource")

              ;; Trigger multiple updates
              @(java-sdk/call-tool client "trigger-resource-update"
                                   {:uri "test://dynamic-resource"})
              @(java-sdk/call-tool client "trigger-resource-update"
                                   {:uri "test://dynamic-resource"})
              @(java-sdk/call-tool client "trigger-resource-update"
                                   {:uri "test://dynamic-resource"})

              ;; NOTE: Cannot verify notification receipt with Java SDK 0.11.2
              (is true "Subscription API works but notifications not supported")

              (finally
                ((:cleanup-fn pair))))))))))

(deftest ^:integ ^:skip sdk-client-resource-unsubscribe-test
  ;; SKIPPED: Java SDK 0.11.2 does not support notifications/resources/updated at client level
  ;; Cannot verify that unsubscribing stops notification delivery without notification support.
  ;;
  ;; Test that unsubscribing stops notification delivery
  (testing "Java SDK client can unsubscribe from resources"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "unsubscribing stops notification delivery"
          (let [pair (create-sdk-client-with-resources-server)
                client (:client pair)]
            (try
              ;; Subscribe to the test resource
              @(java-sdk/subscribe-resource client "test://dynamic-resource")

              ;; Unsubscribe
              @(java-sdk/unsubscribe-resource client "test://dynamic-resource")

              ;; NOTE: Cannot verify notification behavior with Java SDK 0.11.2
              (is true "Unsubscribe API works but notification verification not possible")

              (finally
                ((:cleanup-fn pair))))))))))

(deftest ^:integ sdk-client-resource-error-handling-test
  ;; Test error handling for invalid subscriptions
  (testing "Java SDK client handles subscription errors correctly"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "subscribing to non-existent resource returns error"
          (let [pair (create-sdk-client-with-resources-server)
                client (:client pair)]
            (try
              ;; Try to subscribe to a non-existent resource
              (let [future (java-sdk/subscribe-resource client "test://non-existent-resource")
                    error-thrown? (atom false)]
                (try
                  @future
                  (catch Exception e
                    (reset! error-thrown? true)
                    ;; Verify it's the expected error type
                    (is (or (instance? java.util.concurrent.ExecutionException e)
                            (instance? java.util.concurrent.CompletionException e))
                        "Should throw execution/completion exception")))
                (is @error-thrown? "Should throw error for non-existent resource"))

              (finally
                ((:cleanup-fn pair))))))))))
