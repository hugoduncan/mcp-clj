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
      TimeUnit
      TimeoutException)))

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
  [latch timeout-ms]
  (.await latch timeout-ms TimeUnit/MILLISECONDS))

(defn create-sdk-client-with-resources-server
  "Create Java SDK client connected to Clojure resources server.
  Returns map with :client, :init-response, :resource-handler-setup, and :cleanup-fn."
  [expected-notification-count]
  (let [;; Create handler with latch
        handler-setup (create-latch-resource-handler expected-notification-count)

        ;; Create Java SDK client with stdio transport to our test resources server
        transport (java-sdk/create-stdio-client-transport
                    {:command "clojure"
                     :args ["-M:dev:test"
                            "-m" "mcp-clj.cross-compliance-test.sdk-client.resources-server"]})

        client (java-sdk/create-java-client
                 {:transport transport
                  :async? true
                  :resource-update-handler (:handler handler-setup)})

        ;; Initialize the client
        init-response (java-sdk/initialize-client client)]

    {:client client
     :init-response init-response
     :resource-handler-setup handler-setup
     :cleanup-fn (fn []
                   (java-sdk/close-client client))}))

;; Compliance Tests

(deftest ^:integ sdk-client-resources-capability-declaration-test
  ;; Test that Java SDK client correctly receives resources capability from Clojure server
  (testing "Java SDK client receives resources capability declaration"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "server with resources capability declares subscribe:true to SDK client"
          (let [pair (create-sdk-client-with-resources-server 0)
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
          (let [pair (create-sdk-client-with-resources-server 0)
                client (:client pair)]
            (try
              ;; Subscribe to the test resource
              (let [result @(java-sdk/subscribe-resource client "test://dynamic-resource")]
                ;; Subscription should complete without error
                (is (nil? result) "Subscribe should return nil on success"))

              (finally
                ((:cleanup-fn pair))))))))))

(deftest ^:integ sdk-client-resource-update-notification-test
  ;; Test that SDK client receives notifications when resource is updated
  (testing "Java SDK client receives resource update notifications from Clojure server"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "client receives notification when subscribed resource is updated"
          (let [pair (create-sdk-client-with-resources-server 1)
                client (:client pair)
                handler-setup (:resource-handler-setup pair)]
            (try
              ;; Subscribe to the test resource
              @(java-sdk/subscribe-resource client "test://dynamic-resource")

              ;; Trigger resource update via tool
              @(java-sdk/call-tool client "trigger-resource-update"
                                   {:uri "test://dynamic-resource"})

              ;; Wait for notification with 5 second timeout
              (let [completed? (wait-for-notifications (:latch handler-setup) 5000)
                    received @(:notifications handler-setup)]
                (is completed? "Should receive notification within timeout")
                (is (= 1 (count received)) "Should receive exactly 1 notification")
                (is (= "test://dynamic-resource" (:uri (first received)))
                    "Notification should contain correct resource URI"))

              (finally
                ((:cleanup-fn pair))))))

        (testing "multiple notifications are received for multiple updates"
          (let [pair (create-sdk-client-with-resources-server 3)
                client (:client pair)
                handler-setup (:resource-handler-setup pair)]
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

              ;; Wait for all notifications
              (let [completed? (wait-for-notifications (:latch handler-setup) 5000)
                    received @(:notifications handler-setup)]
                (is completed? "Should receive all notifications within timeout")
                (is (= 3 (count received)) "Should receive exactly 3 notifications")
                (is (every? #(= "test://dynamic-resource" (:uri %)) received)
                    "All notifications should contain correct resource URI"))

              (finally
                ((:cleanup-fn pair))))))))))

(deftest ^:integ sdk-client-resource-unsubscribe-test
  ;; Test that unsubscribing stops notification delivery
  (testing "Java SDK client can unsubscribe from resources"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "unsubscribing stops notification delivery"
          (let [pair (create-sdk-client-with-resources-server 1)
                client (:client pair)
                handler-setup (:resource-handler-setup pair)]
            (try
              ;; Subscribe to the test resource
              @(java-sdk/subscribe-resource client "test://dynamic-resource")

              ;; Trigger update to verify subscription works
              @(java-sdk/call-tool client "trigger-resource-update"
                                   {:uri "test://dynamic-resource"})

              ;; Wait for first notification
              (let [completed? (wait-for-notifications (:latch handler-setup) 5000)]
                (is completed? "Should receive first notification"))

              ;; Now unsubscribe
              @(java-sdk/unsubscribe-resource client "test://dynamic-resource")

              ;; Create new latch for checking no more notifications
              (let [no-notification-latch (CountDownLatch. 1)
                    notifications-before (count @(:notifications handler-setup))]

                ;; Trigger another update
                @(java-sdk/call-tool client "trigger-resource-update"
                                     {:uri "test://dynamic-resource"})

                ;; Wait a bit to see if notification arrives (it shouldn't)
                (let [received-notification? (.await no-notification-latch 2000 TimeUnit/MILLISECONDS)
                      notifications-after (count @(:notifications handler-setup))]
                  (is (not received-notification?) "Should NOT receive notification after unsubscribe")
                  (is (= notifications-before notifications-after)
                      "Notification count should not increase after unsubscribe")))

              (finally
                ((:cleanup-fn pair))))))))))

(deftest ^:integ sdk-client-resource-error-handling-test
  ;; Test error handling for invalid subscriptions
  (testing "Java SDK client handles subscription errors correctly"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "subscribing to non-existent resource returns error"
          (let [pair (create-sdk-client-with-resources-server 0)
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
