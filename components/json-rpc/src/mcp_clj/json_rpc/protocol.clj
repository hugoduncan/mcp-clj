(ns mcp-clj.json-rpc.protocol
  "JSON-RPC client protocol for MCP communication"
  (:import
    (java.util.concurrent
      CompletableFuture)))

(defprotocol JSONRPCClient
  "Protocol for JSON-RPC client implementations that handle MCP communication"

  (send-request!
    [client method params timeout-ms]
    "Send a JSON-RPC request with the given method and parameters.
    Returns a CompletableFuture that resolves to the response.")

  (send-notification!
    [client method params]
    "Send a JSON-RPC notification with the given method and parameters.
    Returns a CompletableFuture that resolves when the notification is sent.")

  (close!
    [client]
    "Close the JSON-RPC client and cleanup all resources.
    Should cancel any pending requests and shut down executors.")

  (alive?
    [client]
    "Check if the JSON-RPC client is still alive and operational.
    Returns true if the client can handle new requests, false otherwise."))
