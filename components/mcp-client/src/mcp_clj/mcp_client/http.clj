(ns mcp-clj.mcp-client.http
  "MCP client HTTP transport - connects to existing HTTP server"
  (:require
   [mcp-clj.json-rpc.http-client :as http-client]
   [mcp-clj.log :as log]))

;;; Configuration

(def ^:private request-timeout-ms 30000)

;;; Transport Implementation

(defrecord HttpTransport
           [url
            json-rpc-client]) ; HTTPJSONRPCClient instance

(defn create-transport
  "Create HTTP transport for connecting to MCP server"
  [{:keys [url session-id notification-handler num-threads] :as config}]
  (let [json-rpc-client (http-client/create-http-json-rpc-client
                         {:url url
                          :session-id session-id
                          :notification-handler notification-handler
                          :num-threads num-threads})]
    (->HttpTransport url json-rpc-client)))

(defn close!
  "Close transport and cleanup resources"
  [transport]
  (http-client/close-http-json-rpc-client! (:json-rpc-client transport))
  (log/info :http/transport-closed {:url (:url transport)}))

(defn transport-alive?
  "Check if transport is still alive"
  [transport]
  @(:running (:json-rpc-client transport)))