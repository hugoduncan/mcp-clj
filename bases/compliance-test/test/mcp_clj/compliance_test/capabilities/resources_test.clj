(ns mcp-clj.compliance-test.capabilities.resources-test
  "Compliance tests for MCP resources capability across implementations.

  Tests verify that resources functionality works correctly with:
  - mcp-client + mcp-server (Clojure-only, in-memory transport)

  Version-specific behavior is tested using conditional assertions."
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.compliance-test.test-helpers :as helpers]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-server.core :as mcp-server]))

;;; Compliance Tests

(deftest ^:integ resources-list-compliance-test
  ;; Test that resources/list returns available resources with correct schema
  (testing "resources/list returns available resources"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)
        (let [test-resources (helpers/create-test-resources protocol-version)
              pair (helpers/create-clojure-pair protocol-version {:resources test-resources})
              client (:client pair)
              result @(client/list-resources client)
              resources (:resources result)]

          (try
            ;; Basic structure
            (is (vector? resources))
            (is (= 3 (count resources)))

            ;; Resource URIs
            (let [resource-uris (set (map :uri resources))]
              (is (contains? resource-uris "file:///test/text-resource.txt"))
              (is (contains? resource-uris "file:///test/data.json"))
              (is (contains? resource-uris "file:///test/image.png")))

            ;; Required fields for all versions
            (doseq [resource resources]
              (is (string? (:uri resource)))
              (is (string? (:name resource)))
              (is (string? (:mimeType resource)))
              (is (or (nil? (:description resource))
                      (string? (:description resource)))))

            ;; Version-specific fields
            (when (>= (compare protocol-version "2025-03-26") 0)
              (testing "annotations present in 2025-03-26+"
                (let [text-resource (first (filter #(= "text-resource" (:name %)) resources))]
                  (is (map? (:annotations text-resource)))
                  (is (vector? (get-in text-resource [:annotations :audience])))
                  (is (number? (get-in text-resource [:annotations :priority]))))))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ resources-read-compliance-test
  ;; Test that resources/read retrieves resource content correctly
  (testing "resources/read retrieves resource contents"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)
        (let [test-resources (helpers/create-test-resources protocol-version)
              pair (helpers/create-clojure-pair protocol-version {:resources test-resources})
              client (:client pair)]

          (try
            (testing "reading text resource"
              (let [result @(client/read-resource client "file:///test/text-resource.txt")
                    contents (:contents result)]

                (is (vector? contents))
                (is (= 1 (count contents)))
                (is (= "file:///test/text-resource.txt" (:uri (first contents))))
                (is (= "Hello, world!" (:text (first contents))))
                (is (nil? (:blob (first contents))))))

            (testing "reading JSON resource"
              (let [result @(client/read-resource client "file:///test/data.json")
                    contents (:contents result)]

                (is (vector? contents))
                (is (= 1 (count contents)))
                (is (= "file:///test/data.json" (:uri (first contents))))
                (is (= "{\"key\": \"value\"}" (:text (first contents))))))

            (testing "reading blob resource"
              (let [result @(client/read-resource client "file:///test/image.png")
                    contents (:contents result)]

                (is (vector? contents))
                (is (= 1 (count contents)))
                (is (= "file:///test/image.png" (:uri (first contents))))
                (is (string? (:blob (first contents))))
                (is (nil? (:text (first contents))))))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ resources-error-handling-compliance-test
  ;; Test error handling for invalid resource requests
  (testing "error handling for invalid resource requests"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)
        (let [test-resources (helpers/create-test-resources protocol-version)
              pair (helpers/create-clojure-pair protocol-version {:resources test-resources})
              client (:client pair)]

          (try
            (testing "non-existent resource"
              (let [result @(client/read-resource client "file:///test/nonexistent.txt")]
                ;; Should return error result
                (is (true? (:isError result)))
                (is (vector? (:contents result)))))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ resources-subscribe-compliance-test
  ;; Test that resources/subscribe and notifications/resources/updated work correctly
  (testing "resources/subscribe and notifications/resources/updated"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)
        (let [test-resources (helpers/create-test-resources protocol-version)
              pair (helpers/create-clojure-pair protocol-version {:resources test-resources})
              client (:client pair)
              server (:server pair)
              received-notifications (atom [])
              ;; Subscribe to resource updates
              uri "file:///test/text-resource.txt"
              _ @(client/subscribe-resource!
                  client
                  uri
                  (fn [params]
                    (swap! received-notifications conj
                           {:method "notifications/resources/updated" :params params})))
              ;; Trigger resource update notification
              _ (mcp-server/notify-resource-updated! server uri)
              ;; Wait for notification to be processed
              _ (Thread/sleep 200)]

          (try
            ;; Verify notification was received
            (is (seq @received-notifications)
                "Should receive at least one notification")
            (is (some #(= "notifications/resources/updated" (:method %))
                      @received-notifications)
                "Should receive resources/updated notification")
            (is (= uri (get-in (first @received-notifications) [:params :uri]))
                "Notification should contain the correct URI")

            ;; Verify unsubscribe
            (reset! received-notifications [])
            @(client/unsubscribe-resource! client uri)
            (mcp-server/notify-resource-updated! server uri)
            (Thread/sleep 200)
            (is (empty? @received-notifications)
                "Should not receive notifications after unsubscribe")

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ resources-list-changed-notification-test
  ;; Test that notifications/resources/list_changed is sent when resources change
  (testing "notifications/resources/list_changed sent when resources change"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)
        (let [test-resources (helpers/create-test-resources protocol-version)
              pair (helpers/create-clojure-pair protocol-version {:resources test-resources})
              client (:client pair)
              server (:server pair)
              received-notifications (atom [])
              ;; Set up notification handler by subscribing to resources changes
              _ @(client/subscribe-resources-changed!
                  client
                  (fn [params]
                    (swap! received-notifications conj
                           {:method "notifications/resources/list_changed" :params params})))
              ;; Add a new resource to trigger notification
              new-resource {:name "new-resource"
                            :uri "file:///test/new-resource.txt"
                            :mime-type "text/plain"
                            :description "A new test resource"
                            :implementation (fn [_uri]
                                              {:contents [{:uri "file:///test/new-resource.txt"
                                                           :text "New resource content"}]})}
              _ (mcp-server/add-resource! server new-resource)
              ;; Wait for notification to be processed
              _ (Thread/sleep 200)]

          (try
            ;; Verify notification was received
            (is (seq @received-notifications)
                "Should receive at least one notification")
            (is (some #(= "notifications/resources/list_changed" (:method %))
                      @received-notifications)
                "Should receive resources/list_changed notification")

            (finally
              ((:cleanup-fn pair)))))))))
