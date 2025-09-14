(ns mcp-clj.mcp-client.http-transport-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-server.core :as server]
   [mcp-clj.tools.core :as tools]
   [mcp-clj.log :as log]))

;;; Test Fixtures

(def ^:dynamic *server* nil)
(def ^:dynamic *client* nil)

(defn- get-server-port
  "Get the port of the running server"
  []
  (when-let [prom (:rpc-server-prom *server*)]
    (when-let [rpc-server @prom]
      (:port rpc-server))))

(defn- with-http-server
  "Start HTTP server for tests"
  [f]
  (let [server (server/create-server {:transport :http
                                      :port 0 ; Random port
                                      :tools {"test-echo" {:name "test-echo"
                                                           :description "Echo test tool"
                                                           :inputSchema {:type "object"
                                                                         :properties {:message {:type "string"}}
                                                                         :required ["message"]}
                                                           :implementation (fn [args]
                                                                             {:content [{:type "text"
                                                                                         :text (str "Echo: " (:message args))}]})}}})]
    (binding [*server* server]
      (try
        (f)
        (finally
          ((:stop server)))))))

(defn- with-http-client
  "Create HTTP client for tests"
  [f]
  (let [port (get-server-port)
        client (client/create-client {:url (str "http://localhost:" port)
                                      :client-info {:name "test-client"
                                                    :version "1.0.0"}
                                      :capabilities {:tools {}}
                                      :protocol-version "2024-11-05"
                                      :num-threads 2})]
    (binding [*client* client]
      (try
        ;; Wait for client to be ready
        (client/wait-for-ready *client* 5000)
        (f)
        (finally
          (client/close! client))))))

(use-fixtures :each with-http-server with-http-client)

;;; Tests

(deftest http-client-initialization-test
  ;; Test that HTTP client initializes correctly
  (testing "HTTP client initialization"
    (testing "connects to server successfully"
      (is (client/client-ready? *client*)))

    (testing "has correct client info"
      (let [info (client/get-client-info *client*)]
        (is (= "test-client" (:name info)))
        (is (= "1.0.0" (:version info)))))))

(deftest http-client-tool-discovery-test
  ;; Test tool discovery via HTTP
  (testing "HTTP client tool discovery"
    (testing "lists available tools"
      (let [result (client/list-tools *client*)]
        (is (map? result))
        (is (vector? (:tools result)))
        (is (= 1 (count (:tools result))))
        (let [tool (first (:tools result))]
          (is (= "test-echo" (:name tool)))
          (is (= "Echo test tool" (:description tool))))))))

(deftest http-client-tool-execution-test
  ;; Test tool execution via HTTP
  (testing "HTTP client tool execution"
    (testing "executes tools successfully"
      (let [result (client/call-tool *client* "test-echo" {:message "Hello, HTTP!"})]
        (is (vector? result))
        (is (= 1 (count result)))
        (let [content (first result)]
          (is (= "text" (:type content)))
          (is (= "Echo: Hello, HTTP!" (:text content))))))))

(deftest http-client-error-handling-test
  ;; Test error handling in HTTP client
  (testing "HTTP client error handling"
    (testing "handles tool not found"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Method not found"
                            (client/call-tool *client* "nonexistent-tool" {}))))

    (testing "handles invalid arguments"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tool execution failed"
                            (client/call-tool *client* "test-echo" {:wrong-param "value"}))))))

(deftest http-client-reconnection-test
  ;; Test that client can reconnect with same session
  (testing "HTTP client reconnection"
    (testing "maintains session across requests"
      ;; First call establishes session
      (let [result1 (client/call-tool *client* "test-echo" {:message "First"})
            ;; Second call should use same session
            result2 (client/call-tool *client* "test-echo" {:message "Second"})]
        (is (= "Echo: First" (-> result1 first :text)))
        (is (= "Echo: Second" (-> result2 first :text)))))))