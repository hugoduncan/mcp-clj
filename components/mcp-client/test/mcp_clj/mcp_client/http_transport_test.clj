(ns mcp-clj.mcp-client.http-transport-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.mcp-client.core :as client]
    [mcp-clj.mcp-server.core :as server]))

;; Test Helpers

(defn- create-test-server
  "Create and start a test HTTP server with optional tools"
  ([]
   (create-test-server nil))
  ([tools]
   (server/create-server
     {:transport {:type :http :port 0} ; Random port
      :tools     (or tools
                     {"test-echo"
                      {:name        "test-echo"
                       :description "Echo test tool"
                       :inputSchema {:type       "object"
                                     :properties {:message {:type "string"}}
                                     :required   ["message"]}
                       :implementation
                       (fn [_context args]
                         {:content
                          [{:type "text"
                            :text (str "Echo: " (:message args))}]
                          :isError false})}})})))

(defn- get-server-port
  "Get the port of the running server, waiting for it to be ready"
  [server]
  (when-let [prom (:json-rpc-server server)]
    ;; Wait for server to start with timeout
    (let [rpc-server (deref prom 5000 nil)]
      (when rpc-server
        ;; Give the server a moment to fully bind to the port
        (Thread/sleep 100)
        (:port rpc-server)))))

(defn- create-test-client
  "Create and initialize a test HTTP client for the given server"
  ([server]
   (create-test-client server {}))
  ([server opts]
   (let [port (get-server-port server)]
     (when-not port
       (throw (ex-info "Server did not start - no port available" {})))
     (let [client (client/create-client
                    (merge
                      {:transport {:type :http
                                   :url (str "http://localhost:" port)
                                   :num-threads 2}
                       :client-info {:name "test-client"
                                     :version "1.0.0"}
                       :capabilities {:tools {}}
                       :protocol-version "2024-11-05"}
                      opts))]
       ;; Wait for client to be ready
       (client/wait-for-ready client 5000)
       client))))

(defmacro with-http-test-env
  "Set up HTTP server and client for a test, ensuring cleanup"
  [[server-sym client-sym & {:keys [server-tools client-opts]}] & body]
  `(let [~server-sym (create-test-server ~server-tools)]
     (try
       (let [~client-sym (create-test-client ~server-sym ~client-opts)]
         (try
           ~@body
           (finally
             (client/close! ~client-sym))))
       (finally
         ((:stop ~server-sym))))))

;; Tests

(deftest ^:integ http-client-initialization-test
  ;; Test that HTTP client initializes correctly
  (testing "HTTP client initialization"
    (with-http-test-env [server client]
      (testing "connects to server successfully"
        (is (client/client-ready? client)))

      (testing "has correct client info"
        (let [info (client/get-client-info client)
              client-info (:client-info info)]
          (is (= "test-client" (:name client-info)))
          (is (= "1.0.0" (:version client-info))))))))

(deftest ^:integ http-client-tool-discovery-test
  ;; Test tool discovery via HTTP
  (testing "HTTP client tool discovery"
    (with-http-test-env [server client]
      (testing "lists available tools"
        (let [result @(client/list-tools client)]
          (is (map? result))
          (is (vector? (:tools result)))
          (is (= 1 (count (:tools result))))
          (let [tool (first (:tools result))]
            (is (= "test-echo" (:name tool)))
            (is (= "Echo test tool" (:description tool)))))))))

(deftest ^:integ http-client-tool-execution-test
  ;; Test tool execution via HTTP
  (testing "HTTP client tool execution"
    (with-http-test-env [server client]
      (testing "executes tools successfully"
        (let [result @(client/call-tool
                        client
                        "test-echo"
                        {:message "Hello, HTTP!"})]
          (is (map? result))
          (is (not (:isError result)))
          (is (= 1 (count (:content result))))
          (let [content (first (:content result))]
            (is (= "text" (:type content)))
            (is (= "Echo: Hello, HTTP!" (:text content)))))))))

(deftest ^:integ http-client-error-handling-test
  ;; Test error handling in HTTP client
  (testing "HTTP client error handling"
    (with-http-test-env [server client]
      (testing "handles tool not found"
        (is (thrown-with-msg?
              java.util.concurrent.ExecutionException
              #"Tool call failed: nonexistent-tool"
              @(client/call-tool client "nonexistent-tool" {}))))

      (testing "handles invalid arguments"
        (let [resp @(client/call-tool
                      client
                      "test-echo"
                      {:wrong-param "value"})]
          (is (:isError resp))
          (is (= "Missing args: [:message], found #{:wrong-param}"
                 (-> resp :content first :text))))))))

(deftest ^:integ http-client-reconnection-test
  ;; Test that client can reconnect with same session
  (testing "HTTP client reconnection"
    (with-http-test-env [server client]
      (testing "maintains session across requests"
        ;; First call establishes session
        (let [result1 @(client/call-tool client "test-echo" {:message "First"})
              ;; Second call should use same session
              result2 @(client/call-tool client "test-echo" {:message "Second"})]
          (is (= "Echo: First" (-> result1 :content first :text)))
          (is (= "Echo: Second" (-> result2 :content first :text))))))))
