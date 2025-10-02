(ns mcp-clj.compliance-test.capabilities.prompts-test
  "Compliance tests for MCP prompts capability across implementations."
  (:require
   [clojure.test :refer [deftest is testing]]))

(deftest prompts-list-test
  (testing "prompts/list returns available prompts"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest prompts-get-test
  (testing "prompts/get retrieves prompt with arguments"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest prompts-list-changed-notification-test
  (testing "notifications/prompts/list_changed is sent when prompts change"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))
