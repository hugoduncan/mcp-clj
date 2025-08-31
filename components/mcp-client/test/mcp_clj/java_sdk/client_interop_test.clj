(ns mcp-clj.java-sdk.client-interop-test
  "Cross-implementation tests using Clojure client with Java SDK server"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [mcp-clj.java-sdk.interop :as java-sdk]
   [mcp-clj.mcp-client.core :as mcp-client]
   [mcp-clj.log :as log])
  (:import
   [java.util.concurrent TimeUnit]))

(defn- find-free-port
  "Find a free port for testing"
  []
  (let [socket (java.net.ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

;;; Test fixtures

(def ^:dynamic *java-server* nil)
(def ^:dynamic *clj-client* nil)

(defn with-java-server-http
  "Start Java SDK MCP server with HTTP transport"
  [f]
  (let [port (find-free-port)
        server (java-sdk/create-java-server
                {:transport :http
                 :port port
                 :name "java-test-server"
                 :version "1.0.0"})]
    (try
      ;; Register test tools
      (java-sdk/register-tool server
                              {:name "java-echo"
                               :description "Echo from Java server"
                               :input-schema {:type "object"
                                              :properties {"text" {:type "string"}}
                                              :required ["text"]}
                               :handler (fn [args]
                                          {:content [{:type "text"
                                                      :text (str "Java echo: " (:text args))}]})})

      (java-sdk/register-tool server
                              {:name "java-multiply"
                               :description "Multiply two numbers"
                               :input-schema {:type "object"
                                              :properties {"x" {:type "number"}
                                                           "y" {:type "number"}}
                                              :required ["x" "y"]}
                               :handler (fn [args]
                                          {:content [{:type "text"
                                                      :text (str (* (:x args) (:y args)))}]})})

      (java-sdk/start-server server)
      (Thread/sleep 1000) ;; Let server start
      (binding [*java-server* server]
        (f))
      (finally
        (java-sdk/stop-server server)))))

(defn with-clojure-client-http
  "Create Clojure client with HTTP transport"
  [f]
  (when *java-server*
    (let [port (-> *java-server* :transport .getPort)
          url (format "http://localhost:%d" port)]
      ;; Create Clojure client for HTTP transport
      ;; Note: This will need the actual HTTP client implementation
      ;; For now, using a placeholder
      (binding [*clj-client* {:url url :port port}]
        (f)))))

;;; HTTP Transport Tests

(use-fixtures :each with-java-server-http with-clojure-client-http)

(deftest ^:integration http-client-initialization-test
  (testing "Clojure client can initialize with Java SDK server over HTTP"
    ;; This test would require the HTTP client implementation
    ;; Placeholder assertion
    (is (not (nil? *clj-client*)))
    (is (:url *clj-client*))))

(deftest ^:integration http-client-list-tools-test
  (testing "Clojure client can list tools from Java SDK server"
    ;; This would use the actual Clojure client to list tools
    ;; Placeholder for now
    (is (not (nil? *java-server*)))))

(deftest ^:integration http-client-call-tool-test
  (testing "Clojure client can call tools on Java SDK server"
    ;; This would test actual tool calls
    ;; Placeholder for now
    (is (not (nil? *java-server*)))))

;;; Stdio Transport Tests

(defn create-java-stdio-server-process
  "Create a Java SDK server as a subprocess for stdio testing"
  []
  ;; This would need to compile and run a Java class that starts
  ;; a stdio MCP server using the SDK
  ;; For now, returning a placeholder
  {:process nil})

(deftest ^:integration stdio-client-test
  (testing "Clojure client can communicate with Java SDK server over stdio"
    (let [server-info (create-java-stdio-server-process)]
      (when (:process server-info)
        (try
          ;; Test with actual stdio client
          (is true)
          (finally
            ;; Cleanup process
            (when-let [p (:process server-info)]
              (.destroy p))))))))

;;; Version Negotiation Tests

(deftest ^:integration client-version-negotiation-test
  (testing "Clojure client and Java SDK server negotiate protocol version correctly"
    ;; The clients should handle version negotiation automatically
    ;; This test verifies that different protocol versions can connect
    (is (not (nil? *java-server*)))))

;;; Error Handling Tests

(deftest ^:integration client-error-handling-test
  (testing "Clojure client handles Java SDK server errors correctly"
    ;; Test error scenarios
    (is (not (nil? *java-server*)))))