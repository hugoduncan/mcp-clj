(ns mcp-clj.mcp-client.unit-test
  "Unit tests for MCP client using in-memory transport for speed"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.in-memory-transport.shared :as shared]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-server.core :as server])
  (:import
   [java.util.concurrent TimeUnit]))

;;; Test Tools

(def test-tools
  {"echo" {:name "echo"
           :description "Echo back the input"
           :inputSchema {:type "object"
                         :properties {:message {:type "string"}}}
           :implementation (fn [args] {:result (str "Echo: " (:message args))})}

   "add" {:name "add"
          :description "Add two numbers"
          :inputSchema {:type "object"
                        :properties {:a {:type "number"}
                                     :b {:type "number"}}}
          :implementation (fn [{:keys [a b]}] {:result (+ a b)})}

   "error-tool" {:name "error-tool"
                 :description "Tool that throws an error"
                 :inputSchema {:type "object"}
                 :implementation (fn [_] (throw (ex-info "Test error" {})))}})

;;; Helper Functions

(defn create-test-client-server
  "Create a client and server connected via in-memory transport"
  [& {:keys [tools client-info capabilities]
      :or {tools test-tools
           client-info {:name "test-client" :version "1.0.0"}
           capabilities {}}}]
  (let [shared-transport (shared/create-shared-transport)
        test-server (server/create-server
                     {:transport {:type :in-memory :shared shared-transport}
                      :tools tools})
        test-client (client/create-client
                     {:transport {:type :in-memory :shared shared-transport}
                      :client-info client-info
                      :capabilities capabilities})]
    {:client test-client
     :server test-server
     :shared-transport shared-transport}))

(defn cleanup-test-env
  "Clean up test client and server"
  [{:keys [client server]}]
  (when client (client/close! client))
  (when server ((:stop server))))

;;; Unit Tests

(deftest client-server-initialization-test
  ;; Test MCP client initialization with in-memory transport
  (testing "MCP client initializes with in-memory server"
    (let [test-env (create-test-client-server)]
      (try
        (let [{:keys [client]} test-env]
          ;; Client should start initializing automatically
          (let [initial-state (:state @(:session client))]
            (is (contains? #{:initializing :ready} initial-state)))

          ;; Wait for initialization to complete
          (client/wait-for-ready client 5000) ; 5 second timeout

          ;; Client should now be ready
          (is (client/client-ready? client))
          (is (not (client/client-error? client)))

          ;; Check session details
          (let [info (client/get-client-info client)]
            (is (= :ready (:state info)))
            (is (= "2025-06-18" (:protocol-version info)))
            (is (= {:name "test-client" :version "1.0.0"}
                   (:client-info info)))
            (is (= {} (:client-capabilities info)))
            (is (some? (:server-info info))) ; Server should provide info
            (is (map? (:server-capabilities info))) ; Server should declare capabilities
            (is (:transport-alive? info))))

        (finally
          (cleanup-test-env test-env))))))

(deftest client-tool-operations-test
  ;; Test tool listing and calling operations
  (testing "MCP client can list and call tools"
    (let [test-env (create-test-client-server)]
      (try
        (let [{:keys [client]} test-env]
          ;; Wait for initialization
          (client/wait-for-ready client 5000)

          ;; Test listing tools
          (let [tools-response @(client/list-tools client)]
            (is (some? tools-response))
            (is (= 3 (count (:tools tools-response))))
            (let [tool-names (set (map :name (:tools tools-response)))]
              (is (contains? tool-names "echo"))
              (is (contains? tool-names "add"))
              (is (contains? tool-names "error-tool"))))

          ;; Test calling echo tool
          (let [echo-result @(client/call-tool client "echo" {:message "hello world"})]
            (is (some? echo-result))
            (is (= "Echo: hello world" (-> echo-result :content first :text))))

          ;; Test calling add tool
          (let [add-result @(client/call-tool client "add" {:a 5 :b 3})]
            (is (some? add-result))
            (is (= 8 (-> add-result :content first :text))))

          ;; Test calling non-existent tool
          (is (thrown? Exception
                       @(client/call-tool client "nonexistent" {})))

          ;; Test calling tool that throws error
          (is (thrown? Exception
                       @(client/call-tool client "error-tool" {}))))

        (finally
          (cleanup-test-env test-env))))))

(deftest client-multiple-operations-test
  ;; Test multiple concurrent operations
  (testing "MCP client handles multiple operations concurrently"
    (let [test-env (create-test-client-server)]
      (try
        (let [{:keys [client]} test-env]
          ;; Wait for initialization
          (client/wait-for-ready client 5000)

          ;; Launch multiple operations concurrently
          (let [echo-futures (mapv #(client/call-tool client "echo" {:message (str "message-" %)})
                                   (range 5))
                add-futures (mapv #(client/call-tool client "add" {:a % :b (inc %)})
                                  (range 3))]

            ;; Wait for all to complete
            (let [echo-results (mapv #(deref % 2000 :timeout) echo-futures)
                  add-results (mapv #(deref % 2000 :timeout) add-futures)]

              ;; All should complete successfully
              (is (every? #(not= :timeout %) echo-results))
              (is (every? #(not= :timeout %) add-results))

              ;; Check echo results
              (doseq [[i result] (map-indexed vector echo-results)]
                (is (= (str "Echo: message-" i) (-> result :content first :text))))

              ;; Check add results
              (doseq [[i result] (map-indexed vector add-results)]
                (is (= (+ i (inc i)) (-> result :content first :text)))))))

        (finally
          (cleanup-test-env test-env))))))

(deftest client-error-handling-test
  ;; Test client error handling
  (testing "MCP client handles errors gracefully"
    (let [test-env (create-test-client-server)]
      (try
        (let [{:keys [client server]} test-env]
          ;; Wait for initialization
          (client/wait-for-ready client 5000)

          ;; Client should be ready initially
          (is (client/client-ready? client))

          ;; Stop the server to simulate connection loss
          ((:stop server))

          ;; Give some time for the connection to be detected as lost
          (Thread/sleep 100)

          ;; Transport should no longer be alive
          (let [info (client/get-client-info client)]
            (is (not (:transport-alive? info))))

          ;; New operations should fail
          (is (thrown? Exception
                       @(client/call-tool client "echo" {:message "test"}))))

        (finally
          (cleanup-test-env test-env))))))

(deftest multiple-clients-test
  ;; Test multiple clients connecting to same server
  (testing "Multiple clients can connect to same server via in-memory transport"
    (let [shared-transport (shared/create-shared-transport)
          test-server (server/create-server
                       {:transport {:type :in-memory :shared shared-transport}
                        :tools test-tools})
          client1 (client/create-client
                   {:transport {:type :in-memory :shared shared-transport}
                    :client-info {:name "client-1" :version "1.0.0"}
                    :capabilities {}})
          client2 (client/create-client
                   {:transport {:type :in-memory :shared shared-transport}
                    :client-info {:name "client-2" :version "1.0.0"}
                    :capabilities {}})]

      (try
        ;; Both clients should initialize successfully
        (client/wait-for-ready client1 5000)
        (client/wait-for-ready client2 5000)

        ;; Both should be ready
        (is (client/client-ready? client1))
        (is (client/client-ready? client2))

        ;; Verify they have different client info
        (let [info1 (client/get-client-info client1)
              info2 (client/get-client-info client2)]
          (is (= "client-1" (get-in info1 [:client-info :name])))
          (is (= "client-2" (get-in info2 [:client-info :name]))))

        ;; Both should be able to call tools
        (let [result1 @(client/call-tool client1 "echo" {:message "from client 1"})
              result2 @(client/call-tool client2 "echo" {:message "from client 2"})]
          (is (= "Echo: from client 1" (-> result1 :content first :text)))
          (is (= "Echo: from client 2" (-> result2 :content first :text))))

        (finally
          (client/close! client1)
          (client/close! client2)
          ((:stop test-server)))))))

(deftest client-lifecycle-test
  ;; Test client lifecycle operations
  (testing "MCP client lifecycle operations work correctly"
    (let [test-env (create-test-client-server)]
      (try
        (let [{:keys [client]} test-env]
          ;; Wait for initialization
          (client/wait-for-ready client 5000)

          ;; Client should be ready and alive
          (is (client/client-ready? client))
          (let [info (client/get-client-info client)]
            (is (:transport-alive? info)))

          ;; Close the client
          (client/close! client)

          ;; Client should no longer be ready or alive
          (is (not (client/client-ready? client)))
          (let [info (client/get-client-info client)]
            (is (not (:transport-alive? info)))))

        (finally
          (cleanup-test-env test-env))))))