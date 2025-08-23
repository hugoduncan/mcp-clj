(ns mcp-clj.json-rpc.protocols-test
  "Integration tests for polymorphic JSON-RPC server operations"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.json-rpc.protocols :as protocols]
   [mcp-clj.json-rpc.sse-server :as sse-server]
   [mcp-clj.json-rpc.stdio-server :as stdio-server]))

(defn- test-server-polymorphism
  "Test polymorphic behavior across different server types"
  [create-server-fn server-type]
  (let [server (create-server-fn)
        handlers {"echo" (fn [method params] params)
                  "add" (fn [method params] {:sum (+ (first params) (second params))})}]

    (testing (str server-type " - satisfies JsonRpcServer protocol")
      (is (satisfies? protocols/JsonRpcServer server)))

    (testing (str server-type " - set-handlers! works polymorphically")
      (protocols/set-handlers! server handlers)
      (is (= handlers @(:handlers server))))

    (testing (str server-type " - notify-all! works polymorphically")
      ;; Should not throw for either server type
      (is (nil? (protocols/notify-all! server "test-event" {:data "test"}))))

    (testing (str server-type " - stop! works polymorphically")
      (is (nil? (protocols/stop! server))))))

(deftest polymorphic-server-operations-test
  (testing "SSE Server polymorphic operations"
    (test-server-polymorphism
     #(sse-server/create-server {:port 0 :num-threads 2})
     "SSE Server"))

  (testing "Stdio Server polymorphic operations"
    (test-server-polymorphism
     #(stdio-server/create-server {:num-threads 2})
     "Stdio Server")))

(deftest protocol-interface-consistency-test
  (testing "Both servers expose the same protocol interface"
    (let [sse-server (sse-server/create-server {:port 0})
          stdio-server (stdio-server/create-server {})]

      (testing "Both servers satisfy JsonRpcServer protocol"
        (is (satisfies? protocols/JsonRpcServer sse-server))
        (is (satisfies? protocols/JsonRpcServer stdio-server)))

      (testing "Protocol functions work on both server types"
        (let [test-handlers {"test" (fn [method params] {:result params})}]
          ;; Test set-handlers! on both
          (protocols/set-handlers! sse-server test-handlers)
          (protocols/set-handlers! stdio-server test-handlers)

          (is (= test-handlers @(:handlers sse-server)))
          (is (= test-handlers @(:handlers stdio-server)))

          ;; Test notify-all! on both (should not throw)
          (is (nil? (protocols/notify-all! sse-server "event" {})))
          (is (nil? (protocols/notify-all! stdio-server "event" {})))

          ;; Test stop! on both
          (protocols/stop! sse-server)
          (protocols/stop! stdio-server))))))

(deftest protocol-error-handling-test
  (testing "Protocol implementations handle errors consistently"
    (let [sse-server (sse-server/create-server {:port 0})
          stdio-server (stdio-server/create-server {})]

      (testing "Invalid handlers throw consistent errors"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Handlers must be a map"
             (protocols/set-handlers! sse-server "not-a-map")))

        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Handlers must be a map"
             (protocols/set-handlers! stdio-server "not-a-map"))))

      ;; Clean up
      (protocols/stop! sse-server)
      (protocols/stop! stdio-server))))

(deftest cross-server-handler-compatibility-test
  (testing "Handlers work consistently across server types"
    (let [shared-handlers {"echo" (fn [method params] params)
                           "multiply" (fn [method params]
                                        {:result (* (first params) (second params))})
                           "error" (fn [method params]
                                     (throw (ex-info "Test error" {:params params})))}
          sse-server (sse-server/create-server {:port 0})
          stdio-server (stdio-server/create-server {})]

      (testing "Same handlers can be set on different server types"
        (protocols/set-handlers! sse-server shared-handlers)
        (protocols/set-handlers! stdio-server shared-handlers)

        (is (= shared-handlers @(:handlers sse-server)))
        (is (= shared-handlers @(:handlers stdio-server))))

      (testing "Handler function signatures are compatible"
        ;; Both servers should handle the same handler function signature
        (let [test-handler (fn [method params]
                             {:method method :params params :server-type "shared"})]
          (protocols/set-handlers! sse-server {"shared-test" test-handler})
          (protocols/set-handlers! stdio-server {"shared-test" test-handler})

          (is (= {"shared-test" test-handler} @(:handlers sse-server)))
          (is (= {"shared-test" test-handler} @(:handlers stdio-server)))))

      ;; Clean up
      (protocols/stop! sse-server)
      (protocols/stop! stdio-server))))

(deftest polymorphic-server-management-test
  (testing "Multiple servers can be managed polymorphically"
    (let [servers [(sse-server/create-server {:port 0})
                   (stdio-server/create-server {})
                   (sse-server/create-server {:port 0})
                   (stdio-server/create-server {})]
          shared-handlers {"ping" (fn [method params] "pong")}]

      (testing "Bulk operations on heterogeneous server collection"
        ;; Set handlers on all servers
        (doseq [server servers]
          (protocols/set-handlers! server shared-handlers))

        ;; Verify all servers have the handlers
        (is (every? #(= shared-handlers @(:handlers %)) servers))

        ;; Send notifications to all servers
        (doseq [server servers]
          (is (nil? (protocols/notify-all! server "bulk-notification" {:test true}))))

        ;; Stop all servers
        (doseq [server servers]
          (protocols/stop! server))))))