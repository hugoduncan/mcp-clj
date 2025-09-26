(ns mcp-clj.client-transport.factory
  "Factory for creating MCP client transports"
  (:require
   [mcp-clj.client-transport.http :as http]
   [mcp-clj.client-transport.stdio :as stdio]))

(defn create-transport
  "Create transport based on configuration.

  Supports:
  - HTTP transport when :http map is provided
  - Stdio transport when :stdio map is provided"
  [{:keys [http stdio] :as config}]
  (cond
    ;; HTTP transport configuration
    http
    (http/create-transport http)

    ;; Stdio transport configuration  
    stdio
    (stdio/create-transport stdio)

    :else
    (throw
     (ex-info
      "Unsupported transport configuration"
      {:config config
       :supported
       "Either :http map with :url or :stdio map with :command"}))))