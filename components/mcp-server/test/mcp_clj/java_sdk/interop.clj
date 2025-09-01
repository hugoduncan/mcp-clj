(ns mcp-clj.java-sdk.interop
  "Java SDK interop wrapper for cross-implementation testing.

  Provides a minimal Clojure API to create and interact with MCP
  clients and servers from the official Java SDK."
  (:require
   [mcp-clj.log :as log])
  (:import
   [com.fasterxml.jackson.databind
    ObjectMapper] ;; Types
   [io.modelcontextprotocol.client
    McpClient
    McpAsyncClient
    McpSyncClient]
   [io.modelcontextprotocol.client.transport
    StdioClientTransport]
   [io.modelcontextprotocol.server
    McpServer
    McpAsyncServer
    McpSyncServer
    McpServerFeatures
    McpServerFeatures$SyncToolSpecification
    McpServerFeatures$SyncToolSpecification$Builder
    McpServerFeatures$AsyncToolSpecification$Builder]
   [io.modelcontextprotocol.server.transport
    StdioServerTransportProvider] ;; Jackson for JSON
   [io.modelcontextprotocol.spec
    McpSchema
    McpSchema$CallToolRequest
    McpSchema$CallToolResult
    McpSchema$ServerCapabilities$Builder
    McpSchema$Tool] ;; Java standard library
   [java.lang AutoCloseable]
   [java.util List
    Map]
   [java.util.concurrent CompletableFuture
    TimeUnit]))

;;; Utility functions

;;; Records for Java SDK client and server wrappers

(defrecord JavaSdkClient [^McpClient client transport async?]
  AutoCloseable
  (close [_this]
    (log/info :java-sdk/closing-client)
    (try
      (.close ^AutoCloseable client)
      (.close ^AutoCloseable transport)
      (catch Exception e
        (log/warn :java-sdk/close-error {:error e})))))

(defrecord JavaSdkServer [^McpServer server name version async?]
  AutoCloseable
  (close [_this]
    (log/info :java-sdk/closing-server)
    (try
      (if async?
        (.closeGracefully ^McpAsyncServer server)
        (.closeGracefully ^McpSyncServer server))
      (catch Exception e
        (log/warn :java-sdk/server-close-error {:error e})))))

(defn- clj->java-map
  "Convert Clojure map to Java Map"
  ^Map [m]
  (if (map? m)
    (java.util.HashMap. ^Map m)
    m))

(defn- await-future
  "Block and wait for CompletableFuture with timeout"
  [^CompletableFuture future timeout-seconds]
  (try
    (.get future timeout-seconds TimeUnit/SECONDS)
    (catch Exception e
      (log/error :java-sdk/future-error {:error e})
      (throw e))))

#_(defn- java-content->clj
    "Convert Java content object to Clojure map"
    [content]
     ;; Convert Java content objects to Clojure maps
     ;; The exact structure depends on the Java SDK implementation
    (try
      {:type (.getType content)
       :text (.getText content)}
      (catch Exception e
        (log/warn :java-sdk/content-conversion-error {:error e :content content})
        {:type "text" :text (str content)})))

(defn- java-tool-result->clj
  "Convert Java tool call result to Clojure map"
  [result]
  (try
    (if (instance? Map result)
      (into {} result)
      {:content [{:type "text" :text (str result)}]})
    (catch Exception e
      (log/error :java-sdk/result-conversion-error {:error e})
      {:content [{:type "text" :text "Error converting result"}]
       :isError true})))

(defn- java-tools-result->clj
  "Convert Java tools list to Clojure map"
  [tools]
  (try
    {:tools (mapv (fn [^McpSchema$Tool tool]
                    {:name          (.name tool)
                     :title         (.title tool)
                     :description   (.description tool)
                     :input-schema  (into {} (.inputSchema tool))
                     :output-schema (into {} (.outputSchema tool))
                     :meta          (into {} (._meta tool))})
                  tools)}
    (catch Exception e
      (log/error :java-sdk/tools-result-conversion-error {:error e})
      {:tools []})))

;;; Client API

(defn create-java-client
  "Create a Java SDK MCP client.

  Options:
  - :transport - Transport provider object (required)
  - :timeout - Request timeout in seconds (default 30)
  - :async? - Whether to create async client (default true)

  Returns a JavaSdkClient record that implements AutoCloseable."
  [{:keys [transport timeout async?]
    :or {timeout 30 async? true}}]
  (let [client (if async?
                 (-> (McpClient/async transport)
                     (.build))
                 (-> (McpClient/sync transport)
                     (.build)))]
    (->JavaSdkClient client transport async?)))

(defn create-stdio-client-transport
  "Create a stdio transport provider for the client.

  Args can be:
  - A string command (e.g., \"node server.js\")
  - A map with :command and optional :args (e.g., {:command \"node\" :args [\"server.js\"]})"
  [command-spec]
  (let [[cmd args] (cond
                     (string? command-spec)
                     [command-spec nil]

                     (map? command-spec)
                     [(:command command-spec) (:args command-spec)]

                     :else
                     (throw (ex-info "Invalid command spec" {:command-spec command-spec})))

        ;; Combine command and args into a single command array
        command-array (if args
                        (into-array String (cons cmd args))
                        (into-array String [cmd]))]

    (StdioClientTransport. command-array (ObjectMapper.))))

(defn create-stdio-server-transport
  "Create a stdio transport provider for the server."
  ^StdioServerTransportProvider []
  (StdioServerTransportProvider. (ObjectMapper.)))

(defn initialize-client
  "Initialize the Java SDK client connection.

  Returns the initialization result."
  [^JavaSdkClient client-record]
  (log/info :java-sdk/initializing-client)
  (if (:async? client-record)
    (await-future (.initialize ^McpAsyncClient (:client client-record)) 30)
    (.initialize ^McpSyncClient (:client client-record))))

(defn list-tools
  "List available tools from the server.

  Returns tools list converted to Clojure map."
  [^JavaSdkClient client-record]
  (log/info :java-sdk/listing-tools)
  (if (:async? client-record)
    (let [result (await-future (.listTools ^McpAsyncClient (:client client-record)) 30)]
      (java-tools-result->clj result))
    (let [result (.listTools ^McpSyncClient (:client client-record))]
      (java-tools-result->clj result))))

(defn call-tool
  "Call a tool through the Java SDK client.

  Args:
  - client-record: JavaSdkClient record
  - tool-name: Name of the tool to call
  - arguments: Map of arguments for the tool

  Returns tool result converted to Clojure map."
  [^JavaSdkClient client-record ^String tool-name arguments]
  (log/info :java-sdk/calling-tool {:tool tool-name :args arguments})
  (let [^McpSchema$CallToolRequest request
        (-> (McpSchema$CallToolRequest/builder)
            (.name tool-name)
            (.arguments (clj->java-map arguments))
            (.build))]
    (if (:async? client-record)
      (let [^McpAsyncClient client (:client client-record)
            result                 (await-future
                                    (.callTool client request)
                                    30)]
        (java-tool-result->clj result))
      (let [^McpSyncClient client (:client client-record)
            result                (.callTool client request)]
        (java-tool-result->clj result)))))

(defn close-client
  "Close the Java SDK client."
  [^JavaSdkClient client-record]
  (.close client-record))

;;; Server API (placeholder - not fully implemented yet)

(defn create-java-server
  "Create a Java SDK MCP server using stdio transport.

  Options:
  - :name - Server name (default 'java-sdk-server')
  - :version - Server version (default '0.1.0')
  - :async? - Whether to create async server (default true)

  Returns a JavaSdkServer record that implements AutoCloseable."
  [{:keys [name version async?]
    :or {name "java-sdk-server" version "0.1.0" async? true}}]
  (log/info :java-sdk/creating-server {:name name :version version :async? async?})
  (let [transport (create-stdio-server-transport)
        server (if async?
                 (-> (McpServer/async transport)
                     (.serverInfo name version)
                     (.capabilities
                      (-> (McpSchema$ServerCapabilities$Builder.)
                          (.tools true)
                          (.build)))
                     (.build))
                 (-> (McpServer/sync transport)
                     (.serverInfo name version)
                     (.capabilities
                      (-> (McpSchema$ServerCapabilities$Builder.)
                          (.tools true)
                          (.build)))))]
    (->JavaSdkServer server name version async?)))

;;; Process management for stdio transport

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
  - tool-spec: Map with :name, :description, :inputSchema, :implementation keys

  Returns the updated server record."
  [^JavaSdkServer server
   {:keys [name description ^String input-schema implementation] :as tool-spec}]
  (when-not (:server server)
    (throw (ex-info "Invalid server record" {:server-record server})))
  (log/info :java-sdk/registering-tool {:name name})
  ;; Build Tool object
  (let [tool              (-> (McpSchema$Tool/builder)
                              (.name name)
                              (.description description)
                              (.inputSchema input-schema)
                              (.build))
        ^McpServer server (:server server)]
    (if (:sync? server)
      (let [f                     (fn ^McpSchema$CallToolResult
                                    [exchange
                                     ^McpSchema$CallToolRequest call-tool-request]
                                    (implementation))
            tool-spec             (-> (McpServerFeatures$SyncToolSpecification$Builder.)
                                      (.tool tool)
                                      (.callHandler f)
                                      (.build))
            ^McpSyncServer server (:server server)]
        ;; Add tool to server
        (.addTool server tool-spec))
      (let [f                      (fn ^McpSchema$CallToolResult
                                     [exchange
                                      ^McpSchema$CallToolRequest call-tool-request]
                                     (implementation))
            tool-spec              (-> (McpServerFeatures$AsyncToolSpecification$Builder.)
                                       (.tool tool)
                                       (.callHandler f)
                                       (.build))
            ^McpAsyncServer server (:server server)]
        ;; Add tool to server
        (.addTool server tool-spec))))
  server)

(defn start-server
  "Start the Java SDK server.

  Args:
  - server-record: JavaSdkServer record

  Returns the server record."
  [^JavaSdkServer server-record]
  (when-not (:server server-record)
    (throw (ex-info "Invalid server record" {:server-record server-record})))
  (log/info :java-sdk/starting-server {:name (:name server-record)})
  ;; (let [server (:server server-record)]
  ;;   (.start server))
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
  - transport-type: :stdio-client, :stdio-server
  - options: Transport-specific options
    For :stdio-client - :command (string or map with :command and :args)

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
    (throw
     (ex-info
      "Unknown transport type"
      {:transport-type transport-type}))))
