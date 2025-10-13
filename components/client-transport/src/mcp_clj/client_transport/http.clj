(ns mcp-clj.client-transport.http
  "HTTP transport implementation for MCP client"
  (:require
    [mcp-clj.client-transport.protocol :as transport-protocol]
    [mcp-clj.json-rpc.http-client :as http-client]
    [mcp-clj.json-rpc.protocols :as json-rpc-protocol]
    [mcp-clj.log :as log]))

;; Transport Implementation

(defrecord HttpTransport
  [url
   json-rpc-client] ; HTTPJSONRPCClient instance

  transport-protocol/Transport

  (send-request!
    [_ method params timeout-ms]
    (json-rpc-protocol/send-request! json-rpc-client method params timeout-ms))


  (send-notification!
    [_ method params]
    (json-rpc-protocol/send-notification! json-rpc-client method params))


  (close!
    [_]
    (json-rpc-protocol/close! json-rpc-client)
    (log/info :http/transport-closed {:url url}))


  (alive?
    [_]
    (json-rpc-protocol/alive? json-rpc-client))


  (get-json-rpc-client
    [_]
    json-rpc-client))

(defn create-transport
  "Create HTTP transport for connecting to MCP server"
  [{:keys [url session-id notification-handler num-threads]}]
  (let [json-rpc-client (http-client/create-http-json-rpc-client
                          {:url url
                           :session-id session-id
                           :notification-handler notification-handler
                           :num-threads num-threads})]
    (->HttpTransport url json-rpc-client)))
