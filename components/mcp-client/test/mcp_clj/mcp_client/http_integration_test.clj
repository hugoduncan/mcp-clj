(ns mcp-clj.mcp-client.http-integration-test
  "Integration tests for MCP client and server using HTTP transport"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-server.core :as server]
   [mcp-clj.log :as log]
   [clojure.string :as str]))

;;; Test Tools Definition

(def test-tools
  "Test tools for integration testing"
  {"echo" {:name "echo"
           :description "Echo the input message with optional prefix"
           :inputSchema {:type "object"
                         :properties {:message {:type "string"
                                                :description "Message to echo"}
                                      :prefix {:type "string"
                                               :description "Optional prefix for the message"}}
                         :required ["message"]}
           :implementation (fn [{:keys [message prefix]}]
                             {:content [{:type "text"
                                         :text (str (when prefix (str prefix ": ")) message)}]})}

   "add" {:name "add"
          :description "Add two numbers together"
          :inputSchema {:type "object"
                        :properties {:a {:type "number"
                                         :description "First number"}
                                     :b {:type "number"
                                         :description "Second number"}}
                        :required ["a" "b"]}
          :implementation (fn [{:keys [a b]}]
                            {:content [{:type "text"
                                        :text (str "Result: " (+ a b))}]})}

   "error" {:name "error"
            :description "Tool that always throws an error for testing error handling"
            :inputSchema {:type "object"
                          :properties {:message {:type "string"
                                                 :description "Error message to throw"}}
                          :required ["message"]}
            :implementation (fn [{:keys [message]}]
                              (throw (ex-info message {:type :test-error})))}

   "complex" {:name "complex"
              :description "Tool that returns complex structured data"
              :inputSchema {:type "object"
                            :properties {:count {:type "integer"
                                                 :description "Number of items to generate"
                                                 :minimum 1
                                                 :maximum 10}}
                            :required ["count"]}
              :implementation (fn [{:keys [count]}]
                                {:content [{:type "text"
                                            :text (str "Generated " count " items:")}
                                           {:type "text"
                                            :text (->> (range count)
                                                       (map #(str "Item " (inc %)))
                                                       (clojure.string/join ", "))}]})}})

;;; Test Helper Functions

(defn- get-server-port
  "Get the port of the running server, waiting for it to be ready"
  [server]
  (when-let [prom (:json-rpc-server server)]
    ;; Wait for the promise to be delivered with a timeout
    (let [rpc-server (deref prom 5000 nil)]
      (when rpc-server
        (:port rpc-server)))))

(defn- start-test-server
  "Start HTTP server with test tools"
  [& [opts]]
  (let [server (server/create-server (merge {:transport {:type :http :port 0} ; Random port
                                             :tools test-tools}
                                            opts))]
    (let [port (get-server-port server)]
      (when-not port
        ((:stop server))
        (throw (ex-info "Server failed to start or port not available" {})))
      (log/info :test/server-started {:port port})
      server)))

(defn- create-test-client
  "Create HTTP client connected to test server"
  [server]
  (let [port (get-server-port server)
        client (client/create-client
                {:transport {:type :http
                             :url (str "http://localhost:" port)
                             :num-threads 2}
                 :client-info {:name "integration-test-client"
                               :version "1.0.0"}
                 :capabilities {:tools {}}
                 :protocol-version "2024-11-05"})]
    ;; Wait for client to be ready
    (client/wait-for-ready client 5000)
    (log/info :test/client-connected {:ready (client/client-ready? client)})
    client))

(defmacro with-http-test-env
  "Execute body with HTTP server and client, ensuring cleanup"
  [[server-binding client-binding & [server-opts]] & body]
  `(let [~server-binding (start-test-server ~server-opts)]
     (try
       (let [~client-binding (create-test-client ~server-binding)]
         (try
           ~@body
           (finally
             (client/close! ~client-binding)
             (log/info :test/client-closed))))
       (finally
         ((:stop ~server-binding))
         (log/info :test/server-stopped)))))

;;; Integration Tests

(deftest ^:integ http-server-client-initialization-test
  ;; Test that both server and client initialize correctly over HTTP
  (with-http-test-env [server client]
    (testing "HTTP server and client initialization"
      (testing "server is running"
        (is (some? server))
        (is (pos? (get-server-port server))))

      (testing "client connects successfully"
        (is (client/client-ready? client)))

      (testing "client has correct configuration"
        (let [info (client/get-client-info client)]
          (is (= "integration-test-client" (:name (:client-info info))))
          (is (= "1.0.0" (:version (:client-info info)))))))))

(deftest ^:integ http-tool-discovery-integration-test
  ;; Test tool discovery across HTTP transport
  (with-http-test-env [server client]
    (testing "HTTP tool discovery integration"
      (testing "client can discover all server tools"
        (let [future (client/list-tools client)
              result @future] ; Deref the CompletableFuture
          (is (map? result))
          (is (vector? (:tools result)))
          (is (= 4 (count (:tools result))))

          ;; Verify all test tools are discovered
          (let [tool-names (set (map :name (:tools result)))]
            (is (contains? tool-names "echo"))
            (is (contains? tool-names "add"))
            (is (contains? tool-names "error"))
            (is (contains? tool-names "complex")))))

      (testing "tool definitions are complete"
        (let [tools (:tools @(client/list-tools client)) ; Deref the CompletableFuture
              echo-tool (first (filter #(= "echo" (:name %)) tools))]
          (is (some? echo-tool))
          (is (= "Echo the input message with optional prefix" (:description echo-tool)))
          (is (map? (:inputSchema echo-tool)))
          (is (= "object" (get-in echo-tool [:inputSchema :type]))))))))

(deftest ^:integ http-tool-execution-integration-test
  ;; Test tool execution across HTTP transport
  (with-http-test-env [server client]
    (testing "HTTP tool execution integration"
      (testing "simple echo tool execution"
        (let [future (client/call-tool client "echo" {:message "Hello, HTTP World!"})
              result (:content @future)] ; Deref the CompletableFuture and get :content
          (is (vector? result))
          (is (= 1 (count result)))
          (let [content (first result)]
            (is (= "text" (:type content)))
            (is (= "Hello, HTTP World!" (:text content))))))

      (testing "echo tool with prefix"
        (let [future (client/call-tool client "echo" {:message "Test" :prefix "INFO"})
              result (:content @future)] ; Deref the CompletableFuture and get :content
          (is (= "INFO: Test" (-> result first :text)))))

      (testing "numeric calculation tool"
        (let [future (client/call-tool client "add" {:a 15 :b 27})
              result (:content @future)] ; Deref the CompletableFuture and get :content
          (is (= "Result: 42" (-> result first :text)))))

      (testing "complex structured response"
        (let [future (client/call-tool client "complex" {:count 3})
              result (:content @future)] ; Deref the CompletableFuture and get :content
          (is (= 2 (count result)))
          (is (= "Generated 3 items:" (-> result first :text)))
          (is (= "Item 1, Item 2, Item 3" (-> result second :text))))))))

(deftest ^:integ http-error-handling-integration-test
  ;; Test error handling across HTTP transport
  (with-http-test-env [server client]
    (testing "HTTP error handling integration"
      (testing "tool not found error"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Tool execution failed: nonexistent-tool"
             @(client/call-tool client "nonexistent-tool" {}))))

      (testing "invalid tool arguments"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Tool execution failed"
             @(client/call-tool client "add" {:invalid "params"}))))

      (testing "tool that throws exception"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Tool execution failed"
             @(client/call-tool client "error" {:message "Test error"}))))

      (testing "missing required parameters"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Tool execution failed"
             @(client/call-tool client "echo" {})))))))

(deftest ^:integ http-session-management-integration-test
  ;; Test session management across HTTP transport
  (with-http-test-env [server client]
    (testing "HTTP session management integration"
      (testing "multiple requests use same session"
        ;; Make multiple tool calls to verify session persistence
        (let [future1 (client/call-tool client "echo" {:message "First call"})
              future2 (client/call-tool client "echo" {:message "Second call"})
              future3 (client/call-tool client "add" {:a 10 :b 5})
              result1 (:content @future1)
              result2 (:content @future2)
              result3 (:content @future3)]

          (is (= "First call" (-> result1 first :text)))
          (is (= "Second call" (-> result2 first :text)))
          (is (= "Result: 15" (-> result3 first :text)))))

      (testing "tool discovery works multiple times"
        ;; Call list-tools multiple times to verify consistency
        (let [tools1 (:tools @(client/list-tools client))
              tools2 (:tools @(client/list-tools client))]
          (is (= (count tools1) (count tools2)))
          (is (= (set (map :name tools1)) (set (map :name tools2)))))))))

(deftest ^:integ http-concurrent-requests-integration-test
  ;; Test concurrent requests over HTTP transport
  (with-http-test-env [server client]
    (testing "HTTP concurrent requests integration"
      (testing "concurrent tool calls work correctly"
        (let [futures (doall
                       (for [i (range 5)]
                         (future
                           (:content @(client/call-tool client "add" {:a i :b (+ i 10)})))))
              results (mapv deref futures)]

          ;; Verify all futures completed successfully
          (is (= 5 (count results)))

          ;; Verify results are correct
          (doseq [[i result] (map-indexed vector results)]
            (is (= (str "Result: " (+ i (+ i 10))) result)))))

      (testing "concurrent tool discovery works"
        (let [futures (doall
                       (for [_ (range 3)]
                         (future @(client/list-tools client))))
              results (mapv deref futures)]

          ;; All should return the same tool count
          (is (every? #(= 4 (count (:tools %))) results))

          ;; All should have the same tool names
          (let [tool-names-sets (map #(->> % :tools (map :name) set) results)]
            (is (apply = tool-names-sets))))))))

(deftest ^:integ http-transport-performance-test
  ;; Basic performance test for HTTP transport
  (with-http-test-env [server client]
    (testing "HTTP transport performance"
      (testing "rapid successive calls"
        (let [start-time (System/nanoTime)
              call-count 20]

          ;; Make many rapid calls
          (dotimes [i call-count]
            @(client/call-tool client "echo" {:message (str "Call " i)}))

          (let [elapsed-ms (/ (- (System/nanoTime) start-time) 1000000.0)]
            (log/info :test/performance {:calls call-count
                                         :elapsed-ms elapsed-ms
                                         :calls-per-second (/ call-count (/ elapsed-ms 1000))})

            ;; Should complete reasonably quickly (less than 5 seconds for 20 calls)
            (is (< elapsed-ms 5000)))))

      (testing "large response handling"
        ;; Test with the maximum count for complex tool
        (let [future (client/call-tool client "complex" {:count 10})
              result (:content @future)]
          (is (= 2 (count result)))
          (is (= "Generated 10 items:" (-> result first :text)))
          ;; Should contain all 10 items
          (is (str/includes? (-> result second :text) "Item 10")))))))
