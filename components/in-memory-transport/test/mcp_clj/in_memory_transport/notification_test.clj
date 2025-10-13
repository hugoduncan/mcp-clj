(ns mcp-clj.in-memory-transport.notification-test
  "Test for notification delivery in in-memory transport"
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.in-memory-transport.client :as client]
    [mcp-clj.in-memory-transport.server :as server]
    [mcp-clj.in-memory-transport.shared :as shared]))

(defn- wait-for
  "Wait for a predicate to become true, with timeout"
  [pred timeout-ms]
  (let [start (System/currentTimeMillis)
        deadline (+ start timeout-ms)]
    (loop []
      (if (pred)
        true
        (if (> (System/currentTimeMillis) deadline)
          false
          (do
            (Thread/sleep 10)
            (recur)))))))

(deftest ^:integ notification-delivery-test
  ;; Test that server notifications are delivered to the client
  (testing "server notifications reach client notification handler"
    (let [received-notifications (atom [])
          notification-handler (fn [notification]
                                 (swap! received-notifications conj notification))
          shared-transport (shared/create-shared-transport)

          ;; Create handlers for server
          handlers {"ping" (fn [_request _params] {})}

          ;; Create server and client
          in-memory-server (server/create-in-memory-server
                             {:shared shared-transport}
                             handlers)
          _in-memory-client (client/create-transport
                              {:shared shared-transport
                               :notification-handler notification-handler})]

      (try
        ;; Give the transport time to initialize
        (Thread/sleep 100)

        ;; Manually send a notification from server to client
        ;; This simulates what notify-all! should do
        (shared/offer-to-client! shared-transport
                                 {:jsonrpc "2.0"
                                  :method "notifications/test"
                                  :params {:message "hello"}})

        ;; Wait for notification to be processed
        (is (wait-for #(seq @received-notifications) 1000)
            "Notification should be received within timeout")

        ;; Verify notification was received
        (is (= 1 (count @received-notifications)))
        (is (= "notifications/test" (:method (first @received-notifications))))
        (is (= {:message "hello"} (:params (first @received-notifications))))

        (finally
          (server/stop! in-memory-server))))))
