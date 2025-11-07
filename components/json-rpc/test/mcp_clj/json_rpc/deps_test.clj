(ns mcp-clj.json-rpc.deps-test
  "Test to verify json-rpc component dependencies are correctly declared"
  (:require
    [clojure.test :refer [deftest is testing]]))

(deftest sse-server-dependencies-test
  ;; Test that SSE server and its required dependencies can be loaded.
  ;; This validates that components/json-rpc/deps.edn correctly declares
  ;; all dependencies needed by the sse-server namespace.
  (testing "SSE server namespace"
    (testing "can be loaded with declared dependencies"
      (is (nil? (require 'mcp-clj.json-rpc.sse-server))
          "sse-server namespace should load successfully"))
    (testing "required namespaces are available"
      (is (nil? (require 'mcp-clj.http))
          "mcp-clj.http should be available (required by sse-server:4)")
      (is (nil? (require 'mcp-clj.http-server.adapter))
          "mcp-clj.http-server.adapter should be available (required by sse-server:5)"))))
