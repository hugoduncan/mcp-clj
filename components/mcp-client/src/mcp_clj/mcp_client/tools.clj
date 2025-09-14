(ns mcp-clj.mcp-client.tools
  "Tool calling implementation for MCP client"
  (:require
   [clojure.data.json :as json]
   [mcp-clj.log :as log]
   [mcp-clj.mcp-client.transport :as transport]))

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
          json-rpc-client (:json-rpc-client transport)
          ;; Use the core namespace's send-request! function
          response (transport/send-request!
                    transport
                    "tools/list"
                    {}
                    30000)]
      (when-let [tools (:tools @response)]
        (cache-tools! client tools)
        (log/info :client/list-tools-success {:count (count tools)})
        {:tools tools}))
    (catch Exception e
      (log/error :client/list-tools-error {:error (.getMessage e)})
      (throw e))))

(defn- parse-tool-content
  "Parse tool content, converting JSON strings to Clojure data structures.

  Tools may return content as JSON strings in text fields. This function
  attempts to parse such JSON strings into proper Clojure data structures
  while preserving non-JSON text content as-is."
  [content]
  (if (vector? content)
    (mapv (fn [item]
            (if (and (map? item)
                     (= "text" (:type item))
                     (string? (:text item)))
              (try
                ;; Try to parse as JSON
                (let [parsed (json/read-str (:text item) :key-fn keyword)]
                  ;; If it parses successfully and looks like structured data,
                  ;; replace the text content with the parsed data
                  (if (or (map? parsed) (vector? parsed))
                    (assoc item :data parsed :text nil)
                    item)) ; Keep as text if it's just a simple value
                (catch Exception _
                  ;; Not valid JSON, keep as text
                  item))
              ;; Non-text items or malformed items pass through unchanged
              item))
          content)
    ;; If content is not a vector, return as-is
    content))

(defn call-tool-impl
  "Execute a tool with the given name and arguments.

  Returns the parsed content directly on success.
  Throws ex-info on error with the error content as the message.

  For tools that return JSON strings, the JSON is parsed into Clojure data."
  [client tool-name arguments]
  (log/info :client/call-tool-start {:tool-name tool-name})
  (try
    (let [transport (:transport client)
          json-rpc-client (:json-rpc-client transport)
          params {:name tool-name :arguments (or arguments {})}
          ;; Use the core namespace's send-request! function
          response (transport/send-request!
                    transport
                    "tools/call"
                    params
                    30000)
          result (deref response 30000 ::timeout)]

      (when (= result ::timeout)
        (throw (ex-info (str "Tool call timed out: " tool-name)
                        {:tool-name tool-name
                         :timeout 30000})))

      (let [is-error (:isError result false)
            parsed-content (parse-tool-content (:content result))]
        (if is-error
          (do
            (log/error :client/call-tool-error {:tool-name tool-name
                                                :content parsed-content})
            (throw (ex-info (str "Tool execution failed: " tool-name)
                            {:tool-name tool-name
                             :content parsed-content})))
          (do
            (log/info :client/call-tool-success {:tool-name tool-name})
            parsed-content))))
    (catch Exception e
      (if (instance? clojure.lang.ExceptionInfo e)
        (throw e) ; Re-throw tool errors
        (do
          (log/error :client/call-tool-error {:tool-name tool-name
                                              :error (.getMessage e)
                                              :ex e})
          (throw (ex-info (str "Tool call failed: " tool-name)
                          {:tool-name tool-name
                           :error (.getMessage e)}
                          e)))))))

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
