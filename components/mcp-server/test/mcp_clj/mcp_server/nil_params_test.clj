(ns mcp-clj.mcp-server.nil-params-test
  "Tests for handling nil/missing params in JSON-RPC requests.

  This test demonstrates the NullPointerException bug when params is nil
  (before fix in PR #37) and validates the fix works correctly."
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.client-transport.factory :as client-transport-factory]
    [mcp-clj.in-memory-transport.client :as transport-client]
    [mcp-clj.in-memory-transport.shared :as shared]
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
        in-memory-client (transport-client/create-transport
                           {:shared shared-transport})]
    {:server mcp-server
     :client in-memory-client
     :shared-transport shared-transport}))

(defn cleanup-test-env
  [{:keys [server]}]
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

(deftest ^:skip-ci nil-params-handling-test
  ;; Test that demonstrates NullPointerException with nil params (before fix)
  ;; and validates the fix works correctly after applying `(or params {})`
  (testing "MCP server handles nil/missing params in JSON-RPC requests"
    (let [{:keys [shared-transport] :as test-env} (create-test-env)]
      (try
        (Thread/sleep 100)

        (testing "with explicit nil params"
          ;; Before fix: NullPointerException at core.clj:385
          ;; After fix: Should handle gracefully
          (let [future (send-request-with-params shared-transport nil true)
                response (.get future 1000 TimeUnit/MILLISECONDS)]
            (is (some? response))
            (is (= {} response))))

        (testing "with missing params key"
          ;; Before fix: NullPointerException at core.clj:385
          ;; After fix: Should handle gracefully
          (let [future (send-request-with-params shared-transport nil false)
                response (.get future 1000 TimeUnit/MILLISECONDS)]
            (is (some? response))
            (is (= {} response))))

        (finally
          (cleanup-test-env test-env))))))
