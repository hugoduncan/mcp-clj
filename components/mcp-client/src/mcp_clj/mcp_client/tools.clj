(ns mcp-clj.mcp-client.tools
  "Tool calling implementation for MCP client"
  (:require
   [mcp-clj.log :as log]
   [mcp-clj.mcp-client.stdio :as stdio]))

(defrecord ToolResult
           [content isError]
  Object
  (toString [_]
    (str "ToolResult{content=" content ", isError=" isError "}")))

(defn- get-tools-cache
  "Get or create tools cache in client session"
  [client]
  (let [session-atom (:session client)]
    (or (:tools-cache @session-atom)
        (let [cache (atom nil)]
          (swap! session-atom assoc :tools-cache cache)
          cache))))

(defn- cache-tools!
  "Cache discovered tools in client session"
  [client tools]
  (let [cache (get-tools-cache client)]
    (reset! cache tools)
    tools))

(defn- get-cached-tools
  "Get cached tools from client session"
  [client]
  @(get-tools-cache client))

(defn list-tools-impl
  "Discover available tools from the server.
  
  Returns a map with :tools key containing vector of tool definitions.
  Each tool has :name, :description, and :inputSchema."
  [client]
  (log/info :client/list-tools-start)
  (try
    (let [transport (:transport client)
          response (stdio/send-request! transport "tools/list" {})]
      (when-let [tools (:tools @response)]
        (cache-tools! client tools)
        (log/info :client/list-tools-success {:count (count tools)})
        {:tools tools}))
    (catch Exception e
      (log/error :client/list-tools-error {:error (.getMessage e)})
      (throw e))))

(defn call-tool-impl
  "Execute a tool with the given name and arguments.
  
  Returns a ToolResult record with :content and :isError fields.
  Content can be text, images, audio, or resource references."
  [client tool-name arguments]
  (log/info :client/call-tool-start {:tool-name tool-name})
  (try
    (let [transport (:transport client)
          params {:name tool-name :arguments (or arguments {})}
          response (stdio/send-request! transport "tools/call" params)
          result @response
          tool-result (->ToolResult
                       (:content result)
                       (:isError result false))]
      (log/info :client/call-tool-success {:tool-name tool-name
                                           :is-error (:isError tool-result)})
      tool-result)
    (catch Exception e
      (log/error :client/call-tool-error {:tool-name tool-name
                                          :error (.getMessage e)})
      (throw e))))

(defn available-tools?-impl
  "Check if any tools are available from the server.
  
  Returns true if tools are available, false otherwise.
  Uses cached tools if available, otherwise queries the server."
  [client]
  (try
    (if-let [cached-tools (get-cached-tools client)]
      (boolean (seq cached-tools))
      (when-let [result (list-tools-impl client)]
        (boolean (seq (:tools result)))))
    (catch Exception e
      (log/debug :client/available-tools-error {:error (.getMessage e)})
      false)))