(ns mcp-clj.java-sdk.interop
  "Java SDK interop wrapper for cross-implementation testing.

  Provides a minimal Clojure API to create and interact with MCP
  clients and servers from the official Java SDK."
  (:require
   [mcp-clj.log :as log])
  (:import
   ;; Core MCP SDK classes
   [io.modelcontextprotocol.client
    McpClient]
   [io.modelcontextprotocol.client.transport
    StdioClientTransport]
   [io.modelcontextprotocol.server
    McpServer]
   [io.modelcontextprotocol.server.transport
    StdioServerTransportProvider]

   ;; Jackson for JSON
   [com.fasterxml.jackson.databind
    ObjectMapper]

   ;; Types
   [io.modelcontextprotocol.spec
    McpSchema$ServerCapabilities
    McpSchema$ServerCapabilities$Builder
    McpSchema$Tool]

   ;; Java standard library
   [java.util Map List]
   [java.util.concurrent TimeUnit]))

;;; Utility functions

(defn- clj->java-map
  "Convert Clojure map to Java Map"
  [m]
  (if (map? m)
    (java.util.HashMap. ^Map m)
    m))

(defn- await-future
  "Block and wait for CompletableFuture with timeout"
  [future timeout-seconds]
  (try
    (.get future timeout-seconds TimeUnit/SECONDS)
    (catch Exception e
      (log/error :java-sdk/future-error {:error e})
      (throw e))))

(defn- java-content->clj
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

(defn- java-McpSchema$Tool-result->clj
  "Convert Java McpSchema$Tool call result to Clojure map"
  [result]
  (try
    (if (instance? Map result)
      (into {} result)
      {:content [{:type "text" :text (str result)}]})
    (catch Exception e
      (log/error :java-sdk/result-conversion-error {:error e})
      {:content [{:type "text" :text "Error converting result"}]
       :isError true})))

(defn- java-McpSchema$Tools-result->clj
  "Convert Java McpSchema$Tools list to Clojure map"
  [McpSchema$Tools]
  (try
    {:McpSchema$Tools (mapv (fn [McpSchema$Tool]
                              {:name        (.getName McpSchema$Tool)
                               :description (.getDescription McpSchema$Tool)
                               :inputSchema (into {} (.getInputSchema McpSchema$Tool))})
                            McpSchema$Tools)}
    (catch Exception e
      (log/error :java-sdk/McpSchema$Tools-result-conversion-error {:error e})
      {:McpSchema$Tools []})))

;;; Client API

(defn create-java-client
  "Create a Java SDK MCP client.

  Options:
  - :transport - Transport provider object (required)
  - :timeout - Request timeout in seconds (default 30)
  - :async? - Whether to create async client (default true)

  Returns a map with :client key."
  [{:keys [transport timeout async?]
    :or   {timeout 30 async? true}}]
  (let [client (if async?
                 (-> (McpClient/async transport)
                     (.serverInfo "java-sdk-client" "1.0.0")
                     (.build))
                 (-> (McpClient/sync transport)
                     (.serverInfo "java-sdk-client" "1.0.0")
                     (.build)))]
    {:client client
     :async? async?}))

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
  []
  (StdioServerTransportProvider. (ObjectMapper.)))

(defn initialize-client
  "Initialize the Java SDK client connection.

  Returns the initialization result."
  [{:keys [client async?] :as client-map}]
  (log/info :java-sdk/initializing-client)
  (if async?
    (await-future (.connect client) 30)
    (.connect client)))

(defn list-McpSchema$Tools
  "List available McpSchema$Tools from the server.

  Returns McpSchema$Tools list converted to Clojure map."
  [{:keys [client async?]}]
  (log/info :java-sdk/listing-McpSchema$Tools)
  (if async?
    (let [result (await-future (.listMcpSchema$Tools client) 30)]
      (java-McpSchema$Tools-result->clj result))
    (let [result (.listMcpSchema$Tools client)]
      (java-McpSchema$Tools-result->clj result))))

(defn call-McpSchema$Tool
  "Call a McpSchema$Tool through the Java SDK client.

  Args:
  - client-map: Map with :client key
  - McpSchema$Tool-name: Name of the McpSchema$Tool to call
  - arguments: Map of arguments for the McpSchema$Tool

  Returns McpSchema$Tool result converted to Clojure map."
  [{:keys [client async?]} McpSchema$Tool-name arguments]
  (log/info :java-sdk/calling-McpSchema$Tool {:McpSchema$Tool McpSchema$Tool-name :args arguments})
  (if async?
    (let [result (await-future (.callMcpSchema$Tool client McpSchema$Tool-name (clj->java-map arguments)) 30)]
      (java-McpSchema$Tool-result->clj result))
    (let [result (.callMcpSchema$Tool client McpSchema$Tool-name (clj->java-map arguments))]
      (java-McpSchema$Tool-result->clj result))))

(defn close-client
  "Close the Java SDK client."
  [{:keys [client]}]
  (log/info :java-sdk/closing-client)
  (try
    (.close client)
    (catch Exception e
      (log/warn :java-sdk/close-error {:error e}))))

;;; Server API (placeholder - not fully implemented yet)

(defn create-java-server
  "Create a Java SDK MCP server using stdio transport.

  Options:
  - :name - Server name (default 'java-sdk-server')
  - :version - Server version (default '0.1.0')
  - :async? - Whether to create async server (default true)

  Returns a map with server configuration."
  [{:keys [name version async?]
    :or   {name "java-sdk-server" version "0.1.0" async? true}}]
  (log/info :java-sdk/creating-server {:name name :version version :async? async?})
  (let [transport (create-stdio-server-transport)
        server    (if async?
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
    {:server  server
     :async?  async?
     :name    name
     :version version}))

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

(defn register-McpSchema$Tool
  "Register a McpSchema$Tool with the Java SDK server.

  Args:
  - server-map: Server map returned from create-java-server
  - McpSchema$Tool-spec: Map with :name, :description, :inputSchema, :implementation keys

  Returns the updated server map."
  [server-map {:keys [name description inputSchema implementation] :as McpSchema$Tool-spec}]
  (when-not (:server server-map)
    (throw (ex-info "Invalid server map" {:server-map server-map})))
  (log/info :java-sdk/registering-McpSchema$Tool {:name name})
  ;; Build McpSchema$Tool object
  (let [McpSchema$Tool (-> (McpSchema$Tool/builder)
                 (.name name)
                 (.description description)
                 (.inputSchema (clj->java-map inputSchema))
                 (.build))
        server (:server server-map)]
    ;; Add McpSchema$Tool to server
    (if (:async? server-map)
      (.addMcpSchema$Tool server McpSchema$Tool implementation)
      (.addMcpSchema$Tool server McpSchema$Tool implementation)))
  server-map)

(defn start-server
  "Start the Java SDK server.

  Args:
  - server-map: Server map returned from create-java-server

  Returns the server map."
  [server-map]
  (when-not (:server server-map)
    (throw (ex-info "Invalid server map" {:server-map server-map})))
  (log/info :java-sdk/starting-server {:name (:name server-map)})
  (let [server (:server server-map)]
    (.start server))
  server-map)

(defn stop-server
  "Stop the Java SDK server.

  Args:
  - server-map: Server map returned from create-java-server"
  [server-map]
  (when-not (:server server-map)
    (throw (ex-info "Invalid server map" {:server-map server-map})))
  (log/info :java-sdk/stopping-server {:name (:name server-map)})
  (let [server (:server server-map)]
    (.close server))
  server-map)

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
                      (throw (ex-info "Command required for stdio client transport" {:options options})))
                    (create-stdio-client-transport command))
    :stdio-server (create-stdio-server-transport)
    (throw (ex-info "Unknown transport type" {:transport-type transport-type}))))
