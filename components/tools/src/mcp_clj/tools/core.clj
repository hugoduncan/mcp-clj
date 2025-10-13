(ns mcp-clj.tools.core
  "Tool definitions and validation for MCP servers"
  (:require
    [mcp-clj.tools.clj-eval :as clj-eval]
    [mcp-clj.tools.ls :as ls]))

(defn valid-tool?
  "Validate a tool definition"
  [{:keys [name description inputSchema implementation] :as _tool}]
  (and (string? name)
       (string? description)
       (map? inputSchema)
       (ifn? implementation)))

(defn tool-definition
  [tool]
  (dissoc tool :implementation))

(def default-tools
  "Default set of built-in tools"
  {"clj-eval" clj-eval/clj-eval-tool
   "ls" ls/ls-tool})
