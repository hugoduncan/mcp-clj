(ns mcp-clj.json-rpc-server.deps-test
  "Test to verify json-rpc-server component dependencies are correctly declared"
  (:require
    [clojure.test :refer [deftest is testing]]))

(deftest sse-server-dependencies-test
  ;; Test that SSE server and its required dependencies can be loaded.
  ;; This validates that components/json-rpc-server/deps.edn correctly declares
  ;; all dependencies needed by the sse namespace.
  (testing "SSE server namespace"
    (testing "can be loaded with declared dependencies"
      (is (nil? (require 'mcp-clj.json-rpc-server.sse))
          "sse namespace should load successfully"))
    (testing "required namespaces are available"
      (is (nil? (require 'mcp-clj.http))
          "mcp-clj.http should be available (required by sse)")
      (is (nil? (require 'mcp-clj.http-server.adapter))
          "mcp-clj.http-server.adapter should be available (required by sse)"))))
