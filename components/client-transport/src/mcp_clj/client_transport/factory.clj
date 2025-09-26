(ns mcp-clj.client-transport.factory
  "Factory for creating MCP client transports"
  (:require
   [mcp-clj.client-transport.http :as http]
   [mcp-clj.client-transport.stdio :as stdio]))

(defn create-transport
  "Create transport based on configuration.

  Supports:
  - HTTP transport when :url is provided
  - Stdio transport when :server map with :command is provided"
  [{:keys [server url] :as config}]
  (cond
    ;; HTTP transport configuration (URL provided)
    url
    (http/create-transport config)

    ;; Stdio transport configuration
    (and (map? server) (:command server))
    (stdio/create-transport server)

    :else
    (throw
     (ex-info
      "Unsupported server configuration"
      {:server server
       :url    url
       :supported
       "Either :url for HTTP or :server map with :command and :args for stdio"}))))