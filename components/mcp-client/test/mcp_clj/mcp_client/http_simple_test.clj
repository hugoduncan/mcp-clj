(ns mcp-clj.mcp-client.http-simple-test
  "Simple HTTP transport test for debugging"
  (:require
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-server.core :as server]
   [mcp-clj.log :as log]))

(deftest simple-http-test
  ;; Simple test to verify HTTP transport basics
  (testing "Basic HTTP transport test"
    (let [server (server/create-server
                  {:transport :http
                   :port      0
                   :tools
                   {"echo"
                    {:name           "echo"
                     :description    "Simple echo"
                     :inputSchema    {:type       "object"
                                      :properties {:msg {:type "string"}}
                                      :required   ["msg"]}
                     :implementation (fn [{:keys [msg]}]
                                       {:content [{:type "text" :text msg}]})}}})
          ;; Wait for server to be fully initialized with proper timeout
          rpc-server (deref (:json-rpc-server server) 5000 nil)
          port   (when rpc-server
                   (:port rpc-server))]

      (log/info :test/server-started {:port port})
      (is (some? port) "Server should have a port")
      (is (pos? port) "Port should be positive")

      (try
        ;; Test that we can make a simple HTTP request to the server first
        (let [response (try
                         (shell/sh "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}"
                                   (str "http://localhost:" port "/")
                                   "-X" "POST" "-H" "Content-Type: application/json"
                                   "-d" "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}")
                         (catch Exception e
                           (log/error :test/curl-error {:error e})
                           {:exit 1 :out "000"}))]
          (log/info :test/curl-response {:response response})
          (is (= 0 (:exit response)) "Curl should succeed")

          ;; Only proceed with client test if server is responding
          (when (= 0 (:exit response))
            (let [client (client/create-client {:url              (str "http://localhost:" port)
                                                :client-info      {:name "test" :version "1.0.0"}
                                                :capabilities     {:tools {}}
                                                :protocol-version "2024-11-05"
                                                :num-threads      2})]

              (try
                ;; Wait for ready
                (client/wait-for-ready client 10000)

                (testing "client is ready"
                  (is (client/client-ready? client)))

                (testing "can list tools"
                  (let [tools (client/list-tools client)]
                    (is (= 1 (count (:tools tools))))
                    (is (= "echo" (-> tools :tools first :name)))))

                (testing "can call tool"
                  (let [result (client/call-tool client "echo" {:msg "Hello"})]
                    (is (= "Hello" (-> result first :text)))))

                (finally
                  (client/close! client))))))

        (finally
          ((:stop server))
          (log/info :test/server-stopped))))))
