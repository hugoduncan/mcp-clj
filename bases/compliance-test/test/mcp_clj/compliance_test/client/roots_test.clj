(ns mcp-clj.compliance-test.client.roots-test
  "Compliance tests for MCP client roots capability across implementations."
  (:require
   [clojure.test :refer [deftest is testing]]))

(deftest roots-list-test
  (testing "roots/list returns client-provided roots"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest roots-list-changed-notification-test
  (testing "notifications/roots/list_changed is sent when roots change"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))
