(ns mcp-clj.mcp-server-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [mcp-clj.mcp-server.core :as mcp]))

(def valid-client-info
  {:clientInfo      {:name "test-client" :version "1.0"}
   :protocolVersion "0.1"
   :capabilities    {:tools {}}})

(deftest lifecycle-test
  (let [state  (atom {:initialized? false})
        server {:state state}]

    (testing "initialize request"
      (let [response (mcp/handle-initialize server valid-client-info)]
        (is (= "mcp-clj" (get-in response [:serverInfo :name]))
            "Returns server info")
        (is (= "0.1" (:protocolVersion response))
            "Returns protocol version")
        (is (map? (:capabilities response))
            "Returns capabilities")
        (is (false? (get-in response [:capabilities :tools :listChanged]))
            "Tools capability configured correctly")
        (is (= (:clientInfo valid-client-info)
               (:client-info @state))
            "Client info stored in state")))

    (testing "initialize with invalid version"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unsupported protocol version"
           (mcp/handle-initialize
            server
            (assoc valid-client-info :protocolVersion "0.2")))
          "Throws on version mismatch"))

    (testing "initialized notification"
      (is (nil? (mcp/handle-initialized server nil))
          "Returns nil for notification")
      (is (:initialized? @state)
          "Server marked as initialized"))

    (testing "ping after initialization"
      (is (= {} (mcp/ping server nil))
          "Ping returns empty map when initialized"))

    (testing "ping before initialization"
      (swap! state assoc :initialized? false)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Server not initialized"
           (mcp/ping server nil))
          "Ping throws when not initialized"))))

(deftest server-creation-test
  (testing "create server"
    (let [server (mcp/create-server {:port 8080})]
      (is (instance? mcp_clj.mcp_server.core.MCPServer server)
          "Returns MCPServer instance")
      (is (fn? (:stop server))
          "Has stop function")
      (is (not (:initialized? @(:state server)))
          "Initial state is uninitialized")

      ;; Stop server
      ((:stop server)))))
