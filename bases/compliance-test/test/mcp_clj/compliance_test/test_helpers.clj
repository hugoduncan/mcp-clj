(ns mcp-clj.compliance-test.test-helpers
  "Shared test helpers for MCP compliance tests"
  (:require
   [mcp-clj.client-transport.factory :as client-transport-factory]
   [mcp-clj.in-memory-transport.shared :as shared]
   [mcp-clj.java-sdk.interop :as java-sdk]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-server.core :as server]
   [mcp-clj.server-transport.factory :as server-transport-factory]))

;;; Transport Registration

(defn ensure-in-memory-transport-registered!
  "Ensure in-memory transport is registered in both client and server factories.
  Can be called multiple times safely - registration is idempotent."
  []
  (client-transport-factory/register-transport!
   :in-memory
   (fn [options]
     (require 'mcp-clj.in-memory-transport.client)
     (let [create-fn (ns-resolve
                      'mcp-clj.in-memory-transport.client
                      'create-transport)]
       (create-fn options))))
  (server-transport-factory/register-transport!
   :in-memory
   (fn [options handlers]
     (require 'mcp-clj.in-memory-transport.server)
     (let [create-server (ns-resolve
                          'mcp-clj.in-memory-transport.server
                          'create-in-memory-server)]
       (create-server options handlers)))))

(ensure-in-memory-transport-registered!)

;;; Test Tools Creation

(defn create-test-tools
  "Create test tools with version-appropriate fields.

  Protocol version differences:
  - 2024-11-05: Base version with name, description, inputSchema
  - 2025-03-26: Adds annotations field
  - 2025-06-18: Adds title and outputSchema fields"
  [protocol-version]
  (let [base-tools
        {"echo"
         {:name "echo"
          :description "Echo the input message"
          :inputSchema {:type "object"
                        :properties {:message {:type "string"
                                               :description "Message to echo"}}
                        :required ["message"]}
          :implementation (fn [{:keys [message]}]
                            {:content [{:type "text"
                                        :text (str "Echo: " message)}]
                             :isError false})}

         "add"
         {:name "add"
          :description "Add two numbers"
          :inputSchema {:type "object"
                        :properties {:a {:type "number"
                                         :description "First number"}
                                     :b {:type "number"
                                         :description "Second number"}}
                        :required ["a" "b"]}
          :implementation (fn [{:keys [a b]}]
                            {:content [{:type "text"
                                        :text (str (+ a b))}]
                             :isError false})}

         "error"
         {:name "error"
          :description "Tool that always throws an error"
          :inputSchema {:type "object"
                        :properties {:message {:type "string"
                                               :description "Error message"}}
                        :required ["message"]}
          :implementation (fn [{:keys [message]}]
                            (throw (ex-info message {:type :test-error})))}}

        ;; Add annotations for 2025-03-26+
        with-annotations (if (>= (compare protocol-version "2025-03-26") 0)
                           (-> base-tools
                               (assoc-in ["echo" :annotations] {:category "utility"})
                               (assoc-in ["add" :annotations] {:category "math"}))
                           base-tools)

        ;; Add title and outputSchema for 2025-06-18+
        with-extended (if (>= (compare protocol-version "2025-06-18") 0)
                        (-> with-annotations
                            (assoc-in ["echo" :title] "Echo Tool")
                            (assoc-in ["add" :title] "Addition Calculator")
                            (assoc-in ["add" :outputSchema]
                                      {:type "object"
                                       :properties {:result {:type "number"}}
                                       :required ["result"]}))
                        with-annotations)]

    with-extended))

;;; Test Environment Creation

(defn- stop-in-memory-server!
  "Stop an in-memory server"
  [server]
  (require 'mcp-clj.in-memory-transport.server)
  ((ns-resolve 'mcp-clj.in-memory-transport.server 'stop!) server))

(defn create-clojure-pair
  "Create Clojure client + Clojure server pair using in-memory transport.

  Returns a map with:
  - :client - initialized MCP client
  - :server - running MCP server
  - :cleanup-fn - function to cleanly shutdown both"
  [protocol-version]
  (let [test-tools (create-test-tools protocol-version)
        shared-transport (shared/create-shared-transport)

        ;; Create server
        mcp-server (server/create-server
                    {:transport {:type :in-memory
                                 :shared shared-transport}
                     :tools test-tools
                     :server-info {:name "test-server"
                                   :version "1.0.0"}})

        ;; Create client
        mcp-client (client/create-client
                    {:transport {:type :in-memory
                                 :shared shared-transport}
                     :client-info {:name "test-client"
                                   :version "1.0.0"}
                     :protocol-version protocol-version})]

    ;; Wait for client to initialize
    (client/wait-for-ready mcp-client 5000)

    {:client mcp-client
     :server mcp-server
     :cleanup-fn (fn []
                   (client/close! mcp-client)
                   ((:stop mcp-server)))}))

(defn create-clojure-client-java-server-pair
  "Create Clojure client + Java SDK server pair using stdio transport.

  Returns a map with:
  - :client - initialized MCP client
  - :server - running Java SDK server
  - :cleanup-fn - function to cleanly shutdown both"
  [protocol-version]
  (let [test-tools (create-test-tools protocol-version)

        ;; Create Java SDK server
        java-server (java-sdk/create-java-server
                     {:name "java-test-server"
                      :version "1.0.0"
                      :async? true})

        ;; Register test tools
        _ (doseq [[_name tool] test-tools]
            (java-sdk/register-tool java-server tool))

        ;; Start the server
        _ (java-sdk/start-server java-server)

        ;; Create transport for Clojure client to connect to Java server
        transport (java-sdk/create-stdio-client-transport
                   {:command "clojure"
                    :args ["-M:dev:test" "-m"
                           "mcp-clj.java-sdk.sdk-server-main"]})

        ;; Create Clojure client
        mcp-client (client/create-client
                    {:transport {:type :stdio
                                 :transport-instance transport}
                     :client-info {:name "test-client"
                                   :version "1.0.0"}
                     :protocol-version protocol-version})]

    ;; Wait for client to initialize
    (client/wait-for-ready mcp-client 5000)

    {:client mcp-client
     :server java-server
     :cleanup-fn (fn []
                   (client/close! mcp-client)
                   (java-sdk/stop-server java-server))}))

(defn create-java-client-clojure-server-pair
  "Create Java SDK client + Clojure server pair using stdio transport.

  Returns a map with:
  - :client - initialized Java SDK client
  - :server - running MCP server
  - :cleanup-fn - function to cleanly shutdown both"
  [protocol-version]
  (let [test-tools (create-test-tools protocol-version)

        ;; Create Clojure server with stdio transport
        mcp-server (server/create-server
                    {:transport {:type :stdio}
                     :tools test-tools
                     :server-info {:name "test-server"
                                   :version "1.0.0"}})

        ;; Create Java SDK client
        transport (java-sdk/create-stdio-client-transport
                   {:command "clojure"
                    :args ["-M:dev:test" "-m"
                           "mcp-clj.stdio-server"]})

        java-client (java-sdk/create-java-client
                     {:transport transport
                      :async? true})]

    ;; Initialize client
    (java-sdk/initialize-client java-client)

    {:client java-client
     :server mcp-server
     :cleanup-fn (fn []
                   (java-sdk/close-client java-client)
                   ((:stop mcp-server)))}))

;;; Test Execution Helpers

(def test-protocol-versions
  "MCP protocol versions to test for compliance"
  ["2024-11-05" "2025-03-26" "2025-06-18"])

(def test-implementations
  "Implementation combinations to test.
  Currently only includes Clojure-only pair to avoid stdio transport issues."
  [{:name "Clojure client + Clojure server"
    :create-fn create-clojure-pair
    :client-type :clojure
    :integ? false}

   ;; TODO: Enable when stdio transport issues are resolved
   #_{:name "Clojure client + Java server"
      :create-fn create-clojure-client-java-server-pair
      :client-type :clojure
      :integ? true}

   #_{:name "Java client + Clojure server"
      :create-fn create-java-client-clojure-server-pair
      :client-type :java
      :integ? true}])

(defn run-test-across-implementations
  "Run test-fn across all implementation combinations and protocol versions.

  test-fn receives three arguments:
  - client-type (:clojure or :java)
  - protocol-version (string)
  - pair (map with :client, :server, :cleanup-fn)"
  [test-fn]
  (doseq [impl test-implementations
          protocol-version test-protocol-versions]
    (clojure.test/testing (str (:name impl) " [" protocol-version "]")
      (let [pair ((:create-fn impl) protocol-version)]
        (try
          (test-fn (:client-type impl) protocol-version pair)
          (finally
            ((:cleanup-fn pair))))))))
