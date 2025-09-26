(ns mcp-clj.client-transport.factory
  "Factory for creating MCP client transports"
  (:require
   [mcp-clj.client-transport.http :as http]
   [mcp-clj.client-transport.stdio :as stdio]))

(defn create-transport
  "Create transport based on configuration.

  Supports:
  - HTTP transport when :transport {:type :http ...} is provided
  - Stdio transport when :transport {:type :stdio ...} is provided"
  [{:keys [transport] :as config}]
  (if-not transport
    (throw
     (ex-info
      "Missing transport configuration"
      {:config config
       :supported
       ":transport map with :type :http or :stdio"}))

    (let [{:keys [type] :as transport-config} transport
          transport-options (dissoc transport-config :type)]
      (case type
        ;; HTTP transport configuration
        :http
        (http/create-transport transport-options)

        ;; Stdio transport configuration
        :stdio
        (stdio/create-transport transport-options)

        ;; Unsupported transport type
        (throw
         (ex-info
          "Unsupported transport type"
          {:config config
           :transport-type type
           :supported-types [:http :stdio]}))))))
