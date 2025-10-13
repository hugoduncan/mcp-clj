(ns mcp-clj.java-sdk.interop
  "Java SDK interop wrapper for cross-implementation testing.

  Provides a minimal Clojure API to create and interact with MCP
  clients and servers from the official Java SDK."
  (:require
    [clojure.string :as str]
    [mcp-clj.log :as log])
  (:import
    (com.fasterxml.jackson.databind
      ObjectMapper)
    ;; Types
    (io.modelcontextprotocol.client
      McpAsyncClient
      McpClient
      McpClient$AsyncSpec
      McpClient$SyncSpec
      McpSyncClient)
    (io.modelcontextprotocol.client.transport
      ServerParameters
      StdioClientTransport
      WebClientStreamableHttpTransport
      WebFluxSseClientTransport)
    (io.modelcontextprotocol.server
      McpAsyncServer
      McpServer
      McpServer$AsyncSpecification
      McpServer$SingleSessionSyncSpecification
      McpServer$StreamableSyncSpecification
      McpServerFeatures$AsyncResourceSpecification
      McpServerFeatures$AsyncToolSpecification$Builder
      McpServerFeatures$SyncResourceSpecification
      McpServerFeatures$SyncToolSpecification
      McpServerFeatures$SyncToolSpecification$Builder
      McpSyncServer)
    (io.modelcontextprotocol.server.transport
      StdioServerTransportProvider
      WebFluxSseServerTransportProvider
      WebFluxStatelessServerTransport
      WebFluxStreamableServerTransportProvider)
    (io.modelcontextprotocol.spec
      McpSchema
      McpSchema$AudioContent
      McpSchema$BlobResourceContents
      McpSchema$CallToolRequest
      McpSchema$CallToolResult
      McpSchema$Content
      McpSchema$EmbeddedResource
      McpSchema$ImageContent
      McpSchema$InitializeResult
      McpSchema$ListResourcesResult
      McpSchema$ListToolsResult
      McpSchema$LoggingLevel
      McpSchema$LoggingMessageNotification
      McpSchema$ReadResourceRequest
      McpSchema$ReadResourceResult
      McpSchema$Resource
      McpSchema$ResourceContents
      McpSchema$ResourceLink
      McpSchema$ResourcesUpdatedNotification
      McpSchema$ServerCapabilities
      McpSchema$ServerCapabilities$Builder
      McpSchema$ServerCapabilities$LoggingCapabilities
      McpSchema$ServerCapabilities$PromptCapabilities
      McpSchema$ServerCapabilities$ResourceCapabilities
      McpSchema$ServerCapabilities$ToolCapabilities
      McpSchema$SubscribeRequest
      McpSchema$TextContent
      McpSchema$TextResourceContents
      McpSchema$Tool
      McpSchema$UnsubscribeRequest
      McpServerTransportProvider)
    (java.lang
      AutoCloseable)
    (java.util
      List
      Map)
    (java.util.concurrent
      CompletableFuture
      TimeUnit)
    (org.springframework.web.reactive.function.client
      WebClient
      WebClient$Builder)
    (reactor.core.publisher
      Mono)))

;; Utility functions

;; Records for Java SDK client and server wrappers

(defrecord JavaSdkClient
  [^McpClient client transport async? notification-handlers]

  AutoCloseable

  (close
    [_this]
    (log/info :java-sdk/closing-client)
    (try
      (if async?
        (.closeGracefully ^McpAsyncClient client)
        (.closeGracefully ^McpSyncClient client))
      (.close ^StdioClientTransport transport)
      (catch Exception e
        (log/warn :java-sdk/close-error {:error e})))))

(defrecord JavaSdkServer
  [^McpServer server name version async?]

  AutoCloseable

  (close
    [_this]
    (log/info :java-sdk/closing-server)
    (try
      (if async?
        (.closeGracefully ^McpAsyncServer server)
        (.closeGracefully ^McpSyncServer server))
      (catch Exception e
        (log/warn :java-sdk/server-close-error {:error e})))))

(defn- clj->java-map
  "Convert Clojure map to Java Map, recursively converting keywords to strings"
  ^Map [m]
  (cond
    (map? m)
    (java.util.HashMap.
      ^Map (into {} (map (fn [[k v]]
                           [(if (keyword? k) (name k) k)
                            (clj->java-map v)])
                         m)))

    (sequential? m)
    (java.util.ArrayList. ^java.util.Collection (map clj->java-map m))

    (keyword? m)
    (name m)

    :else
    m))

(defn- clj-result->java-result
  "Convert Clojure tool result to Java CallToolResult"
  [clj-result]
  (let [builder (McpSchema$CallToolResult/builder)]
    (when (:isError clj-result)
      (.isError builder (:isError clj-result)))

    ;; Add content items
    (when-let [content (:content clj-result)]
      (doseq [item content]
        (case (:type item)
          "text" (.addTextContent builder (:text item))
          ;; Default to text for other types
          (.addTextContent builder (str item)))))

    (.build builder)))

(defn- await-future
  "Block and wait for CompletableFuture or Mono with timeout"
  [future-or-mono timeout-seconds]
  (try
    (let [^CompletableFuture future (if (instance? CompletableFuture future-or-mono)
                                      future-or-mono
                                      ;; Convert Mono to CompletableFuture
                                      (.toFuture ^reactor.core.publisher.Mono future-or-mono))]
      (.get future timeout-seconds TimeUnit/SECONDS))
    (catch Exception e
      (log/error :java-sdk/future-error {:error e})
      (throw e))))

(defn- java-content->clj
  [^McpSchema$Content content]
  (let [m (.meta content)]
    (cond->
      (condp = (.type content)
        "text"
        (let [^McpSchema$TextContent text-content content]
          {:type "text"
           :text (.text text-content)})

        "image"
        (let [^McpSchema$ImageContent image-content content]
          {:data (.data image-content)
           :mime-type (.mimeType image-content)})

        "audio"
        (let [^McpSchema$AudioContent audio-content content]
          {:data (.data audio-content)
           :mime-type (.mimeType audio-content)})

        "resource"
        (let [^McpSchema$EmbeddedResource embedded-resource content
              resource (.resource embedded-resource)
              base {:uri (.uri resource)
                    :mime-type (.mimeType resource)
                    :meta (into {} (.meta resource))}]
          (cond
            (instance? McpSchema$TextResourceContents resource)
            (assoc base
                   :text (.text ^McpSchema$TextResourceContents resource))
            (instance? McpSchema$BlobResourceContents resource)
            (assoc base
                   :blob (.blob ^McpSchema$BlobResourceContents resource))))

        "resource_link"
        (let [^McpSchema$ResourceLink link content]
          {:name (.name link)
           :title (.title link)
           :uri (.uri link)
           :descritpion (.description link)
           :mime-type (.mimeType link)
           :size (.size link)})

        {:type "text"
         :text (str content)})
      (and m (seq m))
      (assoc :meta m))))

(defn- java-tool-result->clj
  "Convert Java tool call result to Clojure map"
  [^McpSchema$CallToolResult result]
  (try
    {:content (mapv java-content->clj (.content result))
     :isError (.isError result)}
    (catch Exception e
      (log/error :java-sdk/result-conversion-error {:error e})
      {:content [{:type "text" :text "Error converting result"}]
       :isError true})))

(defn- java-tools-result->clj
  "Convert Java tools list result to Clojure map"
  [^McpSchema$ListToolsResult result]
  (try
    {:tools (mapv (fn [^McpSchema$Tool tool]
                    {:name (.name tool)
                     :title (.title tool)
                     :description (.description tool)
                     :input-schema (when-let [schema (.inputSchema tool)]
                                     (try
                                       ;; Convert JsonSchema to string then parse back to avoid type issues
                                       (-> schema .toString)
                                       (catch Exception e
                                         (str schema))))
                     :output-schema (when-let [schema (.outputSchema tool)]
                                      (try
                                        (-> schema .toString)
                                        (catch Exception e
                                          (str schema))))
                     :meta (try
                             (when-let [meta-obj (.meta tool)]
                               (str meta-obj))
                             (catch Exception e
                               nil))})
                  (.tools result))}
    (catch Exception e
      (log/error :java-sdk/tools-result-conversion-error {:error e})
      {:tools []})))

(defn- java-init-result->clj
  "Convert Java SDK initialization result to Clojure map"
  [^McpSchema$InitializeResult init-result]
  (when init-result
    (let [server-info (.serverInfo init-result)
          capabilities (.capabilities init-result)]
      {:serverInfo {:name (when server-info (.name server-info))
                    :version (when server-info (.version server-info))
                    :title (when server-info (.title server-info))}
       :protocolVersion (.protocolVersion init-result)
       :capabilities (cond-> {}
                       (and capabilities (.tools capabilities))
                       (assoc :tools
                              (let [^McpSchema$ServerCapabilities$ToolCapabilities tools-cap (.tools capabilities)]
                                {:listChanged (.listChanged tools-cap)}))

                       (and capabilities (.resources capabilities))
                       (assoc :resources
                              (let [^McpSchema$ServerCapabilities$ResourceCapabilities resources-cap (.resources capabilities)]
                                {:listChanged (.listChanged resources-cap)
                                 :subscribe (.subscribe resources-cap)}))

                       (and capabilities (.prompts capabilities))
                       (assoc :prompts
                              (let [^McpSchema$ServerCapabilities$PromptCapabilities prompts-cap (.prompts capabilities)]
                                {:listChanged (.listChanged prompts-cap)}))

                       (and capabilities (.logging capabilities))
                       (assoc :logging {}))
       :instructions (.instructions init-result)})))

;; Client API

(defn create-java-client
  "Create a Java SDK MCP client.

  Options:
  - :transport - Transport provider object (required)
  - :timeout - Request timeout in seconds (default 30)
  - :async? - Whether to create async client (default true)
  - :logging-handler - Optional callback function for logging notifications
                       Receives map with :level, :data, and optional :logger keys

  NOTE: Java SDK 0.11.2 does not support notifications/resources/updated at the
  client level. Resource update notifications cannot be received.

  Returns a JavaSdkClient record that implements AutoCloseable."
  [{:keys [transport timeout async? logging-handler resource-update-handler]
    :or {timeout 30 async? true}}]
  (when resource-update-handler
    (log/warn :java-sdk/unsupported-feature
              {:feature :resource-update-handler
               :message "Java SDK 0.11.2 does not support resource update notifications - handler will be ignored"}))
  (let [builder (if async?
                  (McpClient/async transport)
                  (McpClient/sync transport))

        ;; Add logging consumer if provided
        builder (if logging-handler
                  (if async?
                    (.loggingConsumer
                      ^McpClient$AsyncSpec builder
                      (reify java.util.function.Function
                        (apply
                          [_ notification]
                          (Mono/fromRunnable
                            (reify java.lang.Runnable
                              (run
                                [_]
                                (try
                                  (let [^McpSchema$LoggingMessageNotification
                                        notification notification
                                        level (.level notification)
                                        logger (.logger notification)
                                        data (.data notification)
                                        clj-notification
                                        (cond-> {:level (-> level .name str/lower-case keyword)
                                                 :data data}
                                          logger (assoc :logger logger))]
                                    (logging-handler clj-notification))
                                  (catch Exception e
                                    (log/error :java-sdk/logging-handler-error
                                               {:error e
                                                :message (.getMessage e)
                                                :stack-trace (with-out-str (.printStackTrace e))})))))))))
                    (.loggingConsumer
                      ^McpClient$SyncSpec builder
                      (reify java.util.function.Consumer
                        (accept
                          [_ notification]
                          (log/info :java-sdk/logging-notification-received
                                    {:notification-type (type notification)})
                          (try
                            (let [^McpSchema$LoggingMessageNotification
                                  notification notification
                                  level (-> notification .level .name str/lower-case keyword)
                                  logger (.logger notification)
                                  data (.data notification)
                                  clj-notification
                                  (cond->
                                    {:level level
                                     :data data}
                                    logger (assoc :logger logger))]
                              (log/info :java-sdk/calling-handler
                                        {:notification clj-notification})
                              (logging-handler clj-notification))
                            (catch Exception e
                              (log/error :java-sdk/logging-handler-error
                                         {:error e
                                          :message (.getMessage e)
                                          :stack-trace (with-out-str (.printStackTrace e))})))))))
                  builder)

        ;; Add resource update consumer if provided

        client (if async?
                 (.build ^McpClient$AsyncSpec builder)
                 (.build ^McpClient$SyncSpec builder))]

    (->JavaSdkClient client transport async? {:logging logging-handler})))

(defn create-stdio-client-transport
  "Create a stdio transport provider for the client.

  Args can be:
  - A string command (e.g., \"node server.js\")
  - A map with :command and optional :args
     (e.g., {:command \"node\" :args [\"server.js\"]})"
  [command-spec]
  (let [[cmd args] (cond
                     (string? command-spec)
                     [command-spec nil]

                     (map? command-spec)
                     [(:command command-spec) (:args command-spec)]

                     :else
                     (throw
                       (ex-info
                         "Invalid command spec"
                         {:command-spec command-spec})))

        ;; Build ServerParameters using the builder pattern
        builder (ServerParameters/builder cmd)
        server-params (if args
                        (-> builder
                            (.args
                              ^"[Ljava.lang.String;" (into-array String args))
                            (.build))
                        (.build builder))]

    (StdioClientTransport. server-params (ObjectMapper.))))

(defn create-stdio-server-transport
  "Create a stdio transport provider for the server."
  ^StdioServerTransportProvider []
  (StdioServerTransportProvider. (ObjectMapper.)))

(defn create-http-client-transport
  "Create an HTTP transport for the client using Spring WebFlux.

  Options:
  - :url - Base URL for the MCP server (required)
  - :use-sse - Whether to use Server-Sent Events (default false)
  - :open-connection-on-startup - Whether to open connection immediately (default false)
  - :resumable-streams - Whether to enable resumable streams (default false)

  Returns an HTTP transport provider object."
  [{:keys [url use-sse open-connection-on-startup resumable-streams]
    :or {use-sse false open-connection-on-startup false resumable-streams false}}]
  (when-not url
    (throw (ex-info "URL required for HTTP client transport" {:options {:url url}})))
  (if use-sse
    ;; Use SSE transport for streaming
    (let [web-client-builder (-> (WebClient/builder)
                                 (.baseUrl url))]
      (WebFluxSseClientTransport. web-client-builder (ObjectMapper.)))
    ;; Use regular HTTP transport
    (let [web-client-builder (WebClient/builder)
          builder (-> (WebClientStreamableHttpTransport/builder web-client-builder)
                      (.endpoint url)
                      (.objectMapper (ObjectMapper.))
                      (.openConnectionOnStartup open-connection-on-startup)
                      (.resumableStreams resumable-streams))]
      (.build builder))))

(defn create-http-server-transport
  "Create an HTTP transport provider for the server using Spring WebFlux.

  Options:
  - :port - Port to listen on (default 8080)
  - :use-sse - Whether to use Server-Sent Events (default false)
  - :stateless - Whether to use stateless transport (default false)
  - :endpoint - Message endpoint path (default '/message')

  Returns an HTTP server transport provider object."
  [{:keys [port use-sse stateless endpoint]
    :or {port 8080 use-sse false stateless false endpoint "/message"}}]
  (cond
    stateless
    ;; Note: WebFluxStatelessServerTransport might need different initialization
    ;; This is a placeholder - actual implementation depends on the class structure
    (throw (ex-info "Stateless HTTP server transport not yet implemented" {}))

    use-sse
    ;; Use SSE transport - takes ObjectMapper and endpoint path
    (WebFluxSseServerTransportProvider. (ObjectMapper.) endpoint)

    :else
    ;; Use streamable HTTP transport with builder pattern
    (-> (WebFluxStreamableServerTransportProvider/builder)
        (.objectMapper (ObjectMapper.))
        (.messageEndpoint endpoint)
        (.build))))

(defn initialize-client
  "Initialize the Java SDK client connection.

  Returns the initialization result as a Clojure map."
  [^JavaSdkClient client-record]
  (log/info :java-sdk/initializing-client)
  (let [java-result (if (:async? client-record)
                      (await-future (.initialize ^McpAsyncClient (:client client-record)) 30)
                      (.initialize ^McpSyncClient (:client client-record)))]
    (java-init-result->clj java-result)))

(defn list-tools
  "List available tools from the server.

  Returns a CompletableFuture that will contain tools list converted to
  Clojure map."
  [^JavaSdkClient client-record]
  (log/info :java-sdk/listing-tools)
  (if (:async? client-record)
    ;; For async client, the result is already a CompletableFuture/Mono
    (let [mono (.listTools ^McpAsyncClient (:client client-record))
          future (.toFuture mono)]
      ;; Convert Mono to CompletableFuture if needed and transform result
      (-> future
          (.thenApply (reify java.util.function.Function
                        (apply
                          [_ result]
                          (java-tools-result->clj result))))))
    ;; For sync client, wrap the synchronous call in a CompletableFuture
    (CompletableFuture/supplyAsync
      (reify java.util.function.Supplier
        (get
          [_]
          (let [result (.listTools ^McpSyncClient (:client client-record))]
            (java-tools-result->clj result)))))))

(defn call-tool
  "Call a tool through the Java SDK client.

  Args:
  - client-record: JavaSdkClient record
  - tool-name: Name of the tool to call
  - arguments: Map of arguments for the tool

  Returns a CompletableFuture that will contain tool result converted to
  Clojure map."
  [^JavaSdkClient client-record ^String tool-name arguments]
  (log/info :java-sdk/calling-tool {:tool tool-name :args arguments})
  (let [^McpSchema$CallToolRequest request
        (-> (McpSchema$CallToolRequest/builder)
            (.name tool-name)
            (.arguments (clj->java-map arguments))
            (.build))]
    (if (:async? client-record)
      ;; For async client, the result is already a CompletableFuture/Mono
      (let [^McpAsyncClient client (:client client-record)
            future-or-mono (.callTool client request)
            future (.toFuture future-or-mono)]
        (-> future
            (.thenApply (reify java.util.function.Function
                          (apply
                            [_ result]
                            (java-tool-result->clj result))))))
      ;; For sync client, wrap the synchronous call in a CompletableFuture
      (CompletableFuture/supplyAsync
        (reify java.util.function.Supplier
          (get
            [_]
            (let [^McpSyncClient client (:client client-record)
                  result (.callTool client request)]
              (java-tool-result->clj result))))))))

(defn close-client
  "Close the Java SDK client."
  [^JavaSdkClient client-record]
  (.close client-record))

(defn set-logging-level
  "Set the logging level on the server via the Java SDK client.

  Parameters:
  - client-record - JavaSdkClient record
  - level - Log level keyword (:debug, :info, :notice, :warning, :error, :critical, :alert, :emergency)

  Returns a CompletableFuture that completes when the level is set."
  [^JavaSdkClient client-record level]
  (log/info :java-sdk/setting-log-level {:level level})
  (let [level-str (name level)
        java-level (McpSchema$LoggingLevel/valueOf (str/upper-case level-str))]
    (if (:async? client-record)
      (let [^McpAsyncClient client (:client client-record)
            mono (.setLoggingLevel client java-level)]
        (.toFuture mono))
      (CompletableFuture/supplyAsync
        (reify java.util.function.Supplier
          (get
            [_]
            (let [^McpSyncClient client (:client client-record)]
              (.setLoggingLevel
                client
                (McpSchema$LoggingLevel/valueOf (str/upper-case level-str)))
              nil)))))))

(defn subscribe-resource
  "Subscribe to a resource via the Java SDK client.

  Parameters:
  - client-record - JavaSdkClient record
  - uri - Resource URI string to subscribe to

  Returns a CompletableFuture that completes when the subscription is established.
  The future will complete exceptionally if the resource doesn't exist or subscription fails."
  [^JavaSdkClient client-record ^String uri]
  (log/info :java-sdk/subscribing-to-resource {:uri uri})
  (let [request (McpSchema$SubscribeRequest. uri)]
    (if (:async? client-record)
      (let [^McpAsyncClient client (:client client-record)
            mono (.subscribeResource client request)]
        (.toFuture mono))
      (CompletableFuture/supplyAsync
        (reify java.util.function.Supplier
          (get
            [_]
            (let [^McpSyncClient client (:client client-record)]
              (.subscribeResource client request)
              nil)))))))

(defn unsubscribe-resource
  "Unsubscribe from a resource via the Java SDK client.

  Parameters:
  - client-record - JavaSdkClient record
  - uri - Resource URI string to unsubscribe from

  Returns a CompletableFuture that completes when the unsubscription is processed."
  [^JavaSdkClient client-record ^String uri]
  (log/info :java-sdk/unsubscribing-from-resource {:uri uri})
  (let [request (McpSchema$UnsubscribeRequest. uri)]
    (if (:async? client-record)
      (let [^McpAsyncClient client (:client client-record)
            mono (.unsubscribeResource client request)]
        (.toFuture mono))
      (CompletableFuture/supplyAsync
        (reify java.util.function.Supplier
          (get
            [_]
            (let [^McpSyncClient client (:client client-record)]
              (.unsubscribeResource client request)
              nil)))))))

;; Server API (placeholder - not fully implemented yet)

(defn create-java-server
  "Create a Java SDK MCP server with configurable transport.

  Options:
  - :name - Server name (default 'java-sdk-server')
  - :version - Server version (default '0.1.0')
  - :async? - Whether to create async server (default true)
  - :transport - Server transport provider (default stdio)
  - :capabilities - Map of capabilities to enable:
    - :tools - Enable tools capability (default true)
    - :resources - Enable resources capability with optional :subscribe and :listChanged

  Returns a JavaSdkServer record that implements AutoCloseable."
  [{:keys [name version async? transport capabilities]
    :or {name "java-sdk-server" version "0.1.0" async? true
         capabilities {:tools true}}}]
  (log/debug :java-sdk/creating-server
             {:name name :version version :async? async? :capabilities capabilities})
  (let [transport
        (or transport (create-stdio-server-transport))

        ;; Build capabilities
        caps-builder (McpSchema$ServerCapabilities$Builder.)
        caps-builder (if (:tools capabilities)
                       (.tools caps-builder true)
                       caps-builder)
        caps-builder (if-let [resources-cap (:resources capabilities)]
                       (let [subscribe (get resources-cap :subscribe false)
                             list-changed (get resources-cap :listChanged false)]
                         (.resources caps-builder subscribe list-changed))
                       caps-builder)
        server-caps (.build caps-builder)

        server (if async?
                 (let [builder (if (instance? McpServerTransportProvider transport)
                                 (McpServer/async ^McpServerTransportProvider transport)
                                 (McpServer/async ^WebFluxStreamableServerTransportProvider transport))]
                   (-> ^McpServer$AsyncSpecification builder
                       (.serverInfo name version)
                       (.capabilities server-caps)
                       (.build)))
                 (let [builder (if (instance? McpServerTransportProvider transport)
                                 (McpServer/sync ^McpServerTransportProvider transport)
                                 (McpServer/sync ^WebFluxStreamableServerTransportProvider transport))]
                   (-> ^McpServer$StreamableSyncSpecification builder
                       (.serverInfo name version)
                       (.capabilities server-caps)
                       (.build))))]
    (->JavaSdkServer server name version async?)))

;; Process management for stdio transport

(defn start-process
  "Start a process for stdio transport.

  Args:
  - command: Vector of command and arguments

  Returns a Process object."
  [command]
  (log/info :java-sdk/starting-process {:command command})
  (let [pb (ProcessBuilder. ^List command)
        process (.start pb)]
    process))

(defn stop-process
  "Stop a process."
  [^Process process]
  (log/info :java-sdk/stopping-process)
  (when (.isAlive process)
    (.destroy process)
    (when-not (.waitFor process 5 TimeUnit/SECONDS)
      (.destroyForcibly process))))

(defn register-tool
  "Register a tool with the Java SDK server.

  Args:
  - server-record: JavaSdkServer record
  - tool-spec: Map with :name, :description, :input-schema, :implementation keys

  Returns the updated server record."
  [^JavaSdkServer server-record
   {:keys [name description input-schema implementation] :as tool-spec}]
  (when-not (:server server-record)
    (throw (ex-info "Invalid server record" {:server-record server-record})))
  (log/info :java-sdk/registering-tool {:name name})
  ;; Convert input-schema map to JSON string for Java SDK
  (let [^String schema-json (if (string? input-schema)
                              input-schema
                              (.writeValueAsString
                                (ObjectMapper.)
                                input-schema))
        tool (-> (McpSchema$Tool/builder)
                 (.name name)
                 (.description description)
                 (.inputSchema schema-json)
                 (.build))
        ^McpServer java-server (:server server-record)]
    (if (:async? server-record)
      (let [f (fn ^McpSchema$CallToolResult
                [exchange ^McpSchema$CallToolRequest call-tool-request]
                (let [java-args (.arguments call-tool-request)
                      clj-args (into {} (map (fn [[k v]] [(keyword k) v]) java-args))
                      clj-result (implementation clj-args)]
                  (clj-result->java-result clj-result)))
            tool-spec (-> (McpServerFeatures$AsyncToolSpecification$Builder.)
                          (.tool tool)
                          (.callHandler f)
                          (.build))
            ^McpAsyncServer async-server java-server]
        ;; Add tool to server
        (.addTool async-server tool-spec))
      (let [f (fn ^McpSchema$CallToolResult
                [exchange ^McpSchema$CallToolRequest call-tool-request]
                (let [java-args (.arguments call-tool-request)
                      clj-args (into {} (map (fn [[k v]] [(keyword k) v]) java-args))
                      clj-result (implementation clj-args)]
                  (clj-result->java-result clj-result)))
            tool-spec (-> (McpServerFeatures$SyncToolSpecification$Builder.)
                          (.tool tool)
                          (.callHandler f)
                          (.build))
            ^McpSyncServer sync-server java-server]
        ;; Add tool to server
        (.addTool sync-server tool-spec))))
  server-record)

(defn register-resource
  "Register a resource with the Java SDK server.

  Args:
  - server-record: JavaSdkServer record
  - resource-spec: Map with :uri, :name, :description, :mime-type, :implementation keys
    - :uri - Resource URI (required)
    - :name - Human-readable name (required)
    - :description - Optional description
    - :mime-type - Optional MIME type
    - :implementation - Function taking [exchange request] and returning resource contents

  Returns the updated server record."
  [^JavaSdkServer server-record
   {:keys [uri name description mime-type implementation] :as resource-spec}]
  (when-not (:server server-record)
    (throw (ex-info "Invalid server record" {:server-record server-record})))
  (log/info :java-sdk/registering-resource {:uri uri :name name})

  (let [resource-builder (-> (McpSchema$Resource/builder)
                             (.uri uri)
                             (.name name))
        resource-builder (if description
                           (.description resource-builder description)
                           resource-builder)
        resource-builder (if mime-type
                           (.mimeType resource-builder mime-type)
                           resource-builder)
        resource (.build resource-builder)
        ^McpServer java-server (:server server-record)]

    (if (:async? server-record)
      (let [read-handler (fn [exchange ^McpSchema$ReadResourceRequest read-request]
                           (let [req-uri (.uri read-request)
                                 clj-result (implementation exchange req-uri)]
                             ;; Convert Clojure result to Java ReadResourceResult
                             (let [contents-list (java.util.ArrayList.)]
                               (doseq [content (:contents clj-result)]
                                 (let [text-contents (McpSchema$TextResourceContents.
                                                       (:uri content)
                                                       (:mimeType content "text/plain")
                                                       (:text content))]
                                   (.add contents-list text-contents)))
                               (McpSchema$ReadResourceResult. contents-list))))
            resource-spec (McpServerFeatures$AsyncResourceSpecification.
                            resource
                            read-handler)]
        (.addResource ^McpAsyncServer java-server resource-spec))
      (let [read-handler (fn [exchange ^McpSchema$ReadResourceRequest read-request]
                           (let [req-uri (.uri read-request)
                                 clj-result (implementation exchange req-uri)]
                             ;; Convert Clojure result to Java ReadResourceResult
                             (let [contents-list (java.util.ArrayList.)]
                               (doseq [content (:contents clj-result)]
                                 (let [text-contents (McpSchema$TextResourceContents.
                                                       (:uri content)
                                                       (:mimeType content "text/plain")
                                                       (:text content))]
                                   (.add contents-list text-contents)))
                               (McpSchema$ReadResourceResult. contents-list))))
            resource-spec (McpServerFeatures$SyncResourceSpecification.
                            resource
                            read-handler)]
        (.addResource ^McpSyncServer java-server resource-spec))))
  server-record)

(defn notify-resource-updated
  "Send a resource updated notification from the Java SDK server.

  Args:
  - server-record: JavaSdkServer record
  - uri: URI of the resource that was updated
  - meta: Optional metadata map

  Returns the updated server record."
  [^JavaSdkServer server-record uri & [meta]]
  (when-not (:server server-record)
    (throw (ex-info "Invalid server record" {:server-record server-record})))
  (log/info :java-sdk/notifying-resource-updated {:uri uri :meta meta})

  (let [^McpServer java-server (:server server-record)
        notification (if meta
                       (McpSchema$ResourcesUpdatedNotification. uri (clj->java-map meta))
                       (McpSchema$ResourcesUpdatedNotification. uri nil))]
    (if (:async? server-record)
      (.notifyResourcesUpdated ^McpAsyncServer java-server notification)
      (.notifyResourcesUpdated ^McpSyncServer java-server notification)))
  server-record)

(defn start-server
  "Start the Java SDK server.
  
  Note: The Java SDK server doesn't require explicit starting - it's ready
  to handle requests once created with its transport.

  Args:
  - server-record: JavaSdkServer record

  Returns the server record."
  [^JavaSdkServer server-record]
  (when-not (:server server-record)
    (throw (ex-info "Invalid server record" {:server-record server-record})))
  (log/info :java-sdk/server-ready {:name (:name server-record)})
  server-record)

(defn stop-server
  "Stop the Java SDK server.

  Args:
  - server-record: JavaSdkServer record"
  [^JavaSdkServer server-record]
  (.close server-record))

(defn create-transport
  "Create transport for Java SDK client/server based on type.

  Args:
  - transport-type: :stdio-client, :stdio-server, :http-client, :http-server
  - options: Transport-specific options
    For :stdio-client - :command (string or map with :command and :args)
    For :http-client - :url (required), :use-sse (optional), :open-connection-on-startup (optional), :resumable-streams (optional)
    For :http-server - :port (optional), :use-sse (optional), :stateless (optional), :endpoint (optional)

  Returns appropriate transport provider object."
  [transport-type options]
  (case transport-type
    :stdio-client (let [command (:command options)]
                    (when-not command
                      (throw
                        (ex-info
                          "Command required for stdio client transport"
                          {:options options})))
                    (create-stdio-client-transport command))
    :stdio-server (create-stdio-server-transport)
    :http-client (create-http-client-transport options)
    :http-server (create-http-server-transport options)
    (throw
      (ex-info
        "Unknown transport type"
        {:transport-type transport-type}))))
