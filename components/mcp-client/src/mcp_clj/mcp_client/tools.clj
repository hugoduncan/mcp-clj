(ns mcp-clj.mcp-client.tools
  "Tool calling implementation for MCP client"
  (:require
   [clojure.data.json :as json]
   [mcp-clj.log :as log]
   [mcp-clj.mcp-client.transport :as transport])
  (:import
   [java.util.concurrent
    CompletableFuture]))

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

  Returns a CompletableFuture that will contain a map with :tools key
  containing vector of tool definitions.  Each tool
  has :name, :description, and :inputSchema."
  ^CompletableFuture [client]
  (try
    (let [transport (:transport client)
          response (transport/send-request!
                    transport
                    "tools/list"
                    {}
                    30000)]
      ;; Transform the response future to handle caching and return tools
      (.thenApply response
                  (reify java.util.function.Function
                    (apply [_ result]
                      (if-let [tools (:tools result)]
                        (do
                          (cache-tools! client tools)
                          {:tools tools})
                        {:tools []})))))
    (catch Exception e
      (log/error :client/list-tools-error {:error (.getMessage e)})
      ;; Return a failed future for immediate exceptions
      (java.util.concurrent.CompletableFuture/failedFuture e))))

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

(defn- parse-response [result tool-name]
  (let [is-error (:isError result false)
        parsed-content (parse-tool-content
                        (:content result))]
    (if is-error
      (do
        (log/error :client/call-tool-error
                   {:tool-name tool-name
                    :content parsed-content})
        ;; Return error map instead of throwing
        {:isError true
         :tool-name tool-name
         :content parsed-content})
      (do
        (log/info :client/call-tool-success
                  {:tool-name tool-name})
        ;; Extract the actual value from the tool response
        ;; For simple text responses, return just the text
        ;; For complex responses, return the content structure
        (if (and (vector? parsed-content)
                 (= 1 (count parsed-content))
                 (let [item (first parsed-content)]
                   (and (map? item)
                        (= "text" (:type item))
                        (string? (:text item))
                        (nil? (:data item)))))
          (:text (first parsed-content))
          parsed-content)))))

(defn call-tool-impl
  "Execute a tool with the given name and arguments.

  Returns a CompletableFuture that will contain the tool result on success.
  For errors, the future contains a map with :isError true.

  For tools that return JSON strings, the JSON is parsed into Clojure data."
  ^CompletableFuture [client tool-name arguments]
  (log/info :client/call-tool-start {:tool-name tool-name})
  (try
    (let [transport (:transport client)
          params    {:name tool-name :arguments (or arguments {})}]
      (transport/send-request!
       transport
       "tools/call"
       params
       30000))
    (catch Exception e
      ;; Return a failed future for immediate exceptions (like transport errors)
      (log/error :client/call-tool-error {:tool-name tool-name
                                          :error     (.getMessage e)
                                          :ex        e})
      (CompletableFuture/failedFuture
       (ex-info
        (str "Tool call failed: " tool-name)
        {:tool-name tool-name
         :error     (.getMessage e)}
        e)))))

(defn available-tools?-impl
  "Check if any tools are available from the server.

  Returns true if tools are available, false otherwise.
  Uses cached tools if available, otherwise queries the server."
  [client]
  (try
    (if-let [cached-tools (get-cached-tools client)]
      (boolean (seq cached-tools))
      (when-let [result-future (list-tools-impl client)]
        (let [result @result-future]
          (boolean (seq (:tools result))))))
    (catch Exception e
      (log/debug :client/available-tools-error {:error (.getMessage e)})
      false)))
