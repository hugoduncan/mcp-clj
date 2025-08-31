(ns mcp-clj.java-sdk.interop
  "Java SDK interop wrapper for cross-implementation testing.
  
  Provides a minimal Clojure API to create and interact with MCP
  clients and servers from the official Java SDK."
  (:require
   [clojure.java.io :as io]
   [mcp-clj.log :as log])
  (:import
   ;; Core MCP classes
   [io.modelcontextprotocol.client McpClient]
   [io.modelcontextprotocol.server McpServer]

   ;; Available transport classes
   [io.modelcontextprotocol.client.transport StdioClientTransport ServerParameters]

   ;; Java standard library
   [java.time Duration]
   [java.util Map List Optional]
   [java.util.concurrent CompletableFuture TimeUnit]
   [java.util.function Function Consumer]))

;;; Utility functions

(defn- clj->java-map
  "Convert Clojure map to Java Map"
  [m]
  (if (map? m)
    (java.util.HashMap. ^Map m)
    m))

(defn- await-mono
  "Block and wait for Reactor Mono with timeout"
  [mono timeout-seconds]
  (try
    (.block mono (Duration/ofSeconds timeout-seconds))
    (catch Exception e
      (log/error :java-sdk/mono-error {:error e})
      (throw e))))

;;; Client API

(defn create-java-client
  "Create a Java SDK MCP client.
  
  Options:
  - :transport - Transport object (required)
  - :timeout - Request timeout in seconds (default 30)
  - :init-timeout - Initialization timeout in seconds (default 10)
  - :async? - Whether to create async client (default true)
  
  Returns a map with :client key."
  [{:keys [transport timeout init-timeout async?]
    :or {timeout 30 init-timeout 10 async? true}}]
  (let [builder (if async?
                  (McpClient/async transport)
                  (McpClient/sync transport))
        client (-> builder
                   (.requestTimeout (Duration/ofSeconds timeout))
                   (.initializationTimeout (Duration/ofSeconds init-timeout))
                   (.build))]
    {:client client
     :async? async?}))

(defn create-stdio-transport
  "Create a stdio transport for the client.
  
  Args:
  - command: Command string to start the MCP server process"
  [command]
  (let [server-params (-> (ServerParameters/builder command)
                          (.build))]
    (StdioClientTransport. server-params)))

(defn initialize-client
  "Initialize the Java SDK client connection.
  
  Returns the initialization result."
  [{:keys [client async?] :as client-map}]
  (log/info :java-sdk/initializing-client)
  (if async?
    (await-mono (.initialize client) 30)
    (.initialize client)))

(defn list-tools
  "List available tools from the server.
  
  Returns a ListToolsResult."
  [{:keys [client async?]}]
  (log/info :java-sdk/listing-tools)
  (if async?
    ;; For async client, need to import and use FIRST_PAGE
    (throw (ex-info "Async list-tools not implemented yet" {}))
    (.listTools client)))

(defn call-tool
  "Call a tool through the Java SDK client.
  
  Args:
  - client-map: Map with :client key  
  - tool-name: Name of the tool to call
  - arguments: Map of arguments for the tool
  
  Returns a CallToolResult."
  [{:keys [client async?]} tool-name arguments]
  (log/info :java-sdk/calling-tool {:tool tool-name :args arguments})
  (if async?
    (throw (ex-info "Async call-tool not implemented yet" {}))
    (throw (ex-info "Sync call-tool not implemented yet" {}))))

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
  "Create a Java SDK MCP server with specified transport.
  
  Options:
  - :transport - Transport object (required)
  - :name - Server name (default 'java-sdk-server')
  - :version - Server version (default '0.1.0')
  - :async? - Whether to create async server (default true)
  
  Returns a map with :server key."
  [{:keys [transport name version async?]
    :or {name "java-sdk-server" version "0.1.0" async? true}}]
  (throw (ex-info "Server creation not implemented yet" {})))

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