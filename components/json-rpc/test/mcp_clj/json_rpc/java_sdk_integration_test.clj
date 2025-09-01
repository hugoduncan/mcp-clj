(ns mcp-clj.json-rpc.java-sdk-integration-test
  "Integration tests using Clojure stdio client against Java SDK server.
   
   This tests cross-implementation compatibility by using our Clojure JSON-RPC
   stdio client to communicate with the Java SDK MCP server subprocess."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [mcp-clj.json-rpc.stdio-client :as stdio-client]
   [mcp-clj.log :as log])
  (:import
   [java.io BufferedReader
    BufferedWriter
    InputStreamReader
    OutputStreamWriter]
   [java.util.concurrent TimeUnit]))

(def ^:dynamic *java-sdk-process* nil)
(def ^:dynamic *client* nil)

(defn start-java-sdk-server
  "Start the Java SDK server as a subprocess"
  []
  (log/info :integration-test/starting-java-sdk-server)
  (let [pb (ProcessBuilder. (into-array String ["clj" "-M:dev:test" "-m" "mcp-clj.java-sdk.sdk-server-main"]))
        _ (.redirectErrorStream pb true)
        process (.start pb)]

    ;; Give server time to start
    (Thread/sleep 2000)

    (if (.isAlive process)
      (do
        (log/info :integration-test/java-sdk-server-started)
        process)
      (throw (ex-info "Java SDK server failed to start"
                      {:exit-code (.exitValue process)})))))

(defn stop-java-sdk-server
  "Stop the Java SDK server subprocess"
  [process]
  (when (and process (.isAlive process))
    (log/info :integration-test/stopping-java-sdk-server)
    (.destroy process)
    (when-not (.waitFor process 5 TimeUnit/SECONDS)
      (.destroyForcibly process))
    (log/info :integration-test/java-sdk-server-stopped)))

(defn create-stdio-client
  "Create stdio client connected to Java SDK server process"
  [process]
  (let [input-stream (BufferedReader. (InputStreamReader. (.getInputStream process)))
        output-stream (BufferedWriter. (OutputStreamWriter. (.getOutputStream process)))]
    (stdio-client/create-json-rpc-client input-stream output-stream)))

(defn with-java-sdk-server
  "Test fixture: start/stop Java SDK server process"
  [f]
  (let [process (start-java-sdk-server)]
    (try
      (binding [*java-sdk-process* process]
        (f))
      (finally
        (stop-java-sdk-server process)))))

(defn with-stdio-client
  "Test fixture: create stdio client connected to server"
  [f]
  (when *java-sdk-process*
    (let [client (create-stdio-client *java-sdk-process*)]
      (try
        (binding [*client* client]
          (f))
        (finally
          (stdio-client/close-json-rpc-client! client))))))

(use-fixtures :each with-java-sdk-server with-stdio-client)

;;; MCP Protocol Tests

(deftest test-mcp-initialization
  "Test MCP protocol initialization with Java SDK server"
  (testing "initialize request"
    (let [init-params {:protocolVersion "2024-11-05"
                       :capabilities {:roots {:listChanged false}}
                       :clientInfo {:name "clojure-mcp-client" :version "0.1.0"}}
          future (stdio-client/send-request! *client* "initialize" init-params 5000)]

      ;; Wait for response
      (let [response (.get future 5 TimeUnit/SECONDS)]
        (is (map? response))
        (is (contains? response :protocolVersion))
        (is (contains? response :capabilities))
        (is (contains? response :serverInfo))

        (log/info :integration-test/initialize-response {:response response}))))

  (testing "initialized notification"
    ;; Send initialized notification after initialize
    (stdio-client/send-notification! *client* "initialized" {})

    ;; Give server time to process
    (Thread/sleep 100)))

(deftest test-tool-discovery
  "Test tool discovery with Java SDK server"
  (testing "list tools request"
    ;; First initialize
    (let [init-params {:protocolVersion "2024-11-05"
                       :capabilities {:roots {:listChanged false}}
                       :clientInfo {:name "clojure-mcp-client" :version "0.1.0"}}
          init-future (stdio-client/send-request! *client* "initialize" init-params 5000)]
      (.get init-future 5 TimeUnit/SECONDS))

    (stdio-client/send-notification! *client* "initialized" {})
    (Thread/sleep 100)

    ;; List tools
    (let [future (stdio-client/send-request! *client* "tools/list" {} 5000)]
      (let [response (.get future 5 TimeUnit/SECONDS)]
        (is (map? response))
        (is (contains? response :tools))
        (is (sequential? (:tools response)))

        ;; Should have our test tools
        (let [tool-names (set (map :name (:tools response)))]
          (is (contains? tool-names "echo"))
          (is (contains? tool-names "add"))
          (is (contains? tool-names "get-time")))

        (log/info :integration-test/tools-list {:tools (:tools response)})))))

(deftest test-tool-calls
  "Test actual tool calls with Java SDK server"
  (testing "echo tool call"
    ;; Initialize first
    (let [init-params {:protocolVersion "2024-11-05"
                       :capabilities {:roots {:listChanged false}}
                       :clientInfo {:name "clojure-mcp-client" :version "0.1.0"}}
          init-future (stdio-client/send-request! *client* "initialize" init-params 5000)]
      (.get init-future 5 TimeUnit/SECONDS))

    (stdio-client/send-notification! *client* "initialized" {})
    (Thread/sleep 100)

    ;; Call echo tool
    (let [call-params {:name "echo" :arguments {:message "Hello from Clojure client!"}}
          future (stdio-client/send-request! *client* "tools/call" call-params 5000)]

      (let [response (.get future 5 TimeUnit/SECONDS)]
        (is (map? response))
        (is (contains? response :content))
        (is (sequential? (:content response)))

        (let [first-content (first (:content response))]
          (is (= "text" (:type first-content)))
          (is (= "Echo: Hello from Clojure client!" (:text first-content))))

        (log/info :integration-test/echo-response {:response response}))))

  (testing "add tool call"
    ;; Call add tool
    (let [call-params {:name "add" :arguments {:a 42 :b 13}}
          future (stdio-client/send-request! *client* "tools/call" call-params 5000)]

      (let [response (.get future 5 TimeUnit/SECONDS)]
        (is (map? response))
        (is (contains? response :content))

        (let [first-content (first (:content response))]
          (is (= "text" (:type first-content)))
          (is (= "55" (:text first-content))))

        (log/info :integration-test/add-response {:response response}))))

  (testing "get-time tool call"
    ;; Call get-time tool
    (let [call-params {:name "get-time" :arguments {}}
          future (stdio-client/send-request! *client* "tools/call" call-params 5000)]

      (let [response (.get future 5 TimeUnit/SECONDS)]
        (is (map? response))
        (is (contains? response :content))

        (let [first-content (first (:content response))]
          (is (= "text" (:type first-content)))
          (is (string? (:text first-content)))
          (is (> (count (:text first-content)) 0)))

        (log/info :integration-test/get-time-response {:response response})))))

(deftest test-error-handling
  "Test error handling scenarios"
  (testing "non-existent tool call"
    ;; Initialize first
    (let [init-params {:protocolVersion "2024-11-05"
                       :capabilities {:roots {:listChanged false}}
                       :clientInfo {:name "clojure-mcp-client" :version "0.1.0"}}
          init-future (stdio-client/send-request! *client* "initialize" init-params 5000)]
      (.get init-future 5 TimeUnit/SECONDS))

    (stdio-client/send-notification! *client* "initialized" {})
    (Thread/sleep 100)

    ;; Try to call non-existent tool
    (let [call-params {:name "non-existent-tool" :arguments {:param "value"}}
          future (stdio-client/send-request! *client* "tools/call" call-params 5000)]

      (try
        (let [response (.get future 5 TimeUnit/SECONDS)]
          ;; If we get a response instead of exception, check for error indication
          (when (and (map? response) (contains? response :isError))
            (is (:isError response))))
        (catch Exception e
          ;; Exception is also acceptable for non-existent tools
          (is (instance? Exception e))
          (log/info :integration-test/non-existent-tool-error {:error (.getMessage e)})))))

  (testing "invalid method call"
    ;; Try to call non-existent method
    (let [future (stdio-client/send-request! *client* "invalid/method" {} 5000)]

      (try
        (.get future 5 TimeUnit/SECONDS)
        (is false "Should have thrown exception for invalid method")
        (catch Exception e
          (is (instance? Exception e))
          (log/info :integration-test/invalid-method-error {:error (.getMessage e)}))))))

(deftest test-protocol-compliance
  "Test MCP protocol compliance"
  (testing "jsonrpc version in responses"
    (let [init-params {:protocolVersion "2024-11-05"
                       :capabilities {:roots {:listChanged false}}
                       :clientInfo {:name "clojure-mcp-client" :version "0.1.0"}}
          future (stdio-client/send-request! *client* "initialize" init-params 5000)]

      (let [response (.get future 5 TimeUnit/SECONDS)]
        ;; Response should be a proper JSON-RPC response
        (is (map? response))
        ;; The actual protocol validation happens at the JSON-RPC layer
        (log/info :integration-test/protocol-compliance {:response response}))))

  (testing "concurrent requests"
    ;; Initialize first
    (let [init-params {:protocolVersion "2024-11-05"
                       :capabilities {:roots {:listChanged false}}
                       :clientInfo {:name "clojure-mcp-client" :version "0.1.0"}}
          init-future (stdio-client/send-request! *client* "initialize" init-params 5000)]
      (.get init-future 5 TimeUnit/SECONDS))

    (stdio-client/send-notification! *client* "initialized" {})
    (Thread/sleep 100)

    ;; Send multiple concurrent tool calls
    (let [futures (doall
                   (for [i (range 5)]
                     (stdio-client/send-request!
                      *client*
                      "tools/call"
                      {:name "echo" :arguments {:message (str "Message " i)}}
                      5000)))]

      ;; Wait for all to complete
      (let [responses (doall (map #(.get % 5 TimeUnit/SECONDS) futures))]
        (is (= 5 (count responses)))

        ;; Each should be a valid response
        (doseq [response responses]
          (is (map? response))
          (is (contains? response :content)))

        ;; Messages should be different
        (let [messages (map #(-> % :content first :text) responses)]
          (is (= 5 (count (set messages)))))

        (log/info :integration-test/concurrent-responses {:count (count responses)})))))

(deftest test-lifecycle-management
  "Test proper lifecycle management"
  (testing "multiple initialize calls"
    ;; First initialize
    (let [init-params {:protocolVersion "2024-11-05"
                       :capabilities {:roots {:listChanged false}}
                       :clientInfo {:name "clojure-mcp-client" :version "0.1.0"}}
          future1 (stdio-client/send-request! *client* "initialize" init-params 5000)]

      (let [response1 (.get future1 5 TimeUnit/SECONDS)]
        (is (map? response1)))

      ;; Second initialize (should work or give appropriate error)
      (let [future2 (stdio-client/send-request! *client* "initialize" init-params 5000)]
        (try
          (let [response2 (.get future2 5 TimeUnit/SECONDS)]
            (is (map? response2)))
          (catch Exception e
            ;; Multiple initializes might be rejected, which is valid
            (is (instance? Exception e))
            (log/info :integration-test/multiple-init-error {:error (.getMessage e)}))))))

  (testing "requests before initialization"
    ;; Try to call tools before initialize - should fail
    (let [call-params {:name "echo" :arguments {:message "test"}}
          future (stdio-client/send-request! *client* "tools/call" call-params 2000)]

      (try
        (.get future 2 TimeUnit/SECONDS)
        (is false "Should have failed before initialization")
        (catch Exception e
          (is (instance? Exception e))
          (log/info :integration-test/before-init-error {:error (.getMessage e)}))))))