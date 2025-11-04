(ns mcp-clj.in-memory-transport.core-test
  "Tests for in-memory transport implementation"
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-clj.client-transport.protocol :as transport-protocol]
    [mcp-clj.in-memory-transport.client :as client]
    [mcp-clj.in-memory-transport.shared :as shared])
  (:import
    (java.util.concurrent
      CompletableFuture
      TimeUnit)))

;; Unit Tests for SharedTransport

(deftest test-create-shared-transport
  ;; Test creating shared transport state for connecting client and server
  (testing "create-shared-transport"
    (testing "creates transport with proper initial state"
      (let [shared (shared/create-shared-transport)]
        (is (some? (:client-to-server-queue shared)))
        (is (some? (:server-to-client-queue shared)))
        (is (shared/transport-alive? shared))
        (is (= 0 (shared/get-request-id shared)))
        (is (empty? @(:pending-requests shared)))))))

;; Unit Tests for Client Transport

(deftest test-client-transport-creation
  ;; Test creating client transport with shared state
  (testing "create-transport"
    (let [shared-transport (shared/create-shared-transport)]
      (testing "creates transport successfully with shared state"
        (let [transport (client/create-transport {:shared shared-transport})]
          (is (satisfies? transport-protocol/Transport transport))
          (is (transport-protocol/alive? transport))))

      (testing "throws error when shared transport missing"
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Missing :shared transport"
              (client/create-transport {})))))))

(deftest test-client-transport-lifecycle
  ;; Test client transport lifecycle operations
  (testing "client transport lifecycle"
    (let [shared-transport (shared/create-shared-transport)
          transport (client/create-transport {:shared shared-transport})]

      (testing "transport is initially alive"
        (is (transport-protocol/alive? transport)))

      (testing "transport can be closed"
        (transport-protocol/close! transport)
        (is (not (transport-protocol/alive? transport))))

      (testing "get-json-rpc-client returns client-like object"
        (let [client (transport-protocol/get-json-rpc-client transport)]
          (is (some? client))
          (is (string? (str client))))))))

(def ^:private poll-timeout-ms
  200)

(deftest test-transport-sends-messages
  ;; Test that transport can send messages to queues
  (testing "transport sends messages to queues"
    (let [shared-transport (shared/create-shared-transport)
          transport (client/create-transport
                      {:shared shared-transport})]

      (testing "send-notification puts message in queue"
        (let [future (transport-protocol/send-notification!
                       transport "test" {:data "hello"})]
          ;; Should complete successfully
          (is (nil? (.get future 1 TimeUnit/SECONDS)))
          ;; Should have message in queue
          (let [message (shared/poll-from-client!
                          shared-transport
                          poll-timeout-ms)]
            (is (some? message))
            (is (= "test" (:method message)))
            (is (= {:data "hello"} (:params message))))))

      (testing "send-request puts message in queue"
        (let [future (transport-protocol/send-request!
                       transport
                       "test-req"
                       {:input "world"}
                       1000)]
          ;; Should create a pending future
          (is (instance? CompletableFuture future))
          ;; Should have message in queue
          (let [message (shared/poll-from-client!
                          shared-transport
                          poll-timeout-ms)]
            (is (some? message))
            (is (= "test-req" (:method message)))
            (is (= {:input "world"} (:params message)))
            (is (some? (:id message))))))

      ;; Cleanup
      (transport-protocol/close! transport))))

(deftest test-server-to-client-communication
  ;; Test that server can send responses back to client
  (testing "server can send responses to client"
    (testing "server can put response in server-to-client queue"
      (let [shared-transport (shared/create-shared-transport)
            transport (client/create-transport {:shared shared-transport})]
        (try
          ;; Simulate server putting a response message
          (let [response {:jsonrpc "2.0"
                          :id 123
                          :result {:content "server response"}}]
            (shared/offer-to-client! shared-transport response)

            ;; Client should be able to poll the response
            (let [received-message (shared/poll-from-server!
                                     shared-transport
                                     poll-timeout-ms)]
              (is (some? received-message))
              (is (= 123 (:id received-message)))
              (is (= {:content "server response"} (:result received-message)))))
          (finally
            (transport-protocol/close! transport)))))

    (testing "client can receive notifications from server"
      (let [shared-transport (shared/create-shared-transport)
            received-notifications (atom [])
            transport (client/create-transport
                        {:shared shared-transport
                         :notification-handler
                         (fn [notification]
                           (swap! received-notifications conj notification))})]
        (try
          ;; Simulate server sending a notification
          (let [notification {:jsonrpc "2.0"
                              :method "server/notification"
                              :params {:data "notification data"}}]
            (shared/offer-to-client! shared-transport notification)

            ;; Wait for notification handler to be called (with timeout)
            (loop [attempts 0]
              (when (and (< attempts 50) (empty? @received-notifications))
                (Thread/sleep 10)
                (recur (inc attempts))))

            ;; Assert on captured notification
            (is (= 1 (count @received-notifications)))
            (let [received-notification (first @received-notifications)]
              (is (some? received-notification))
              (is (= "server/notification" (:method received-notification)))
              (is (= {:data "notification data"}
                     (:params received-notification)))))
          (finally
            (transport-protocol/close! transport)))))))

(deftest test-bidirectional-communication
  ;; Test complete round-trip communication
  (testing "bidirectional communication works"
    (let [shared-transport (shared/create-shared-transport)
          transport (client/create-transport {:shared shared-transport})]

      (testing "direct queue operations work"
        ;; Test direct queue operations first
        (let [test-message {:test "direct-queue-test"}]
          (shared/offer-to-client! shared-transport test-message)
          (let [polled-message (shared/poll-from-server!
                                 shared-transport
                                 poll-timeout-ms)]
            (is (some? polled-message))
            (is (= test-message polled-message)))))

      (testing "full request-response cycle"
        ;; 1. Client sends request
        (transport-protocol/send-request!
          transport
          "test-method"
          {:input "test-data"}
          5000)

        ;; 2. Verify request is in client-to-server queue
        (let [request-message (shared/poll-from-client!
                                shared-transport
                                poll-timeout-ms)]
          (is (some? request-message))
          (is (= "test-method" (:method request-message)))
          (is (= {:input "test-data"} (:params request-message)))
          (is (some? (:id request-message)))

          ;; 3. Simulate server processing and sending response
          (let [response {:jsonrpc "2.0"
                          :id (:id request-message)
                          :result {:processed "test-data"}}]
            (shared/offer-to-client! shared-transport response)

            ;; 4. Verify response is available in server-to-client queue
            (let [response-message (shared/poll-from-server!
                                     shared-transport
                                     poll-timeout-ms)]
              (is (some? response-message))
              (is (= (:id request-message) (:id response-message)))
              (is (= {:processed "test-data"} (:result response-message)))))))

      ;; Cleanup
      (transport-protocol/close! transport))))

(deftest test-session-handling
  ;; Test that in-memory transport properly handles session IDs in requests
  (testing "in-memory transport includes session ID in request objects"
    (let [shared-transport (shared/create-shared-transport)
          received-requests (atom [])
          test-handlers {"test-method"
                         (fn [request params]
                           (swap!
                             received-requests
                             conj
                             {:request request :params params})
                           {:result "success"})}]

      ;; Create server with handlers that capture requests
      (require 'mcp-clj.in-memory-transport.server)
      (let [create-server (ns-resolve
                            'mcp-clj.in-memory-transport.server
                            'create-in-memory-server)
            _server (create-server
                      {:shared shared-transport}
                      test-handlers)
            client-transport (client/create-transport
                               {:shared shared-transport})]

        (try
          ;; Send a test request
          (let [request-future (transport-protocol/send-request!
                                 client-transport
                                 "test-method"
                                 {:test "data"}
                                 5000)]

            ;; Wait for completion
            (.get request-future 5 TimeUnit/SECONDS)

            ;; Verify the request object contains session ID
            (is (= 1 (count @received-requests)))
            (let [{:keys [request params]} (first @received-requests)]
              (is (map? request) "Request should be a map")
              (is (contains? request :query-params)
                  "Request should contain query-params")
              (is (= "in-memory-session"
                     (get-in request [:query-params "session_id"]))
                  "Request should contain the in-memory session ID")
              (is (= "test-method" (:method request))
                  "Request should contain method")
              (is (= {:test "data"} params)
                  "Params should be passed correctly")))

          (finally
            (transport-protocol/close! client-transport)))))))
