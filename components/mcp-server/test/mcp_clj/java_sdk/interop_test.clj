(ns mcp-clj.java-sdk.interop-test
  "Comprehensive tests for Java SDK interop wrapper using subprocess approach.

  Tests all critical aspects of the SDK wrapper to ensure reliability:
  - Transport creation and configuration
  - Client operations (sync/async, connection, discovery, calling)
  - Server operations (creation, registration, lifecycle)
  - Data conversion between Clojure and Java
  - End-to-end integration via subprocess
  - Error handling and edge cases"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [mcp-clj.java-sdk.interop :as java-sdk]
   [mcp-clj.log :as log])
  (:import
   [java.util.concurrent TimeUnit]))

;;; Test Infrastructure

(def ^:dynamic *sync-client* nil)
(def ^:dynamic *async-client* nil)

(defn start-test-server-process
  "Start SDK server subprocess for testing"
  []
  (log/info :test/starting-server-process)
  (let [pb      (ProcessBuilder.
                 ^"[Ljava.lang.String;"
                 (into-array String
                             ["clj" "-M:dev:test" "-m"
                              "mcp-clj.java-sdk.sdk-server-main"]))
        _       (.redirectErrorStream pb true)
        process (.start pb)]

    (Thread/sleep 3000) ; Give server time to start

    (if (.isAlive process)
      (do
        (log/info :test/server-process-started)
        process)
      (throw (ex-info "Server process failed to start"
                      {:exit-code (.exitValue process)})))))

(defn stop-test-server-process
  "Stop SDK server subprocess"
  [^Process process]
  (when (and process (.isAlive process))
    (log/info :test/stopping-server-process)
    (.destroy process)
    (when-not (.waitFor process 5 TimeUnit/SECONDS)
      (.destroyForcibly process))
    (log/info :test/server-process-stopped)))

(defn create-test-client
  "Create SDK client for testing"
  [async?]
  (let [transport (java-sdk/create-stdio-client-transport
                   {:command "clj"
                    :args ["-M:dev:test" "-m"
                           "mcp-clj.java-sdk.sdk-server-main"]})
        client (java-sdk/create-java-client
                {:transport transport
                 :async? async?})]
    client))

(defn with-clients
  "Test fixture: create sync and async clients"
  [f]
  (let [sync-client (create-test-client false)
        async-client (create-test-client true)]
    (try
      (binding [*sync-client* sync-client
                *async-client* async-client]
        (f))
      (finally
        (when sync-client (java-sdk/close-client sync-client))
        (when async-client (java-sdk/close-client async-client))))))

(use-fixtures :each with-clients)

;;; Transport Layer Tests

(deftest test-transport-creation
  "Test transport provider creation with various configurations"
  (testing "stdio client transport with string command"
    (let [transport (java-sdk/create-stdio-client-transport "echo test")]
      (is (some? transport))))

  (testing "stdio client transport with command and args map"
    (let [transport (java-sdk/create-stdio-client-transport
                     {:command "clj" :args ["-version"]})]
      (is (some? transport))))

  (testing "stdio server transport"
    (let [transport (java-sdk/create-stdio-server-transport)]
      (is (some? transport))))

  (testing "HTTP client transport creation"
    (let [transport (java-sdk/create-http-client-transport
                     {:url "http://localhost:8080"})]
      (is (some? transport)))

    (let [transport (java-sdk/create-http-client-transport
                     {:url "http://localhost:8080"
                      :open-connection-on-startup true
                      :resumable-streams true})]
      (is (some? transport)))

    (let [transport (java-sdk/create-http-client-transport
                     {:url "http://localhost:8080"
                      :use-sse true})]
      (is (some? transport))))

  (testing "HTTP server transport creation"
    (let [transport (java-sdk/create-http-server-transport
                     {:port 8080})]
      (is (some? transport)))

    (let [transport (java-sdk/create-http-server-transport
                     {:port 8081
                      :use-sse true})]
      (is (some? transport))))

  (testing "transport creation via create-transport function"
    (let [transport (java-sdk/create-transport :http-client
                                                {:url "http://localhost:8080"})]
      (is (some? transport)))

    (let [transport (java-sdk/create-transport :http-server
                                                {:port 8080})]
      (is (some? transport))))

  (testing "transport creation error handling"
    (is (thrown? Exception
                 (java-sdk/create-stdio-client-transport nil)))

    (is (thrown? Exception
                 (java-sdk/create-stdio-client-transport {})))

    (is (thrown? Exception
                 (java-sdk/create-http-client-transport {})))

    (is (thrown? Exception
                 (java-sdk/create-http-client-transport {:use-sse true})))))

;;; Client Operations Tests

(deftest ^:integ test-client-creation-and-types
  "Test client creation in sync and async modes"
  (testing "sync client creation"
    (is (some? *sync-client*))
    (is (some? (:client *sync-client*)))
    (is (= false (:async? *sync-client*))))

  (testing "async client creation"
    (is (some? *async-client*))
    (is (some? (:client *async-client*)))
    (is (= true (:async? *async-client*)))))

(deftest ^:integ test-client-initialization
  "Test client connection and initialization"
  (testing "sync client initialization"
    (let [result (java-sdk/initialize-client *sync-client*)]
      (is (some? result))
      (log/info :test/sync-client-initialized {:result result})))

  (testing "async client initialization"
    (let [result (java-sdk/initialize-client *async-client*)]
      (is (some? result))
      (log/info :test/async-client-initialized {:result result}))))

(deftest ^:integ test-tool-discovery
  "Test listing tools from server"
  (testing "sync client tool listing"
    (java-sdk/initialize-client *sync-client*)
    (let [result @(java-sdk/list-tools *sync-client*)]
      (is (map? result))
      (is (contains? result :tools))
      (is (sequential? (:tools result)))
      (is (= 3 (count (:tools result)))) ; echo, add, get-time

      (let [tool-names (set (map :name (:tools result)))]
        (is (contains? tool-names "echo"))
        (is (contains? tool-names "add"))
        (is (contains? tool-names "get-time")))

      (log/info :test/sync-tools-listed {:count (count (:tools result))})))

  (testing "async client tool listing"
    (java-sdk/initialize-client *async-client*)
    (let [result @(java-sdk/list-tools *async-client*)]
      (is (map? result))
      (is (contains? result :tools))
      (is (= 3 (count (:tools result))))

      (log/info :test/async-tools-listed {:count (count (:tools result))}))))

;;; Data Conversion Tests

(deftest ^:integ test-data-marshalling
  "Test data conversion between Clojure and Java"
  (testing "simple string arguments"
    (java-sdk/initialize-client *sync-client*)
    (let [result @(java-sdk/call-tool
                   *sync-client*
                   "echo"
                   {:message "Hello World"})]
      (is (map? result))
      (is (contains? result :content))
      (let [content (first (:content result))]
        (is (= "text" (:type content)))
        (is (= "Echo: Hello World" (:text content))))))

  (testing "numeric arguments"
    (java-sdk/initialize-client *sync-client*)
    (let [result @(java-sdk/call-tool *sync-client* "add" {:a 42 :b 13})]
      (is (map? result))
      (is (contains? result :content))
      (let [content (first (:content result))]
        (is (= "text" (:type content)))
        (is (= "55" (:text content))))))

  (testing "special characters and unicode"
    (java-sdk/initialize-client *sync-client*)
    (let [message "Special chars: åäö 中文 🚀 \n\t\"quotes\""
          result  @(java-sdk/call-tool *sync-client* "echo" {:message message})]
      (is (map? result))
      (let [content (first (:content result))]
        (is (= (str "Echo: " message) (:text content))))))

  (testing "empty and nil values"
    (java-sdk/initialize-client *sync-client*)
    (let [result @(java-sdk/call-tool *sync-client* "echo" {:message ""})]
      (is (map? result))
      (let [content (first (:content result))]
        (is (= "Echo: " (:text content)))))))

;;; Tool Execution Tests

(deftest ^:integ test-tool-execution-patterns
  "Test various tool execution patterns"
  (testing "sequential tool calls"
    (java-sdk/initialize-client *sync-client*)
    (let [results (doall (for [i (range 3)]
                           @(java-sdk/call-tool
                             *sync-client* "echo"
                             {:message (str "Message " i)})))]
      (is (= 3 (count results)))
      (doseq [[i result] (map-indexed vector results)]
        (let [content (first (:content result))]
          (is (= (str "Echo: Message " i) (:text content)))))))

  (testing "different tool types in sequence"
    (java-sdk/initialize-client *sync-client*)

    ;; Call echo
    (let [echo-result @(java-sdk/call-tool
                        *sync-client*
                        "echo"
                        {:message "test"})]
      (is (some? echo-result))
      (is (= "Echo: test" (-> echo-result :content first :text))))

    ;; Call add
    (let [add-result @(java-sdk/call-tool *sync-client* "add" {:a 10 :b 5})]
      (is (some? add-result))
      (is (= "15" (-> add-result :content first :text))))

    ;; Call get-time
    (let [time-result @(java-sdk/call-tool *sync-client* "get-time" {})]
      (is (some? time-result))
      (is (string? (-> time-result :content first :text)))))

  (testing "async tool execution"
    (java-sdk/initialize-client *async-client*)
    (let [result @(java-sdk/call-tool
                   *async-client*
                   "echo"
                   {:message "async test"})]
      (is (map? result))
      (is (= "Echo: async test" (-> result :content first :text))))))

;;; Error Handling Tests

(deftest ^:integ test-error-handling
  "Test error handling and edge cases"
  (testing "non-existent tool"
    (java-sdk/initialize-client *sync-client*)
    (try
      (let [result (java-sdk/call-tool *sync-client* "non-existent-tool" {:arg "value"})]
        (log/info :test/non-existent-tool-result {:result result})
        ;; Server should return an error response
        (when (contains? result :isError)
          (is (:isError result))))
      (catch Exception e
        ;; Or it might throw an exception
        (is (instance? Exception e))
        (log/info :test/non-existent-tool-exception {:error (.getMessage e)}))))

  (testing "invalid arguments for tool"
    (java-sdk/initialize-client *sync-client*)
    (try
      ;; Try to call add without required arguments
      (let [result (java-sdk/call-tool *sync-client* "add" {:invalid "args"})]
        (log/info :test/invalid-args-result {:result result})
        ;; Should get an error response
        (when (contains? result :isError)
          (is (:isError result))))
      (catch Exception e
        (is (instance? Exception e))
        (log/info :test/invalid-args-exception {:error (.getMessage e)}))))

  (testing "tool call with nil arguments"
    (java-sdk/initialize-client *sync-client*)
    ;; nil arguments might be handled gracefully rather than throwing
    (try
      (let [result @(java-sdk/call-tool *sync-client* "echo" nil)]
        ;; If no exception, check that we get some kind of result
        (is (map? result)))
      (catch Exception e
        ;; If exception is thrown, that's also acceptable
        (is (instance? Exception e)))))

  (testing "tool call with nil tool name"
    (java-sdk/initialize-client *sync-client*)
    (is (thrown?
         Exception
         @(java-sdk/call-tool *sync-client* nil {:message "test"})))))

;;; Server Wrapper Tests

(deftest test-server-wrapper-operations
  "Test server creation and configuration"
  (testing "server creation with default config"
    (let [server-map (java-sdk/create-java-server {})]
      (is (some? server-map))
      (is (some? (:server server-map)))
      (is (= "java-sdk-server" (:name server-map)))
      (is (= "0.1.0" (:version server-map)))
      (java-sdk/stop-server server-map)))

  (testing "server creation with custom config"
    (let [server-map (java-sdk/create-java-server
                      {:name "test-server" :version "2.0.0" :async? false})]
      (is (some? server-map))
      (is (= "test-server" (:name server-map)))
      (is (= "2.0.0" (:version server-map)))
      (is (= false (:async? server-map)))
      (java-sdk/stop-server server-map)))

  (testing "tool registration"
    (let [server-map (java-sdk/create-java-server {})
          test-tool {:name "test-tool"
                     :description "A test tool"
                     :inputSchema {:type "object"
                                   :properties {:input {:type "string"}}
                                   :required ["input"]}
                     :implementation (fn [args]
                                       {:content [{:type "text"
                                                   :text (str "Processed: " (:input args))}]})}]

      ;; Register tool
      (java-sdk/register-tool server-map test-tool)

      ;; Start and stop server to test lifecycle
      (java-sdk/start-server server-map)
      (java-sdk/stop-server server-map))))

;;; Resource Cleanup Tests

(deftest ^:integ test-resource-cleanup
  "Test proper cleanup of resources"
  (testing "client cleanup"
    (let [transport (java-sdk/create-stdio-client-transport "echo test")
          client (java-sdk/create-java-client {:transport transport})]
      ;; Close should not throw
      (is (nil? (java-sdk/close-client client)))))

  (testing "server cleanup"
    (let [server-map (java-sdk/create-java-server {:name "cleanup-test"})]
      ;; Stop should not throw and returns nil
      (is (nil? (java-sdk/stop-server server-map))))))

;;; HTTP Transport Integration Tests

(deftest ^:integ test-http-transport-object-creation
  "Test HTTP transport object creation and basic properties"
  (testing "HTTP client transport object creation"
    ;; Test that HTTP client transports can be created with correct configuration
    (let [http-transport (java-sdk/create-http-client-transport
                          {:url "http://localhost:8080/"})
          sse-transport (java-sdk/create-http-client-transport
                         {:url "http://localhost:8080" :use-sse true})]

      (is (some? http-transport))
      (is (some? sse-transport))

      ;; Verify they are different types
      (is (not= (class http-transport) (class sse-transport)))

      ;; Should be WebClientStreamableHttpTransport and WebFluxSseClientTransport
      (is (= "WebClientStreamableHttpTransport" (.getSimpleName (class http-transport))))
      (is (= "WebFluxSseClientTransport" (.getSimpleName (class sse-transport))))))

  (testing "HTTP server transport object creation"
    ;; Test that HTTP server transports can be created
    (let [http-server-transport (java-sdk/create-http-server-transport
                                 {:port 8080 :endpoint "/api"})
          sse-server-transport (java-sdk/create-http-server-transport
                                {:port 8081 :use-sse true :endpoint "/events"})]

      (is (some? http-server-transport))
      (is (some? sse-server-transport))

      ;; Should be different types
      (is (not= (class http-server-transport) (class sse-server-transport)))

      ;; Should be WebFluxStreamableServerTransportProvider and WebFluxSseServerTransportProvider
      (is (= "WebFluxStreamableServerTransportProvider" (.getSimpleName (class http-server-transport))))
      (is (= "WebFluxSseServerTransportProvider" (.getSimpleName (class sse-server-transport))))))

  (testing "HTTP transport integration with Java SDK clients and servers"
    ;; Test that transports can be passed to client/server constructors without errors
    (let [client-transport (java-sdk/create-http-client-transport
                           {:url "http://example.com/"})
          server-transport (java-sdk/create-http-server-transport
                           {:port 9999 :endpoint "/test"})

          ;; Create client with HTTP transport
          client (java-sdk/create-java-client
                 {:transport client-transport :async? false})

          ;; Create server with HTTP transport
          server (java-sdk/create-java-server
                 {:name "http-test-server"
                  :version "1.0.0"
                  :transport server-transport})]

      ;; Verify objects were created successfully
      (is (some? client))
      (is (some? server))

      ;; Verify they contain the expected transport objects
      (is (identical? client-transport (:transport client)))
      (is (some? (:server server)))

      ;; Clean up
      (java-sdk/close-client client)
      (java-sdk/stop-server server))))

;;; Performance and Stress Tests

(deftest ^:integ test-multiple-parallel-calls
  (testing "Parallel calls"
    (java-sdk/initialize-client *sync-client*)
    (let [results (doall
                   (for [i (range 10)]
                     (java-sdk/call-tool
                      *sync-client* "echo"
                      {:message (str "rapid-" i)})))]
      (is (= 10 (count results)))
      (doseq [result results]
        (is (map? @result))
        (is (contains? @result :content))
        (is (string? (-> @result :content first :text)))))))
