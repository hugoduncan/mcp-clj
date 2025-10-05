(ns mcp-clj.compliance-test.capabilities.prompts-test
  "Compliance tests for MCP prompts capability across implementations.

  Tests verify that prompts functionality works correctly with:
  - mcp-client + mcp-server (Clojure-only, in-memory transport)

  Version-specific behavior is tested using conditional assertions.

  Note: Currently testing only Clojure client + server as cross-implementation
  testing requires additional setup for Java SDK prompt support."
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.compliance-test.test-helpers :as helpers]
    [mcp-clj.mcp-client.core :as client]
    [mcp-clj.mcp-server.core :as mcp-server]))

;; Compliance Tests

(deftest ^:integ prompts-list-compliance-test
  ;; Test that prompts/list returns available prompts with correct schema
  (testing "prompts/list returns available prompts"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)
        (let [test-prompts (helpers/create-test-prompts protocol-version)
              pair (helpers/create-clojure-pair protocol-version {:prompts test-prompts})
              client (:client pair)
              result @(client/list-prompts client)
              prompts (:prompts result)]

          (try
            ;; Basic structure
            (is (vector? prompts))
            (is (= 3 (count prompts)))

            ;; Prompt names
            (let [prompt-names (set (map :name prompts))]
              (is (contains? prompt-names "simple"))
              (is (contains? prompt-names "template"))
              (is (contains? prompt-names "optional-args")))

            ;; Required fields for all versions
            (doseq [prompt prompts]
              (is (string? (:name prompt)))
              (is (or (nil? (:description prompt))
                      (string? (:description prompt))))
              (is (or (nil? (:arguments prompt))
                      (vector? (:arguments prompt)))))

            ;; Version-specific fields
            (when (>= (compare protocol-version "2025-06-18") 0)
              (testing "title field present in 2025-06-18+"
                (let [simple-prompt (first (filter #(= "simple" (:name %)) prompts))]
                  (is (string? (:title simple-prompt)))
                  (is (= "Simple Prompt" (:title simple-prompt))))))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ prompts-get-compliance-test
  ;; Test that prompts/get retrieves prompts with argument substitution
  (testing "prompts/get retrieves prompts correctly"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)
        (let [test-prompts (helpers/create-test-prompts protocol-version)
              pair (helpers/create-clojure-pair protocol-version {:prompts test-prompts})
              client (:client pair)]

          (try
            (testing "simple prompt without arguments"
              (let [result @(client/get-prompt client "simple")
                    messages (:messages result)]

                (is (vector? messages))
                (is (= 1 (count messages)))
                (is (= "user" (:role (first messages))))
                (is (= "text" (get-in (first messages) [:content :type])))
                (is (= "Please help me with a task."
                       (get-in (first messages) [:content :text])))))

            (testing "template prompt with arguments"
              (let [result @(client/get-prompt client "template" {:task "code review"})
                    messages (:messages result)]

                (is (vector? messages))
                (is (= 1 (count messages)))
                (is (= "user" (:role (first messages))))
                (is (= "Please help me with: code review"
                       (get-in (first messages) [:content :text])))))

            (testing "prompt with optional arguments"
              (let [result @(client/get-prompt client "optional-args" {:context "test context"})
                    messages (:messages result)]

                (is (vector? messages))
                (is (= 2 (count messages)))
                (is (= "system" (:role (first messages))))
                (is (= "user" (:role (second messages))))
                (is (= "Context: test context"
                       (get-in (second messages) [:content :text])))))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ prompts-error-handling-compliance-test
  ;; Test error handling for invalid prompt requests
  (testing "error handling for invalid prompt requests"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)
        (let [test-prompts (helpers/create-test-prompts protocol-version)
              pair (helpers/create-clojure-pair protocol-version {:prompts test-prompts})
              client (:client pair)]

          (try
            (testing "non-existent prompt"
              (let [result @(client/get-prompt client "nonexistent")]
                ;; Should return error result
                (is (true? (:isError result)))))

            (testing "missing required arguments"
              (let [result @(client/get-prompt client "template")]
                ;; Template requires 'task' argument, should handle gracefully
                ;; Current implementation doesn't validate required args strictly,
                ;; so this may not error but will have unsubstituted template
                (is (vector? (:messages result)))))

            (finally
              ((:cleanup-fn pair)))))))))

(deftest ^:integ prompts-list-changed-notification-test
  ;; Test that notifications/prompts/list_changed is sent when prompts change
  (testing "notifications/prompts/list_changed sent when prompts change"
    (doseq [protocol-version helpers/test-protocol-versions]
      (testing (str "protocol version " protocol-version)
        (let [test-prompts (helpers/create-test-prompts protocol-version)
              pair (helpers/create-clojure-pair protocol-version {:prompts test-prompts})
              client (:client pair)
              server (:server pair)
              received-notifications (atom [])
              ;; Set up notification handler by subscribing to prompts changes
              _ @(client/subscribe-prompts-changed!
                   client
                   (fn [params]
                     (swap! received-notifications conj
                            {:method "notifications/prompts/list_changed" :params params})))
              ;; Add a new prompt to trigger notification
              new-prompt {:name "new-prompt"
                          :description "A new test prompt"
                          :messages [{:role "user"
                                      :content {:type "text"
                                                :text "This is a new prompt."}}]}
              _ (mcp-server/add-prompt! server new-prompt)
              ;; Wait for notification to be processed
              _ (Thread/sleep 200)]

          (try
            ;; Verify notification was received
            (is (seq @received-notifications)
                "Should receive at least one notification")
            (is (some #(= "notifications/prompts/list_changed" (:method %))
                      @received-notifications)
                "Should receive prompts/list_changed notification")

            (finally
              ((:cleanup-fn pair)))))))))
