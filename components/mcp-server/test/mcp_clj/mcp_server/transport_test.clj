(ns mcp-clj.mcp-server.transport-test
  "Tests for MCP server transport selection and functionality"
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.json-rpc.protocols :as json-rpc-protocols]
   [mcp-clj.mcp-server.core :as mcp]
   [mcp-clj.tools.core :as tools])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream PipedInputStream PipedOutputStream StringReader]
   [java.util.concurrent TimeUnit]))

(def test-tool
  {:name "test-add"
   :description "Add two numbers"
   :inputSchema {:type "object"
                 :properties {:a {:type "number"}
                              :b {:type "number"}}
                 :required [:a :b]}
   :implementation (fn [_tool params]
                     {:content [{:type "text"
                                 :text (str "Result: " (+ (:a params) (:b params)))}]})})

;;; Transport Selection Tests

(deftest transport-selection-test
  (testing "Transport determination logic"
    (let [determine-transport (fn [{:keys [transport port]}]
                                (cond
                                  (= transport :stdio) :stdio
                                  (= transport :sse) :sse
                                  (some? port) :sse
                                  :else :stdio))]

      (testing "explicit stdio transport"
        (is (= :stdio (determine-transport {:transport :stdio}))))

      (testing "explicit sse transport"
        (is (= :sse (determine-transport {:transport :sse}))))

      (testing "port implies sse transport"
        (is (= :sse (determine-transport {:port 3001}))))

      (testing "default is stdio"
        (is (= :stdio (determine-transport {})))))))

;;; stdio Transport Tests

(deftest stdio-server-creation-test
  (testing "stdio server creation"
    (let [server (mcp/create-server {:transport :stdio
                                     :tools {"test-add" test-tool}})]
      (is (some? server) "stdio server should be created")
      (is (fn? (:stop server)) "server should have stop function")

      ;; Test that we can call protocol methods
      (testing "protocol operations work"
        (is (nil? (json-rpc-protocols/notify-all!
                   @(:json-rpc-server server)
                   "test" {})) "notify-all should work (no-op for stdio)"))

      ;; Clean up
      ((:stop server)))))

(deftest stdio-server-default-test
  (testing "stdio is default when no port specified"
    (let [server (mcp/create-server {:tools {"test-add" test-tool}})]
      (is (some? server) "default server should be created")

      ;; The json-rpc-server should be a stdio server (no :session-id->session)
      (let [rpc-server @(:json-rpc-server server)]
        (is (not (contains? rpc-server :session-id->session))
            "default server should be stdio (no session management)"))

      ;; Clean up
      ((:stop server)))))

;;; SSE Transport Tests

(deftest sse-server-creation-test
  (testing "SSE server creation"
    (let [server (mcp/create-server {:transport :sse
                                     :port 0 ; random port
                                     :tools {"test-add" test-tool}})]
      (is (some? server) "SSE server should be created")
      (is (fn? (:stop server)) "server should have stop function")

      ;; Test that we can call protocol methods
      (testing "protocol operations work"
        (is (nil? (json-rpc-protocols/notify-all!
                   @(:json-rpc-server server)
                   "test" {})) "notify-all should work"))

      ;; The json-rpc-server should be an SSE server (has :session-id->session)
      (let [rpc-server @(:json-rpc-server server)]
        (is (contains? rpc-server :session-id->session)
            "SSE server should have session management")
        (is (number? (:port rpc-server)) "SSE server should have a port"))

      ;; Clean up
      ((:stop server)))))

(deftest sse-server-port-implies-test
  (testing "port parameter implies SSE transport"
    (let [server (mcp/create-server {:port 0
                                     :tools {"test-add" test-tool}})]
      (is (some? server) "server with port should be created")

      ;; Should be SSE server
      (let [rpc-server @(:json-rpc-server server)]
        (is (contains? rpc-server :session-id->session)
            "server with port should be SSE server"))

      ;; Clean up
      ((:stop server)))))

;;; Protocol Polymorphism Tests

(deftest protocol-polymorphism-test
  (testing "Both server types implement JsonRpcServer protocol"
    (let [stdio-server (mcp/create-server {:transport :stdio
                                           :tools {"test-add" test-tool}})
          sse-server (mcp/create-server {:transport :sse :port 0
                                         :tools {"test-add" test-tool}})]

      (try
        (testing "Both servers support set-handlers!"
          (is (some? (json-rpc-protocols/set-handlers!
                      @(:json-rpc-server stdio-server)
                      {"new" (fn [m p] {:test "result"})}))
              "stdio server should support set-handlers!")

          (is (some? (json-rpc-protocols/set-handlers!
                      @(:json-rpc-server sse-server)
                      {"new" (fn [r p] {:test "result"})}))
              "sse server should support set-handlers!"))

        (testing "Both servers support notify-all!"
          (is (nil? (json-rpc-protocols/notify-all!
                     @(:json-rpc-server stdio-server)
                     "notification" {:data "test"}))
              "stdio server should support notify-all!")

          (is (nil? (json-rpc-protocols/notify-all!
                     @(:json-rpc-server sse-server)
                     "notification" {:data "test"}))
              "sse server should support notify-all!"))

        (testing "Both servers support stop!"
          (is (nil? (json-rpc-protocols/stop! @(:json-rpc-server stdio-server)))
              "stdio server should support stop!")

          (is (nil? (json-rpc-protocols/stop! @(:json-rpc-server sse-server)))
              "sse server should support stop!"))

        (finally
          ;; Clean up servers
          (try ((:stop stdio-server)) (catch Exception _))
          (try ((:stop sse-server)) (catch Exception _)))))))

;;; Integration Tests

(deftest server-functionality-test
  (testing "Both transports support basic MCP operations"
    (doseq [transport [:stdio :sse]]
      (testing (str "Transport: " transport)
        (let [server-opts (if (= transport :sse)
                            {:transport :sse :port 0}
                            {:transport :stdio})
              server (mcp/create-server
                      (merge server-opts
                             {:tools {"test-add" test-tool}}))]

          (try
            (testing "Server creation"
              (is (some? server) (str transport " server should be created"))
              (is (fn? (:stop server)) "server should have stop function"))

            (testing "Tool registry access"
              (is (= {"test-add" test-tool} @(:tool-registry server))
                  "server should have correct tools"))

            (testing "Server management functions"
              ;; Test add-tool!
              (let [new-tool (assoc test-tool :name "test-multiply")]
                (mcp/add-tool! server new-tool)
                (is (contains? @(:tool-registry server) "test-multiply")
                    "should be able to add tools"))

              ;; Test remove-tool!
              (mcp/remove-tool! server "test-multiply")
              (is (not (contains? @(:tool-registry server) "test-multiply"))
                  "should be able to remove tools"))

            (finally
              ;; Clean up
              ((:stop server)))))))))

;;; Error Handling Tests

(deftest transport-error-handling-test
  (testing "Invalid transport type"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unsupported transport type"
         (mcp/create-server {:transport :invalid}))
        "should reject invalid transport types"))

  (testing "Invalid tool definitions"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid tool in constructor"
         (mcp/create-server {:transport :stdio
                             :tools {"invalid" {:bad "tool"}}}))
        "should reject invalid tools")))