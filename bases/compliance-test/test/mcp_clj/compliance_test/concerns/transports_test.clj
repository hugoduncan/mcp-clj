(ns mcp-clj.compliance-test.concerns.transports-test
  "Compliance tests for MCP transport implementations."
  (:require
   [clojure.test :refer [deftest is testing]]))

(deftest in-memory-transport-test
  (testing "in-memory transport supports bidirectional communication"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest stdio-transport-test
  (testing "stdio transport communicates over stdin/stdout"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))

(deftest http-sse-transport-test
  (testing "HTTP/SSE transport supports server-sent events"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))
