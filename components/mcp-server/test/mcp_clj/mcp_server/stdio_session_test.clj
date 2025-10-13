(ns mcp-clj.mcp-server.stdio-session-test
  "Tests for STDIO transport session management.

  Verifies that STDIO transport properly creates and manages sessions,
  particularly for the initialized notification which arrives early in
  the lifecycle before any other requests."
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.mcp-server.core :as mcp]))

(deftest stdio-session-creation-test
  ;; Test that STDIO transport creates a default session on server creation
  (testing "STDIO server creates default session"
    (let [server (mcp/create-server {:transport {:type :stdio}
                                     :tools {}
                                     :prompts {}
                                     :resources {}})]
      (try
        ;; Verify the stdio session exists
        (let [sessions @(:session-id->session server)]
          (is (contains? sessions "stdio")
              "STDIO server should create default 'stdio' session")
          (is (= "stdio" (:session-id (get sessions "stdio")))
              "Session should have 'stdio' as session-id"))
        (finally
          ((:stop server)))))))

(deftest stdio-initialized-notification-test
  ;; Test that initialized notification can be handled immediately after server creation
  (testing "STDIO server handles initialized notification without session error"
    (let [server (mcp/create-server {:transport {:type :stdio}
                                     :tools {}
                                     :prompts {}
                                     :resources {}})
          ;; Get the handlers from the server
          handlers (#'mcp/create-handlers server)]
      (try
        ;; Simulate the initialized notification that arrives from the client
        ;; In STDIO transport, request is just the method string
        (let [initialized-handler (get handlers "notifications/initialized")
              _ (is (fn? initialized-handler) "initialized handler should exist")
              ;; Call the handler with STDIO-style request (method string)
              result (initialized-handler "notifications/initialized" {})]

          ;; Handler should not throw "missing mcp session" error
          (is (nil? result) "initialized notification should return nil"))

        ;; Verify session was marked as initialized
        (let [sessions @(:session-id->session server)
              stdio-session (get sessions "stdio")]
          (is (true? (:initialized? stdio-session))
              "Session should be marked as initialized after notification"))

        (finally
          ((:stop server)))))))

(deftest stdio-request-handler-session-test
  ;; Test that request handlers can find the STDIO session
  (testing "STDIO request handlers receive session correctly"
    (let [test-tool {:name "test-tool"
                     :description "Test tool for session verification"
                     :inputSchema {:type "object"
                                   :properties {}
                                   :required []}
                     :implementation (fn [_server _params]
                                       ;; This will be called by the handler
                                       [{:type "text" :text "ok"}])}
          server (mcp/create-server {:transport {:type :stdio}
                                     :tools {"test-tool" test-tool}
                                     :prompts {}
                                     :resources {}})
          handlers (#'mcp/create-handlers server)]
      (try
        ;; First initialize the session
        (let [init-handler (get handlers "initialize")]
          (init-handler "initialize"
                        {:protocolVersion "2024-11-05"
                         :capabilities {}
                         :clientInfo {:name "test-client" :version "1.0"}}))

        ;; Mark as initialized
        (let [initialized-handler (get handlers "notifications/initialized")]
          (initialized-handler "notifications/initialized" {}))

        ;; Now call tools/call which requires an initialized session
        (let [call-tool-handler (get handlers "tools/call")
              result (call-tool-handler "tools/call"
                                        {:name "test-tool"
                                         :arguments {}})]

          ;; Should not throw "missing mcp session" error
          (is (some? result) "tools/call should return result")
          (is (vector? (:content result)) "Result should have content vector"))

        (finally
          ((:stop server)))))))

(deftest stdio-session-isolation-test
  ;; Test that STDIO session is isolated and doesn't interfere with other transports
  (testing "STDIO session is separate from other transport sessions"
    (let [server (mcp/create-server {:transport {:type :stdio}
                                     :tools {}
                                     :prompts {}
                                     :resources {}})]
      (try
        (let [sessions @(:session-id->session server)]
          ;; Should only have the stdio session
          (is (= 1 (count sessions))
              "STDIO server should only have one session")
          (is (= #{"stdio"} (set (keys sessions)))
              "Session keys should only contain 'stdio'"))

        (finally
          ((:stop server)))))))
