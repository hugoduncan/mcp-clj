(ns mcp-clj.json-rpc.protocols
  "Protocols for JSON-RPC client and server operations")

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

(defprotocol JsonRpcServer
  "Protocol for JSON-RPC server operations"

  (set-handlers!
    [server handlers]
    "Set the handler map for the server.
    Handlers should be a map of method name strings to handler functions.")

  (notify!
    [server session-id method params]
    "Send a notification to a specific session/connection.
    For servers without session concept (like STDIO), this sends to the single connection.")

  (notify-all!
    [server method params]
    "Send a notification to all active sessions/connections.
    For servers without session concept, this may be a no-op.")

  (stop!
    [server]
    "Stop the server and clean up resources."))
