#!/usr/bin/env clj
"Test script to verify MCP client configuration structure"

(require '[mcp-clj.mcp-client.core :as client])

;; Test Claude Code MCP server configuration format
(println "Testing Claude Code MCP server configuration...")
(def test-client-1
  (client/create-client
   {:stdio {:command "echo"
            :args ["hello"]
            :env {"TEST_VAR" "test"}}
    :client-info {:name "config-test-client"
                  :title "Configuration Test Client"
                  :version "1.0.0"}
    :capabilities {}
    :protocol-version "2025-06-18"}))

(println "✓ Claude Code format client created successfully")
(println "Client info:" (client/get-client-info test-client-1))
(client/close! test-client-1)

;; Test vector format
(println "\nTesting vector server format...")
(def test-client-2
  (client/create-client
   {:stdio ["echo", "vector"]
    :client-info {:name "vector-test-client"}}))

(println "✓ Vector format client created successfully")
(client/close! test-client-2)

(println "\n🎉 All configuration formats working correctly!")