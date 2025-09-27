(ns mcp-clj.mcp-server.sdk-client-http-integration-test
  "Integration tests using Java SDK HTTP client against Clojure MCP server.

   This tests cross-implementation compatibility by using the Java SDK MCP client
   with HTTP transport to communicate with our Clojure MCP server using the new
   HTTP transport implementation.

   Tests server behavior from the client perspective - ensuring our HTTP server
   responds correctly to standard MCP operations from a real SDK client."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [mcp-clj.java-sdk.interop :as java-sdk]
   [mcp-clj.log :as log]
   [mcp-clj.mcp-server.core :as mcp-core])
  (:import    [java.lang AutoCloseable]))

;;; Test Fixtures and Helpers

(def ^:private ^:dynamic *server* nil)
(def ^:private ^:dynamic *server-port* nil)

(defn- with-http-server
  "Test fixture that creates an MCP server with HTTP transport"
  [f]
  (let [server (mcp-core/create-server {:transport {:type :http :port 0}})
        port (-> server :json-rpc-server deref :port)]
    (try
      (binding [*server* server
                *server-port* port]
        (log/info :http-integration-test/server-started {:port port})
        (f))
      (finally
        ((:stop server))
        (log/info :http-integration-test/server-stopped)))))

(use-fixtures :each with-http-server)

(defn- create-http-client
  "Create SDK client connected to our Clojure MCP server via HTTP"
  ^AutoCloseable [async?]
  (let [transport (java-sdk/create-http-client-transport
                   {:url                        (str "http://localhost:" *server-port* "/")
                    :use-sse                    false
                    :open-connection-on-startup false
                    :resumable-streams          false})
        client    (java-sdk/create-java-client
                   {:transport transport
                    :async?    async?})]
    client))

;;; Server Behavior Tests via HTTP

(deftest ^:integ test-http-server-initialization
  ;; Test MCP server initialization over HTTP transport
  (testing "Clojure MCP server initialization with Java SDK HTTP client"
    (with-open [client (create-http-client false)]
      ;; Initialize connection
      (let [result (java-sdk/initialize-client client)]
        (is (some? result))
        (is (contains? result :serverInfo))
        (is (= "mcp-clj" (get-in result [:serverInfo :name])))
        (is (contains? result :protocolVersion))
        (is (contains? result :capabilities))))))

(deftest ^:integ test-http-server-tool-discovery
  ;; Test tool discovery over HTTP transport
  (testing "server tool discovery via Java SDK HTTP client"
    (with-open [client (create-http-client false)]
      ;; Initialize connection
      (java-sdk/initialize-client client)

      (testing "list server tools"
        (let [tools-response @(java-sdk/list-tools client)]
          (is (map? tools-response))
          (is (contains? tools-response :tools))
          (is (sequential? (:tools tools-response)))

          ;; Should have our default tools
          (let [tool-names (set (map :name (:tools tools-response)))]
            (is (contains? tool-names "clj-eval"))
            (is (contains? tool-names "ls"))

            ;; Each tool should have proper schema
            (doseq [tool (:tools tools-response)]
              (is (contains? tool :name))
              (is (contains? tool :description))
              (is (some? (:name tool)))
              (is (some? (:description tool))))))))))

(deftest ^:integ test-http-server-tool-execution
  ;; Test tool execution over HTTP transport
  (testing "server tool execution via Java SDK HTTP client"
    (with-open [client (create-http-client false)]
      ;; Initialize connection
      (java-sdk/initialize-client client)

      (testing "ls tool call over HTTP"
        (let [result @(java-sdk/call-tool client "ls" {:path "."})]
          (is (map? result))
          (is (contains? result :content))
          (is (sequential? (:content result)))

          (let [first-content (first (:content result))]
            (is (= "text" (:type first-content)))
            (is (string? (:text first-content)))
            (is (> (count (:text first-content)) 0)))))

      (testing "clj-eval tool call over HTTP"
        (let [result @(java-sdk/call-tool
                       client
                       "clj-eval"
                       {:code "(+ 1 2 3)"})]
          (is (map? result))
          (is (contains? result :content))

          (let [first-content (first (:content result))]
            (is (= "text" (:type first-content)))
            (is (= "6" (:text first-content)))))))))

(deftest ^:integ test-http-server-error-handling
  ;; Test error handling over HTTP transport
  (testing "server error handling via Java SDK HTTP client"
    (with-open [client (create-http-client false)]
      ;; Initialize connection
      (java-sdk/initialize-client client)

      (testing "non-existent tool call over HTTP"
        (let [result @(java-sdk/call-tool
                       client
                       "non-existent-tool"
                       {:param "value"})]
          (is (contains? result :content))
          (when (contains? result :isError)
            (is (:isError result)))))

      (testing "invalid tool arguments for clj-eval over HTTP"
        ;; Try to call clj-eval without required code parameter
        (let [result @(java-sdk/call-tool client "clj-eval" {:invalid "args"})]
          ;; Should get an error response
          (is (contains? result :content))
          (when (contains? result :isError)
            (is (:isError result))))))))

(deftest ^:integ test-http-server-concurrent-operations
  ;; Test concurrent operations over HTTP transport
  (testing "server concurrent operations via Java SDK HTTP client"
    (with-open [client (create-http-client true)] ; Use async client for concurrency
      ;; Initialize connection
      (java-sdk/initialize-client client)

      (testing "multiple concurrent tool calls over HTTP"
        (let [futures (doall
                       (for [i (range 3)]
                         (java-sdk/call-tool
                          client "clj-eval"
                          {:code (str "(+ " i " 10)")})))
              results (mapv deref futures)]

          ;; Wait for all to complete
          (is (= 3 (count results)))

          ;; Each should be a valid result
          (doseq [result results]
            (is (map? result))
            (is (contains? result :content))
            (is (sequential? (:content result))))

          (log/info :http-integration-test/concurrent-results
            {:count (count results)})))

      (testing "mixed operation types concurrently over HTTP"
        (let [list-future (java-sdk/list-tools client)
              ls-future   (java-sdk/call-tool client "ls" {:path "."})
              eval-future (java-sdk/call-tool client "clj-eval" {:code "42"})
              list-result @list-future
              ls-result   @ls-future
              eval-result @eval-future]

          ;; List tools result
          (is (map? list-result))
          (is (contains? list-result :tools))

          ;; ls result
          (is (map? ls-result))
          (is (contains? ls-result :content))

          ;; eval result
          (is (= "42" (-> eval-result :content first :text)))

          (log/info :http-integration-test/mixed-concurrent-success))))))

(deftest ^:integ test-http-server-session-robustness
  ;; Test session robustness over HTTP transport
  (testing "server session robustness via Java SDK HTTP client"
    (with-open [client (create-http-client false)]
      ;; Initialize connection
      (java-sdk/initialize-client client)

      (testing "server remains functional after errors over HTTP"
        ;; Try a failing operation
        (try
          (java-sdk/call-tool client "non-existent-tool" {})
          (catch Exception _))

        ;; Server should still work for valid operations
        (let [result @(java-sdk/call-tool
                       client
                       "clj-eval"
                       {:code "(str \"after-error\")"})]
          (is (= "after-error" (-> result :content first :text))))

        ;; Tool listing should still work
        (let [tools @(java-sdk/list-tools client)]
          (is (map? tools))
          (is (sequential? (:tools tools)))))

      (testing "server handles multiple sequential operations over HTTP"
        (let [result1 @(java-sdk/call-tool client "clj-eval" {:code "1"})
              result2 @(java-sdk/call-tool client "clj-eval" {:code "2"})
              result3 @(java-sdk/call-tool client "clj-eval" {:code "3"})]

          (is (= "1" (-> result1 :content first :text)))
          (is (= "2" (-> result2 :content first :text)))
          (is (= "3" (-> result3 :content first :text))))))))

(deftest ^:integ test-http-server-with-sse-disabled
  ;; Test HTTP server without SSE streaming transport
  (testing "HTTP server with SSE disabled via Java SDK client"
    (let [transport (java-sdk/create-http-client-transport
                     {:url                        (str "http://localhost:" *server-port* "/")
                      :use-sse                    false ; Explicitly disable SSE
                      :open-connection-on-startup false})
          client    (java-sdk/create-java-client
                     {:transport transport
                      :async?    false})]
      (try
        ;; Initialize connection
        (let [init-result (java-sdk/initialize-client client)]
          (is (some? init-result))
          (is (= "mcp-clj" (get-in init-result [:serverInfo :name]))))

        ;; Test tool execution without SSE
        (let [result @(java-sdk/call-tool client "clj-eval" {:code "(* 6 7)"})]
          (is (= "42" (-> result :content first :text))))

        (finally
          (java-sdk/close-client client))))))

(deftest ^:integ test-http-server-origin-validation
  ;; Test HTTP server with origin validation
  (testing "HTTP server with origin validation"
    (let [;; Create a server with restricted origins
          server-with-origins (mcp-core/create-server
                               {:transport {:type :http
                                           :port 0
                                           :allowed-origins ["https://trusted.example.com"]}})
          port (-> server-with-origins :json-rpc-server deref :port)]
      (try
        ;; Try to connect - Java SDK client doesn't set Origin headers by default
        ;; so this should succeed (no Origin header = allowed by default)
        (let [transport (java-sdk/create-http-client-transport
                         {:url (str "http://localhost:" port "/")
                          :use-sse false})
              client (java-sdk/create-java-client
                      {:transport transport
                       :async? false})]
          (try
            ;; This should succeed since no Origin header = allowed
            (let [result (java-sdk/initialize-client client)]
              (is (some? result) "Client should initialize successfully without Origin header")
              (is (= "mcp-clj" (get-in result [:serverInfo :name]))))
            (finally
              (java-sdk/close-client client))))

        (finally
          ((:stop server-with-origins)))))))

(comment
  ;; Manual testing examples

  ;; Run all HTTP integration tests
  (clojure.test/run-tests 'mcp-clj.mcp-server.sdk-client-http-integration-test)

  ;; Test specific functionality with HTTP transport
  (let [server (mcp-core/create-server {:transport {:type :http :port 8080}})]
    (try
      (let [transport (java-sdk/create-http-client-transport
                       {:url "http://localhost:8080/"})
            client    (java-sdk/create-java-client
                       {:transport transport :async? false})]
        (try
          (println "Init:" (java-sdk/initialize-client client))
          (println "Tools:" (java-sdk/list-tools client))
          (println "Eval:" (java-sdk/call-tool client "clj-eval" {:code "(+ 1 2)"}))
          (finally
            (java-sdk/close-client client))))
      (finally
        ((:stop server))))))
