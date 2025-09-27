(ns mcp-clj.mcp-client.transport
  "Transport abstraction for MCP client - delegates to protocol implementation"
  (:require
    [mcp-clj.client-transport.protocol :as protocol])
  (:import
    (java.util.concurrent
      CompletableFuture)))

(defn send-request!
  "Send a request through the transport's JSON-RPC client"
  ^CompletableFuture [transport method params timeout-ms]
  (protocol/send-request! transport method params timeout-ms))

(defn send-notification!
  "Send a notification through the transport's JSON-RPC client"
  ^CompletableFuture [transport method params]
  (protocol/send-notification! transport method params))
