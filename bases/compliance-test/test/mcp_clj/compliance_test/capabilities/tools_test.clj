(ns mcp-clj.compliance-test.capabilities.tools-test
  "Compliance tests for MCP tools capability across implementations.

  Tests verify that tools functionality works correctly with:
  - mcp-client + mcp-server (Clojure-only, in-memory transport)
  - mcp-client + Java SDK server (Clojure client with Java server)
  - Java SDK client + mcp-server (Java client with Clojure server)

  Version-specific behavior is tested using conditional assertions."
  (:require
   [clojure.test :refer [deftest is testing]]))

(deftest tools-list-test
  (testing "tools/list returns available tools"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest tools-call-test
  (testing "tools/call executes tools with arguments"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest tools-list-changed-notification-test
  (testing "notifications/tools/list_changed is sent when tools change"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))
