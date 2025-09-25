(ns mcp-clj.mcp-client.transport
  "Transport abstraction for MCP client"
  (:require
   [mcp-clj.json-rpc.http-client :as http-client]
   [mcp-clj.json-rpc.stdio-client :as stdio-client]))

(defn send-request!
  "Send a request through the transport's JSON-RPC client"
  [transport method params timeout-ms]
  (let [json-rpc-client (:json-rpc-client transport)]
    (cond
      ;; HTTP client
      (instance? mcp_clj.json_rpc.http_client.HTTPJSONRPCClient json-rpc-client)
      (http-client/send-request! json-rpc-client method params timeout-ms)

      ;; Stdio client
      (instance? mcp_clj.json_rpc.stdio_client.JSONRPClient json-rpc-client)
      (stdio-client/send-request! json-rpc-client method params timeout-ms)

      :else
      (throw (ex-info "Unknown transport type" {:client json-rpc-client})))))

(defn send-notification!
  "Send a notification through the transport's JSON-RPC client"
  [transport method params]
  (let [json-rpc-client (:json-rpc-client transport)]
    (cond
      ;; HTTP client
      (instance? mcp_clj.json_rpc.http_client.HTTPJSONRPCClient json-rpc-client)
      (http-client/send-notification! json-rpc-client method params)

      ;; Stdio client
      (instance? mcp_clj.json_rpc.stdio_client.JSONRPClient json-rpc-client)
      (stdio-client/send-notification! json-rpc-client method params)

      :else
      (throw (ex-info "Unknown transport type" {:client json-rpc-client})))))
