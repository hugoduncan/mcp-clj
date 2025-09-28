(ns mcp-clj.versions
  "MCP protocol version management utilities")

(def supported-versions
  "Supported MCP protocol versions in descending order (newest first)"
  ["2025-06-18" "2025-03-26" "2024-11-05"])

(defn get-latest-version
  "Get the latest supported protocol version"
  []
  (first supported-versions))

(defn supported?
  "Check if a protocol version is supported"
  [version]
  (boolean (some #{version} supported-versions)))