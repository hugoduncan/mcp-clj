(ns mcp-clj.json-rpc.protocols
  "Protocols for JSON-RPC server operations")

(defprotocol JsonRpcServer
  "Protocol for JSON-RPC server operations"

  (set-handlers! [server handlers]
    "Set the handler map for the server.
    Handlers should be a map of method name strings to handler functions.")

  (notify-all! [server method params]
    "Send a notification to all active sessions/connections.
    For servers without session concept, this may be a no-op.")

  (stop! [server]
    "Stop the server and clean up resources."))
