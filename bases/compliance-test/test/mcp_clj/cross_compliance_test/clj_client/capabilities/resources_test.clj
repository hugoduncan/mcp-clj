(ns mcp-clj.cross-compliance-test.clj-client.capabilities.resources-test
  "Cross-compliance tests for MCP resource subscriptions using Clojure client + Java SDK server.

  Tests verify that resource subscription functionality works correctly when:
  - Clojure mcp-client connects to Java SDK server via stdio transport
  - All MCP protocol versions (2024-11-05+)

  This validates the full subscription workflow from Clojure client perspective."
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.compliance-test.test-helpers :as helpers]
    [mcp-clj.mcp-client.core :as client])
  (:import
    (java.util.concurrent
      CountDownLatch
      TimeUnit)))

(defn- create-client
  "Create Clojure client connected to Java SDK server with resources capability via stdio"
  []
  (client/create-client
    {:transport {:type :stdio
                 :command "clojure"
                 :args ["-M:dev:test" "-m"
                        "mcp-clj.cross-compliance-test.clj-client.java-sdk-resources-server-main"]}
     :client-info {:name "clj-client-resources-test"
                   :version "0.1.0"}
     :capabilities {}
     :protocol-version "2024-11-05"}))

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

(deftest ^:integ clj-client-resources-capability-declaration-test
  ;; Test that Clojure client correctly receives resources capability from Java SDK server
  (testing "Clojure client receives resources capability declaration from Java SDK server"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "Java SDK server with resources capability declares subscribe:true to Clojure client"
          (with-open [mcp-client (create-client)]
            ;; Wait for client to initialize
            (client/wait-for-ready mcp-client 5000)

            ;; Get server capabilities
            (let [session @(:session mcp-client)
                  capabilities (:server-capabilities session)]

              (is (some? capabilities) "Server capabilities should be present")
              (is (map? capabilities) "Capabilities should be a map")
              (is (contains? capabilities :resources)
                  "Server should declare resources capability")
              (is (map? (:resources capabilities))
                  "Resources capability should be a map")
              (is (true? (:subscribe (:resources capabilities)))
                  "Resources capability should have subscribe:true")
              (is (true? (:listChanged (:resources capabilities)))
                  "Resources capability should have listChanged:true"))))))))

(deftest ^:integ clj-client-resource-subscription-test
  ;; Test that Clojure client can successfully subscribe to a resource on Java SDK server
  (testing "Clojure client can subscribe to resources on Java SDK server"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "subscribe to existing resource succeeds"
          (let [handler-setup (create-latch-resource-handler 0)]
            (with-open [mcp-client (create-client)]
              ;; Wait for client to initialize
              (client/wait-for-ready mcp-client 5000)

              ;; Subscribe to the test resource
              (let [result @(client/subscribe-resource!
                              mcp-client
                              "test://dynamic-resource"
                              (:handler handler-setup))]
                ;; Subscription should complete without error
                (is (nil? result) "Subscribe should return nil on success")))))))))

(deftest ^:integ clj-client-resource-update-notification-test
  ;; Test that Clojure client receives notifications when resource is updated on Java SDK server
  (testing "Clojure client receives resource update notifications from Java SDK server"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "client receives notification when subscribed resource is updated"
          (let [handler-setup (create-latch-resource-handler 1)]
            (with-open [mcp-client (create-client)]
              ;; Wait for client to initialize
              (client/wait-for-ready mcp-client 5000)

              ;; Subscribe to the test resource
              @(client/subscribe-resource!
                 mcp-client
                 "test://dynamic-resource"
                 (:handler handler-setup))

              ;; Trigger resource update via tool
              @(client/call-tool mcp-client "trigger-resource-update"
                                 {:uri "test://dynamic-resource"})

              ;; Wait for notification with 5 second timeout
              (let [completed? (wait-for-notifications (:latch handler-setup) 5000)
                    received @(:notifications handler-setup)]
                (is completed? "Should receive notification within timeout")
                (is (= 1 (count received)) "Should receive exactly 1 notification")
                (is (= "test://dynamic-resource" (:uri (first received)))
                    "Notification should contain correct resource URI")))))

        (testing "multiple notifications are received for multiple updates"
          (let [handler-setup (create-latch-resource-handler 3)]
            (with-open [mcp-client (create-client)]
              ;; Wait for client to initialize
              (client/wait-for-ready mcp-client 5000)

              ;; Subscribe to the test resource
              @(client/subscribe-resource!
                 mcp-client
                 "test://dynamic-resource"
                 (:handler handler-setup))

              ;; Trigger multiple updates
              @(client/call-tool mcp-client "trigger-resource-update"
                                 {:uri "test://dynamic-resource"})
              @(client/call-tool mcp-client "trigger-resource-update"
                                 {:uri "test://dynamic-resource"})
              @(client/call-tool mcp-client "trigger-resource-update"
                                 {:uri "test://dynamic-resource"})

              ;; Wait for all notifications
              (let [completed? (wait-for-notifications (:latch handler-setup) 5000)
                    received @(:notifications handler-setup)]
                (is completed? "Should receive all notifications within timeout")
                (is (= 3 (count received)) "Should receive exactly 3 notifications")
                (is (every? #(= "test://dynamic-resource" (:uri %)) received)
                    "All notifications should contain correct resource URI")))))))))

(deftest ^:integ clj-client-resource-unsubscribe-test
  ;; Test that unsubscribing stops notification delivery from Java SDK server
  (testing "Clojure client can unsubscribe from resources on Java SDK server"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "unsubscribing stops notification delivery"
          (let [handler-setup (create-latch-resource-handler 1)]
            (with-open [mcp-client (create-client)]
              ;; Wait for client to initialize
              (client/wait-for-ready mcp-client 5000)

              ;; Subscribe to the test resource
              @(client/subscribe-resource!
                 mcp-client
                 "test://dynamic-resource"
                 (:handler handler-setup))

              ;; Trigger update to verify subscription works
              @(client/call-tool mcp-client "trigger-resource-update"
                                 {:uri "test://dynamic-resource"})

              ;; Wait for first notification
              (let [completed? (wait-for-notifications (:latch handler-setup) 5000)]
                (is completed? "Should receive first notification"))

              ;; Now unsubscribe
              @(client/unsubscribe-resource! mcp-client "test://dynamic-resource")

              ;; Record notification count before triggering another update
              (let [notifications-before (count @(:notifications handler-setup))]

                ;; Trigger another update
                @(client/call-tool mcp-client "trigger-resource-update"
                                   {:uri "test://dynamic-resource"})

                ;; Wait a bit to see if notification arrives (it shouldn't)
                (Thread/sleep 2000)

                (let [notifications-after (count @(:notifications handler-setup))]
                  (is (= notifications-before notifications-after)
                      "Notification count should not increase after unsubscribe"))))))))))

(deftest ^:integ clj-client-resource-error-handling-test
  ;; Test error handling for invalid subscriptions
  (testing "Clojure client handles subscription errors correctly with Java SDK server"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)

        (testing "subscribing to non-existent resource returns error"
          (let [handler-setup (create-latch-resource-handler 0)]
            (with-open [mcp-client (create-client)]
              ;; Wait for client to initialize
              (client/wait-for-ready mcp-client 5000)

              ;; Try to subscribe to a non-existent resource
              (let [future (client/subscribe-resource!
                             mcp-client
                             "test://non-existent-resource"
                             (:handler handler-setup))
                    error-thrown? (atom false)]
                (try
                  @future
                  (catch Exception e
                    (reset! error-thrown? true)
                    ;; Verify it's the expected error type
                    (is (or (instance? java.util.concurrent.ExecutionException e)
                            (instance? clojure.lang.ExceptionInfo e))
                        "Should throw execution exception or ExceptionInfo")))
                (is @error-thrown? "Should throw error for non-existent resource")))))))))
