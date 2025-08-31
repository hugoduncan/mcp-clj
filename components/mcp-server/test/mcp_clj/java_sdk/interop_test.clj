(ns mcp-clj.java-sdk.interop-test
  "Cross-implementation tests using Java SDK client with Clojure server"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [mcp-clj.java-sdk.interop :as java-sdk]
   [mcp-clj.mcp-server.core :as mcp-server]
   [mcp-clj.tools.core :as tools]
   [mcp-clj.log :as log])
  (:import
   [java.util.concurrent TimeUnit]))

(def test-tool
  "Simple test tool for cross-implementation testing"
  {:name "echo"
   :description "Echo the input"
   :inputSchema {:type "object"
                 :properties {"message" {:type "string"}}
                 :required ["message"]}
   :implementation (fn [{:keys [message]}]
                     {:content [{:type "text"
                                 :text (str "Echo: " message)}]})})

(def test-tool-2
  "Math test tool"
  {:name "add"
   :description "Add two numbers"
   :inputSchema {:type "object"
                 :properties {"a" {:type "number"}
                              "b" {:type "number"}}
                 :required ["a" "b"]}
   :implementation (fn [{:keys [a b]}]
                     {:content [{:type "text"
                                 :text (str "Result: " (+ a b))}]})})

(defn- find-free-port
  "Find a free port for testing"
  []
  (let [socket (java.net.ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

;;; Test fixtures

(def ^:dynamic *clj-server* nil)
(def ^:dynamic *java-client* nil)

(defn with-clojure-server-http
  "Start Clojure MCP server with HTTP transport"
  [f]
  (let [port (find-free-port)
        server (mcp-server/create-server
                {:transport :sse
                 :port port
                 :tools {"echo" test-tool
                         "add" test-tool-2}})]
    (try
      (binding [*clj-server* server]
        (Thread/sleep 500) ;; Let server start
        (f))
      (finally
        ((:stop server))))))

(defn with-java-client-http
  "Create Java SDK client with HTTP transport"
  [f]
  (when *clj-server*
    (let [port (:port @(:json-rpc-server *clj-server*))
          url (format "http://localhost:%d" port)
          client (java-sdk/create-java-client
                  {:transport :http
                   :url url})]
      (try
        (binding [*java-client* client]
          (f))
        (finally
          (java-sdk/close-client client))))))

;;; HTTP Transport Tests

(use-fixtures :each with-clojure-server-http with-java-client-http)

(deftest http-transport-initialization-test
  (testing "Java SDK client can initialize with Clojure server over HTTP"
    (let [result (java-sdk/initialize-client *java-client*)]
      (is (not (nil? result)))
      (is (= "mcp-clj" (.. result getServerInfo getName)))
      (is (.. result getCapabilities getTools)))))

(deftest http-transport-list-tools-test
  (testing "Java SDK client can list tools from Clojure server"
    (java-sdk/initialize-client *java-client*)
    (let [result (java-sdk/list-tools *java-client*)
          tools (.getTools result)]
      (is (= 2 (.size tools)))
      (let [tool-names (set (map #(.getName %) tools))]
        (is (contains? tool-names "echo"))
        (is (contains? tool-names "add"))))))

(deftest http-transport-call-tool-test
  (testing "Java SDK client can call tools on Clojure server"
    (java-sdk/initialize-client *java-client*)

    (testing "Echo tool"
      (let [result (java-sdk/call-tool *java-client* "echo"
                                       {"message" "Hello from Java SDK"})]
        (is (not (.getIsError result)))
        (let [content (.getContent result)]
          (is (= 1 (.size content)))
          (is (= "Echo: Hello from Java SDK"
                 (.getText (.get content 0)))))))

    (testing "Add tool"
      (let [result (java-sdk/call-tool *java-client* "add"
                                       {"a" 5 "b" 3})]
        (is (not (.getIsError result)))
        (let [content (.getContent result)]
          (is (= 1 (.size content)))
          (is (= "Result: 8"
                 (.getText (.get content 0)))))))))

(deftest http-transport-error-handling-test
  (testing "Java SDK client handles errors correctly"
    (java-sdk/initialize-client *java-client*)

    (testing "Calling non-existent tool"
      (let [result (java-sdk/call-tool *java-client* "nonexistent" {})]
        (is (.getIsError result))
        (let [content (.getContent result)]
          (is (> (.size content) 0))
          (is (re-find #"not found"
                       (.. content (get 0) getText toLowerCase))))))))

;;; Stdio Transport Tests (separate namespace to avoid port conflicts)

(defn start-clojure-stdio-server
  "Start Clojure server as a subprocess for stdio testing"
  []
  (let [command ["clojure" "-M:stdio-server"]
        process (java-sdk/start-process command)]
    (Thread/sleep 2000) ;; Let server initialize
    process))

(deftest ^:integration stdio-transport-test
  (testing "Java SDK client can communicate with Clojure server over stdio"
    (let [process (start-clojure-stdio-server)
          client (java-sdk/create-java-client
                  {:transport :stdio
                   :process process})]
      (try
        ;; Initialize
        (let [init-result (java-sdk/initialize-client client)]
          (is (not (nil? init-result)))
          (is (= "mcp-clj" (.. init-result getServerInfo getName))))

        ;; List tools
        (let [tools-result (java-sdk/list-tools client)
              tools (.getTools tools-result)]
          (is (> (.size tools) 0)))

        ;; Call a tool
        (let [result (java-sdk/call-tool client "clj-eval"
                                         {"code" "(+ 1 2)"})]
          (is (not (.getIsError result))))

        (finally
          (java-sdk/close-client client)
          (java-sdk/stop-process process))))))

;;; Version Negotiation Tests

(deftest version-negotiation-test
  (testing "Java SDK client and Clojure server negotiate protocol version correctly"
    (java-sdk/initialize-client *java-client*)
    ;; The Java SDK should handle version negotiation automatically
    ;; We just verify that initialization succeeds
    (is (not (nil? (java-sdk/list-tools *java-client*))))))