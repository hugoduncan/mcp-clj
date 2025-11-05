(ns mcp-clj.mcp-server.nil-params-test
  "Tests for handling nil/missing params in JSON-RPC requests.

  This test demonstrates the NullPointerException bug when params is nil
  (before fix in PR #37) and validates the fix works correctly."
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.client-transport.factory :as client-transport-factory]
    [mcp-clj.in-memory-transport.shared :as shared]
    [mcp-clj.mcp-client.core :as client]
    [mcp-clj.mcp-server.core :as server]
    [mcp-clj.server-transport.factory :as server-transport-factory])
  (:import
    (java.util.concurrent
      TimeUnit)))

;; Transport registration

(defn ensure-in-memory-transport-registered!
  []
  (client-transport-factory/register-transport!
    :in-memory
    (fn [options]
      (require 'mcp-clj.in-memory-transport.client)
      (let [create-fn (ns-resolve
                        'mcp-clj.in-memory-transport.client
                        'create-transport)]
        (create-fn options))))
  (server-transport-factory/register-transport!
    :in-memory
    (fn [options handlers]
      (require 'mcp-clj.in-memory-transport.server)
      (let [create-server (ns-resolve
                            'mcp-clj.in-memory-transport.server
                            'create-in-memory-server)]
        (create-server options handlers)))))

(ensure-in-memory-transport-registered!)

;; Test Helpers

(defn create-test-env
  []
  (let [shared-transport (shared/create-shared-transport)
        mcp-server (server/create-server
                     {:transport {:type :in-memory :shared shared-transport}})
        mcp-client (client/create-client
                     {:transport {:type :in-memory :shared shared-transport}
                      :client-info {:name "test-client" :version "1.0.0"}
                      :capabilities {}})]
    {:server mcp-server
     :client mcp-client
     :shared-transport shared-transport}))

(defn cleanup-test-env
  [{:keys [server client]}]
  (when client (client/close! client))
  (when server ((:stop server))))

(defn send-request-with-params
  [shared-transport params-value include-params-key?]
  (let [request-id (shared/next-request-id! shared-transport)
        future (java.util.concurrent.CompletableFuture.)
        request (cond-> {:jsonrpc "2.0"
                         :id request-id
                         :method "ping"}
                  include-params-key?
                  (assoc :params params-value))]
    (shared/add-pending-request! shared-transport request-id future)
    (shared/offer-to-server! shared-transport request)
    future))

;; Unit Tests

(deftest nil-params-handling-test
  ;; Contracts tested:
  ;; - JSON-RPC 2.0 spec allows params to be omitted or null
  ;; - Server must attach session metadata to params without throwing
  ;; - Server must handle both explicit nil params and missing params key
  (testing "request-handler"
    (let [{:keys [client shared-transport] :as test-env} (create-test-env)]
      (try
        (client/wait-for-ready client 5000)

        (testing "handles nil params"
          ;; Verifies metadata can be attached to nil params value
          (let [future (send-request-with-params shared-transport nil true)
                response (.get future 1000 TimeUnit/MILLISECONDS)]
            (is (some? response) (str "Expected response but got: " (pr-str response)))
            (is (= {} response) (str "Expected empty map but got: " (pr-str response)))))

        (testing "handles missing params key"
          ;; Verifies omitted params key is handled per JSON-RPC 2.0 spec
          (let [future (send-request-with-params shared-transport nil false)
                response (.get future 1000 TimeUnit/MILLISECONDS)]
            (is (some? response) (str "Expected response but got: " (pr-str response)))
            (is (= {} response) (str "Expected empty map but got: " (pr-str response)))))

        (finally
          (cleanup-test-env test-env))))))
