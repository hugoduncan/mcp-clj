(ns mcp-clj.in-memory-transport.core-test
  "Tests for in-memory transport implementation"
  (:require
   [clojure.test :refer [deftest testing is]]
   [mcp-clj.client-transport.protocol :as transport-protocol]
   [mcp-clj.in-memory-transport.client :as client]
   [mcp-clj.in-memory-transport.shared :as shared])
  (:import
   [java.util.concurrent CompletableFuture TimeUnit]))

;;; Unit Tests for SharedTransport

(deftest test-create-shared-transport
  ;; Test creating shared transport state for connecting client and server
  (testing "create-shared-transport"
    (testing "creates transport with proper initial state"
      (let [shared (shared/create-shared-transport)]
        (is (some? (:client-to-server-queue shared)))
        (is (some? (:server-to-client-queue shared)))
        (is (.get (:alive? shared)))
        (is (= 0 (.get (:request-id-counter shared))))
        (is (empty? @(:pending-requests shared)))))))

;;; Unit Tests for Client Transport

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
          (is (string? (.toString client))))))))

(deftest test-transport-sends-messages
  ;; Test that transport can send messages to queues
  (testing "transport sends messages to queues"
    (let [shared-transport (shared/create-shared-transport)
          transport (client/create-transport {:shared shared-transport})]

      (testing "send-notification puts message in queue"
        (let [future (transport-protocol/send-notification! transport "test" {:data "hello"})]
          ;; Should complete successfully
          (is (nil? (.get future 1 TimeUnit/SECONDS)))
          ;; Should have message in queue
          (let [message (.poll (:client-to-server-queue shared-transport) 100 TimeUnit/MILLISECONDS)]
            (is (some? message))
            (is (= "test" (:method message)))
            (is (= {:data "hello"} (:params message))))))

      (testing "send-request puts message in queue"
        (let [future (transport-protocol/send-request! transport "test-req" {:input "world"} 1000)]
          ;; Should create a pending future
          (is (instance? CompletableFuture future))
          ;; Should have message in queue
          (let [message (.poll (:client-to-server-queue shared-transport) 100 TimeUnit/MILLISECONDS)]
            (is (some? message))
            (is (= "test-req" (:method message)))
            (is (= {:input "world"} (:params message)))
            (is (some? (:id message))))))

      ;; Cleanup
      (transport-protocol/close! transport))))