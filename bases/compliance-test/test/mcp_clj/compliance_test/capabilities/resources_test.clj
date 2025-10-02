(ns mcp-clj.compliance-test.capabilities.resources-test
  "Compliance tests for MCP resources capability across implementations."
  (:require
   [clojure.test :refer [deftest is testing]]))

(deftest resources-list-test
  (testing "resources/list returns available resources"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest resources-read-test
  (testing "resources/read retrieves resource contents"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest resources-templates-list-test
  (testing "resources/templates/list returns resource templates"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest resources-list-changed-notification-test
  (testing "notifications/resources/list_changed is sent when resources change"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest resources-subscribe-test
  (testing "resources/subscribe and notifications/resources/updated"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))
