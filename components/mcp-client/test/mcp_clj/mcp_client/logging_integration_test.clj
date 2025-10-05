(ns ^:integ mcp-clj.mcp-client.logging-integration-test
  "Integration tests for logging functionality using full client-server setup"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.client-transport.factory :as client-transport-factory]
   [mcp-clj.in-memory-transport.shared :as shared]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.server-transport.factory :as server-transport-factory])
  (:import
   (java.util.concurrent
    CompletableFuture
    TimeUnit)))

(defn ensure-in-memory-transport-registered!
  "Ensure in-memory transport is registered in both client and server factories."
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

(defn- stop-server!
  "Stop an in-memory server using lazy-loaded function"
  [server]
  (require 'mcp-clj.in-memory-transport.server)
  ((ns-resolve 'mcp-clj.in-memory-transport.server 'stop!) server))

(defn- create-test-server-with-logging
  "Create a test server that supports logging"
  [shared-transport]
  (let [current-log-level (atom :info)
        handlers {"initialize" (fn [_req params]
                                 ;; Use client's requested protocol version
                                 {:protocolVersion (:protocolVersion params)
                                  :capabilities {:logging {}}
                                  :serverInfo {:name "test-server"
                                               :version "1.0.0"}})
                  "logging/setLevel" (fn [_req params]
                                       (reset! current-log-level (keyword (:level params)))
                                       {})}
        create-server-fn (do
                           (require 'mcp-clj.in-memory-transport.server)
                           (ns-resolve
                            'mcp-clj.in-memory-transport.server
                            'create-in-memory-server))]
    {:server (create-server-fn {:shared shared-transport} handlers)
     :log-level current-log-level}))

(defn- send-log-message!
  "Send a log message notification from server to client"
  [server level logger data]
  (require 'mcp-clj.json-rpc.protocols)
  (let [notify-all! (ns-resolve
                     'mcp-clj.json-rpc.protocols
                     'notify-all!)]
    (notify-all! server "notifications/message"
                 (cond-> {:level level :data data}
                   logger (assoc :logger logger)))))

(deftest ^:integ test-set-log-level-integration
  ;; Tests setting log level on a real server
  (testing "set-log-level! sends request to server"
    (ensure-in-memory-transport-registered!)
    (let [shared-transport (shared/create-shared-transport)
          {:keys [server log-level]} (create-test-server-with-logging shared-transport)
          client-config {:transport {:type :in-memory
                                     :shared shared-transport}
                         :client-info {:name "test-client"
                                       :version "1.0.0"}}
          test-client (client/create-client client-config)]

      (try
        (client/wait-for-ready test-client 5000)
        (is (client/client-ready? test-client))

        (testing "sets log level to warning"
          (let [result-future (client/set-log-level! test-client :warning)]
            (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)
            (is (= :warning @log-level))))

        (testing "sets log level to error"
          (let [result-future (client/set-log-level! test-client :error)]
            (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)
            (is (= :error @log-level))))

        (testing "sets log level to debug"
          (let [result-future (client/set-log-level! test-client :debug)]
            (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)
            (is (= :debug @log-level))))

        (finally
          (client/close! test-client)
          (stop-server! server))))))

(deftest ^:integ test-subscribe-log-messages-integration
  ;; Tests subscribing to log messages from a real server
  (testing "subscribe-log-messages! receives notifications"
    (ensure-in-memory-transport-registered!)
    (let [shared-transport (shared/create-shared-transport)
          {:keys [server]} (create-test-server-with-logging shared-transport)
          client-config {:transport {:type :in-memory
                                     :shared shared-transport}
                         :client-info {:name "test-client"
                                       :version "1.0.0"}}
          test-client (client/create-client client-config)
          received-messages (atom [])]

      (try
        (client/wait-for-ready test-client 5000)

        (testing "receives log messages with all fields"
          (let [unsub-future (client/subscribe-log-messages!
                              test-client
                              (fn [msg] (swap! received-messages conj msg)))
                unsub (.get ^CompletableFuture unsub-future 5 TimeUnit/SECONDS)]

            (send-log-message! server "error" "db" {:msg "Connection failed"})
            (Thread/sleep 100)

            (is (= 1 (count @received-messages)))
            (is (= :error (get-in @received-messages [0 :level])))
            (is (= "db" (get-in @received-messages [0 :logger])))
            (is (= {:msg "Connection failed"} (get-in @received-messages [0 :data])))

            (unsub)))

        (testing "receives log messages without logger field"
          (reset! received-messages [])
          (let [unsub-future (client/subscribe-log-messages!
                              test-client
                              (fn [msg] (swap! received-messages conj msg)))
                unsub (.get ^CompletableFuture unsub-future 5 TimeUnit/SECONDS)]

            (send-log-message! server "info" nil "Server started")
            (Thread/sleep 100)

            (is (= 1 (count @received-messages)))
            (is (= :info (get-in @received-messages [0 :level])))
            (is (nil? (get-in @received-messages [0 :logger])))
            (is (= "Server started" (get-in @received-messages [0 :data])))

            (unsub)))

        (testing "multiple subscribers receive same message"
          (reset! received-messages [])
          (let [received2 (atom [])
                unsub1-future (client/subscribe-log-messages!
                               test-client
                               (fn [msg] (swap! received-messages conj msg)))
                unsub2-future (client/subscribe-log-messages!
                               test-client
                               (fn [msg] (swap! received2 conj msg)))
                unsub1 (.get ^CompletableFuture unsub1-future 5 TimeUnit/SECONDS)
                unsub2 (.get ^CompletableFuture unsub2-future 5 TimeUnit/SECONDS)]

            (send-log-message! server "warning" "api" {:status 429})
            (Thread/sleep 100)

            (is (= 1 (count @received-messages)))
            (is (= 1 (count @received2)))
            (is (= :warning (get-in @received-messages [0 :level])))
            (is (= :warning (get-in @received2 [0 :level])))

            (unsub1)
            (unsub2)))

        (testing "unsubscribe stops receiving messages"
          (reset! received-messages [])
          (let [unsub-future (client/subscribe-log-messages!
                              test-client
                              (fn [msg] (swap! received-messages conj msg)))
                unsub (.get ^CompletableFuture unsub-future 5 TimeUnit/SECONDS)]

            (send-log-message! server "error" nil "Before unsub")
            (Thread/sleep 100)
            (is (= 1 (count @received-messages)))

            (unsub)

            (send-log-message! server "error" nil "After unsub")
            (Thread/sleep 100)
            (is (= 1 (count @received-messages)))))

        (finally
          (client/close! test-client)
          (stop-server! server))))))

(deftest ^:integ test-log-level-validation-integration
  ;; Tests validation of log levels in real client
  (testing "set-log-level! validates levels"
    (ensure-in-memory-transport-registered!)
    (let [shared-transport (shared/create-shared-transport)
          {:keys [server]} (create-test-server-with-logging shared-transport)
          client-config {:transport {:type :in-memory
                                     :shared shared-transport}
                         :client-info {:name "test-client"
                                       :version "1.0.0"}}
          test-client (client/create-client client-config)]

      (try
        (client/wait-for-ready test-client 5000)

        (testing "throws for invalid log level"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Invalid log level"
               (client/set-log-level! test-client :invalid)))

          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Invalid log level"
               (client/set-log-level! test-client :trace))))

        (finally
          (client/close! test-client)
          (stop-server! server))))))
