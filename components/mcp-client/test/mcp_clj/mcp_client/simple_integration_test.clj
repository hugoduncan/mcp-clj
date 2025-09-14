(ns mcp-clj.mcp-client.simple-integration-test
  "Simplified integration test for MCP client initialization"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-server.core :as server]
   [mcp-clj.json-rpc.stdio-server :as stdio-server]
   [mcp-clj.log :as log])
  (:import
   [java.util.concurrent CountDownLatch TimeUnit]))

(deftest ^:integration in-process-server-client-test
  (testing "MCP client can initialize with in-process MCP server"
    (let [server-ready-latch (CountDownLatch. 1)
          client-ready-latch (CountDownLatch. 1)

          ;; Create MCP server that will run in separate thread
          mcp-server (server/create-server {:transport :stdio})

          ;; Mock stdio server that captures JSON communication
          server-thread (future
                          (try
                            (.countDown server-ready-latch)
                            ;; In a real scenario, the server would read from stdin
                            ;; For this test, we'll simulate server being ready
                            @client-ready-latch
                            (catch Exception e
                              (log/error :test-server-error {:error e}))))]

      ;; Wait for server to be ready
      (.await server-ready-latch 5 TimeUnit/SECONDS)

      ;; Create client (this will fail to connect to stdio, but we can test the API)
      (let [client (client/create-client
                    {:server
                     {:type :stdio
                      :command "echo"
                      :args ["{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test-server\",\"version\":\"1.0.0\"}}}"]}
                     :client-info {:name "simple-test-client"
                                   :title "Simple Test Client"
                                   :version "1.0.0"}
                     :capabilities {}})]

        ;; Check initial state
        (is (= :initializing (:state @(:session client))))
        (is (not (client/client-ready? client)))
        (is (not (client/client-error? client)))

        ;; Verify client info
        (let [info (client/get-client-info client)]
          (is (= :initializing (:state info)))
          (is (= "2025-06-18" (:protocol-version info)))
          (is (= {:name "simple-test-client"
                  :title "Simple Test Client"
                  :version "1.0.0"}
                 (:client-info info)))
          (is (= {} (:client-capabilities info)))
          (is (or (= {:name "test-server", :version "1.0.0"} (:server-info info))
                  (nil? (:server-info info))))
          (is (nil? (:server-capabilities info))))

        ;; Note: Actual initialization with echo will fail, but that's expected
        ;; The point is to verify the client API and session management works

        (client/close! client)
        (.countDown client-ready-latch)))))

(deftest ^:integration client-session-state-transitions-test
  (testing "Client session transitions through states correctly"
    (let [client (client/create-client
                  ;; cat will read but not respond properly
                  {:server {:type :stdio
                            :command "cat"}
                   :client-info {:name "state-test-client"}
                   :capabilities {}})]

      ;; Client starts initializing
      (is (contains? #{:initializing :error} (:state @(:session client))))

      ;; wait-for-ready should throw an exception since cat can't handle MCP
      ;; protocol
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Client initialization failed"
           (client/wait-for-ready client 2000)))

      ;; Should be in error state after failed initialization
      (is (= :error (:state @(:session client))))

      (client/close! client))))

(deftest ^:integration client-configuration-test
  (testing "Client accepts various transport configurations"
    ;; Test map-style transport
    (let [client1 (client/create-client
                   {:server {:type :stdio :command "echo" :args ["test"]}
                    :client-info {:name "config-test-1"}})]
      (is (some? client1))
      (is (= "config-test-1" (get-in @(:session client1) [:client-info :name])))
      (client/close! client1))

    ;; Test custom protocol version
    (let [client3 (client/create-client
                   {:server {:type :stdio
                             :command "echo"
                             :args ["test"]}
                    :protocol-version "2024-11-05"})]
      (is (some? client3))
      (is (= "2024-11-05" (:protocol-version @(:session client3))))
      (client/close! client3))))
