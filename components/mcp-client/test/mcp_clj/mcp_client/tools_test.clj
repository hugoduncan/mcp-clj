(ns mcp-clj.mcp-client.tools-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.client-transport.factory :as client-transport-factory]
   [mcp-clj.in-memory-transport.shared :as shared]
   [mcp-clj.mcp-client.tools :as tools]
   [mcp-clj.server-transport.factory :as server-transport-factory])
  (:import
   [java.util.concurrent
    CompletableFuture
    TimeUnit]))

;;; Transport registration function for robust test isolation
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

;;; Ensure transport is registered at namespace load time
(ensure-in-memory-transport-registered!)

(defn- stop-server!
  "Stop an in-memory server using lazy-loaded function"
  [server]
  (require 'mcp-clj.in-memory-transport.server)
  ((ns-resolve 'mcp-clj.in-memory-transport.server 'stop!) server))

(defn- create-test-client-with-handler
  "Create a test client with in-memory transport and custom handler"
  [handler]
  ;; Ensure transport is registered before creating client/server
  (ensure-in-memory-transport-registered!)
  (let [shared-transport (shared/create-shared-transport)
        session (atom {})
        ;; Create server using lazy-loaded function
        create-server-fn (do
                           (require 'mcp-clj.in-memory-transport.server)
                           (ns-resolve
                            'mcp-clj.in-memory-transport.server
                            'create-in-memory-server))
        server (create-server-fn
                {:shared shared-transport}
                {"tools/list" handler
                 "tools/call" handler})
        ;; Create transport using lazy-loaded function
        create-transport-fn (do (require 'mcp-clj.in-memory-transport.client)
                                (ns-resolve
                                 'mcp-clj.in-memory-transport.client
                                 'create-transport))
        transport (create-transport-fn {:shared shared-transport})]
    {:session session
     :transport transport
     :server server
     :shared-transport shared-transport}))

(deftest call-tool-success-test
  ;; Tests successful tool execution returns CompletableFuture with content
  (testing "successful tool execution returns CompletableFuture with content"
    (let [handler (fn [method params]
                    {:content "success result" :isError false})
          client  (create-test-client-with-handler handler)]
      (try
        (let [future (tools/call-tool-impl client "test-tool" {})]
          (is (instance? CompletableFuture future))
          (is (= {:content "success result", :isError false}
                 (.get future 1 TimeUnit/SECONDS))))
        (finally
          (stop-server! (:server client))))))

  (testing "tool execution with no JSON content parsing"
    (let [handler (fn [method params]
                    {:content [{:type "text" :text "{\"key\": \"value\"}"}]
                     :isError false})
          client  (create-test-client-with-handler handler)]
      (try
        ;; Should return the parsed content - access the data field from first item
        (let [future (tools/call-tool-impl client "test-tool" {})
              result (.get future 1 TimeUnit/SECONDS)]
          (is (vector? (:content result)))
          (is (= 1 (count (:content result))))
          (let [first-item (first (:content result))]
            (is (= "text" (:type first-item)))
            (is (= "{\"key\": \"value\"}" (:text first-item)))))
        (finally
          (stop-server! (:server client)))))))

(deftest call-tool-error-test
  ;; Tests tool execution returns future with error map when isError is true
  (testing "tool execution returns future with error map when isError is true"
    (let [handler (fn [method params]
                    {:content "error message" :isError true})
          client (create-test-client-with-handler handler)]
      (try
        (let [future (tools/call-tool-impl client "test-tool" {})
              result (.get future 1 TimeUnit/SECONDS)]
          (is (instance? CompletableFuture future))
          (is (map? result))
          (is (true? (:isError result)))
          (is (= "error message" (:content result))))
        (finally
          (stop-server! (:server client)))))))

(deftest tools-cache-test
  ;; Tests tools cache creation and access
  (testing "tools cache creation and access"
    (let [client {:session (atom {})}]
      ;; Cache should be created on first access
      (is (nil? @(#'tools/get-tools-cache client)))

      ;; Cache tools
      (let [test-tools [{:name "test-tool" :description "A test tool"}]]
        (#'tools/cache-tools! client test-tools)
        (is (= test-tools (#'tools/get-cached-tools client)))))))

(deftest list-tools-impl-test
  ;; Tests successful tools list request returns CompletableFuture
  (testing "successful tools list request returns CompletableFuture"
    (let [mock-tools [{:name "echo" :description "Echo tool"}
                      {:name "calc" :description "Calculator tool"}]
          handler (fn [method params]
                    {:tools mock-tools})
          client (create-test-client-with-handler handler)]
      (try
        (let [future (tools/list-tools-impl client)]
          (is (instance? CompletableFuture future))
          (let [result (.get future 1 TimeUnit/SECONDS)]
            (is (= mock-tools (:tools result)))
            ;; Tools should be cached
            (is (= mock-tools (#'tools/get-cached-tools client)))))
        (finally
          (stop-server! (:server client))))))

  (testing "error handling in tools list returns failed future"
    (let [handler (fn [method params]
                    (throw (ex-info "Connection failed" {})))
          client (create-test-client-with-handler handler)]
      (try
        (let [future (tools/list-tools-impl client)]
          (is (instance? CompletableFuture future))
          ;; The in-memory transport will wrap this in an error response
          (is (thrown? Exception (.get future 1 TimeUnit/SECONDS))))
        (finally
          (stop-server! (:server client)))))))

(deftest call-tool-impl-test
  ;; Tests successful tool call returns CompletableFuture
  (testing "successful tool call returns CompletableFuture"
    (let [handler (fn [method params]
                    {:content "tool result"
                     :isError false})
          client  (create-test-client-with-handler handler)]
      (try
        (let [future (tools/call-tool-impl client "test-tool" {:input "test"})]
          (is (instance? CompletableFuture future))
          (is (= {:content "tool result", :isError false}
                 (.get future 1 TimeUnit/SECONDS))))
        (finally
          (stop-server! (:server client))))))

  (testing "tool call with error response returns future with error map"
    (let [handler (fn [method params]
                    {:content "Tool execution failed"
                     :isError true})
          client  (create-test-client-with-handler handler)]
      (try
        (let [future (tools/call-tool-impl client "failing-tool" {})
              result (.get future 1 TimeUnit/SECONDS)]
          (is (instance? CompletableFuture future))
          (is (map? result))
          (is (true? (:isError result)))
          (is (= "Tool execution failed" (:content result))))
        (finally
          (stop-server! (:server client))))))

  (testing "tool call with handler exception"
    (let [handler (fn [method params]
                    (throw (ex-info "Handler error" {})))
          client  (create-test-client-with-handler handler)]
      (try
        (let [^CompletableFuture future
              (tools/call-tool-impl client "test-tool" {})]
          (is (instance? CompletableFuture future))
          ;; The server wraps exceptions in an error response
          (is (thrown? Exception (.get future 1 TimeUnit/SECONDS))))
        (finally
          (stop-server! (:server client)))))))

(deftest available-tools?-impl-test
  ;; Tests available tools checking logic
  (testing "returns true when cached tools exist"
    (let [client {:session (atom {})}]
      (#'tools/cache-tools! client [{:name "test-tool"}])
      (is (true? (tools/available-tools?-impl client)))))

  (testing "returns false when no cached tools and no server tools"
    (let [client {:session (atom {})}]
      (with-redefs [tools/list-tools-impl
                    (fn [_client]
                      (CompletableFuture/completedFuture {:tools []}))]
        (is (false? (tools/available-tools?-impl client))))))

  (testing "queries server when no cached tools"
    (let [client {:session (atom {})}]
      (with-redefs [tools/list-tools-impl
                    (fn [_client]
                      (CompletableFuture/completedFuture {:tools [{:name "server-tool"}]}))]
        (is (true? (tools/available-tools?-impl client))))))

  (testing "returns false on server error"
    (let [client {:session (atom {})}]
      (with-redefs [tools/list-tools-impl
                    (fn [_client]
                      (let [future (CompletableFuture.)]
                        (.completeExceptionally future (ex-info "Server error" {}))
                        future))]
        (is (false? (tools/available-tools?-impl client)))))))
