(ns mcp-clj.mcp-server.prompts
  "MCP prompt endpoints"
  (:require [mcp-clj.log :as log]))

(defn list-prompts
  "List available prompts.
   Returns empty list for current implementation."
  [params]
  (log/info :prompts/list {:params params})
  {:prompts []})
