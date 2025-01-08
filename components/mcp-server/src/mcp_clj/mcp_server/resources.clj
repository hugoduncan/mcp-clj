(ns mcp-clj.mcp-server.resources
  "MCP resource endpoints"
  (:require [mcp-clj.log :as log]))

(defn list-resources
  "List available resources.
   Returns empty list for current implementation."
  [params]
  (log/info :resources/list {:params params})
  {:resources []})
