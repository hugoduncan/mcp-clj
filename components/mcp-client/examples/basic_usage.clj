(ns mcp-clj.mcp-client.examples.basic-usage
  "Basic usage example of MCP client"
  (:require
   [mcp-clj.mcp-client.core :as client]))

(comment
  ;; Example usage of MCP client

  ;; Create a client with Claude Code MCP server configuration
  ;; This matches the format used in Claude Code's .mcp.json
  (def client
    (client/create-client
     {:transport {:type :stdio
                  :command "cat"
                  :args []
                  :env {}} ; cat will echo JSON for testing
      :client-info {:name "example-client"
                    :version "1.0.0"}
      :capabilities {}}))

  ;; Check initial state
  (client/get-client-info client)
  ;; => {:state :disconnected, :protocol-version "2025-06-18", ...}

  ;; Client automatically initializes on creation
  ;; (this will likely fail with "cat" but shows the API)

  ;; Check if ready (will be false with cat example)  
  (client/client-ready? client)
  ;; => false

  ;; Get detailed info
  (client/get-client-info client)

  ;; Clean up
  (client/close! client)

  ;;; Example with a real MCP server
  ;;; This is how you'd connect to an actual MCP server:

  (def real-client
    (client/create-client
     {:transport {:type :stdio
                  :command "python"
                  :args ["-m", "mcp_server", "--stdio"]
                  :env {"PYTHONPATH" "/path/to/server"}}
      :client-info {:name "my-clojure-client"
                    :title "My Clojure MCP Client"
                    :version "1.0.0"}
      :capabilities {}}))

  ;; Wait for automatic initialization to complete
  (client/wait-for-ready real-client 5000) ; 5 second timeout

  ;; Now client is ready for MCP operations
  ;; (Future: add tool calling, prompt requests, etc.)

  (client/close! real-client))