(ns mcp-clj.compliance-test.client.sampling-test
  "Compliance tests for MCP client sampling capability across implementations."
  (:require
    [clojure.test :refer [deftest is testing]]))

(deftest sampling-create-message-test
  (testing "sampling/createMessage requests LLM completion"
    ;; TODO: Implement polymorphic test
    (is true "Placeholder")))
