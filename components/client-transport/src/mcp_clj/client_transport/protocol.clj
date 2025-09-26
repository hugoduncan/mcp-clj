(ns mcp-clj.client-transport.protocol
  "Transport protocol abstractions for MCP clients")

(defprotocol Transport
  "Protocol for MCP client transport implementations"
  (send-request! [transport method params timeout-ms]
    "Send a request through the transport.
    Returns a CompletableFuture that resolves to the response.")

  (send-notification! [transport method params]
    "Send a notification through the transport.
    Returns a CompletableFuture that resolves when sent.")

  (close! [transport]
    "Close the transport and cleanup resources.")

  (alive? [transport]
    "Check if the transport is still alive and operational.")

  (get-json-rpc-client [transport]
    "Get the underlying JSON-RPC client."))
