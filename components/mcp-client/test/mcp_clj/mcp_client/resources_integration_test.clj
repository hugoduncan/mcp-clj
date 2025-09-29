(ns ^:integ mcp-clj.mcp-client.resources-integration-test
  "Integration tests for resource functionality using full client setup"
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

;; Transport registration function for robust test isolation
(defn ensure-in-memory-transport-registered!
  "Ensure in-memory transport is registered in both client and server factories.
  Can be called multiple times safely - registration is idempotent."
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

;; Ensure transport is registered at namespace load time
(ensure-in-memory-transport-registered!)

(defn- stop-server!
  "Stop an in-memory server using lazy-loaded function"
  [server]
  (require 'mcp-clj.in-memory-transport.server)
  ((ns-resolve 'mcp-clj.in-memory-transport.server 'stop!) server))

(defn- create-test-server-with-resources
  "Create a test server with resource handlers"
  [shared-transport]
  (let [mock-resources [{:uri "file:///readme.txt"
                         :name "readme.txt"
                         :description "Project readme file"
                         :mimeType "text/plain"}
                        {:uri "file:///config.json"
                         :name "config.json"
                         :description "Configuration file"}]
        mock-content {"file:///readme.txt" {:contents [{:uri "file:///readme.txt"
                                                        :text "# Project README\nWelcome to our project!"}]}
                      "file:///config.json" {:contents [{:uri "file:///config.json"
                                                         :text "{\"version\": \"1.0\", \"debug\": true}"}]}}
        handlers {"initialize" (fn [_req _params]
                                 {:protocolVersion "2025-06-18"
                                  :capabilities {:resources {}}
                                  :serverInfo {:name "test-server"
                                               :version "1.0.0"}})
                  "resources/list" (fn [_req params]
                                     (if (:cursor params)
                                       {:resources []}
                                       {:resources mock-resources}))
                  "resources/read" (fn [_req params]
                                     (if-let [content (get mock-content (:uri params))]
                                       content
                                       {:content [{:type "text" :text "Resource not found"}]
                                        :isError true}))}
        create-server-fn (do
                           (require 'mcp-clj.in-memory-transport.server)
                           (ns-resolve
                             'mcp-clj.in-memory-transport.server
                             'create-in-memory-server))]
    (create-server-fn {:shared shared-transport} handlers)))

(deftest ^:integ test-client-resource-integration
  (testing "full client integration with resources"
    (ensure-in-memory-transport-registered!)
    (let [shared-transport (shared/create-shared-transport)
          server (create-test-server-with-resources shared-transport)
          client-config {:transport {:type :in-memory
                                     :shared shared-transport}
                         :client-info {:name "test-client"
                                       :version "1.0.0"}}
          test-client (client/create-client client-config)]

      (try
        ;; Wait for client to initialize
        (client/wait-for-ready test-client 5000)
        (is (client/client-ready? test-client))

        ;; Test resource availability
        (is (client/available-resources? test-client))

        ;; Test listing resources
        (let [resources-future (client/list-resources test-client)
              resources-result (.get ^CompletableFuture resources-future 5 TimeUnit/SECONDS)]
          (is (= 2 (count (:resources resources-result))))
          (is (some #(= "readme.txt" (:name %)) (:resources resources-result)))
          (is (some #(= "config.json" (:name %)) (:resources resources-result))))

        ;; Test reading a text resource
        (let [content-future (client/read-resource test-client "file:///readme.txt")
              content-result (.get ^CompletableFuture content-future 5 TimeUnit/SECONDS)]
          (is (= "# Project README\nWelcome to our project!"
                 (get-in content-result [:contents 0 :text]))))

        ;; Test reading a JSON resource
        (let [content-future (client/read-resource test-client "file:///config.json")
              content-result (.get ^CompletableFuture content-future 5 TimeUnit/SECONDS)]
          (is (= "{\"version\": \"1.0\", \"debug\": true}"
                 (get-in content-result [:contents 0 :text]))))

        ;; Test error handling for non-existent resource
        (let [error-future (client/read-resource test-client "file:///nonexistent.txt")
              error-result (.get ^CompletableFuture error-future 5 TimeUnit/SECONDS)]
          (is (:isError error-result))
          (is (= "file:///nonexistent.txt" (:resource-uri error-result))))

        (finally
          (client/close! test-client)
          (stop-server! server))))))

(deftest ^:integ test-client-resource-pagination
  (testing "resource listing with pagination"
    (ensure-in-memory-transport-registered!)
    (let [shared-transport (shared/create-shared-transport)
          ;; Create server with pagination support
          handlers {"initialize" (fn [_req _params]
                                   {:protocolVersion "2025-06-18"
                                    :capabilities {:resources {}}
                                    :serverInfo {:name "test-server"
                                                 :version "1.0.0"}})
                    "resources/list" (fn [_req params]
                                       (if (:cursor params)
                                         {:resources [{:uri "file:///page2.txt"
                                                       :name "page2.txt"}]}
                                         {:resources [{:uri "file:///page1.txt"
                                                       :name "page1.txt"}]
                                          :nextCursor "page2"}))}
          create-server-fn (do
                             (require 'mcp-clj.in-memory-transport.server)
                             (ns-resolve
                               'mcp-clj.in-memory-transport.server
                               'create-in-memory-server))
          server (create-server-fn {:shared shared-transport} handlers)
          client-config {:transport {:type :in-memory
                                     :shared shared-transport}
                         :client-info {:name "test-client"
                                       :version "1.0.0"}}
          test-client (client/create-client client-config)]

      (try
        ;; Wait for client to initialize
        (client/wait-for-ready test-client 5000)

        ;; Test first page
        (let [page1-future (client/list-resources test-client)
              page1-result (.get ^CompletableFuture page1-future 5 TimeUnit/SECONDS)]
          (is (= 1 (count (:resources page1-result))))
          (is (= "page1.txt" (get-in page1-result [:resources 0 :name])))
          (is (= "page2" (:nextCursor page1-result))))

        ;; Test second page with cursor
        (let [page2-future (client/list-resources test-client {:cursor "page2"})
              page2-result (.get ^CompletableFuture page2-future 5 TimeUnit/SECONDS)]
          (is (= 1 (count (:resources page2-result))))
          (is (= "page2.txt" (get-in page2-result [:resources 0 :name])))
          (is (nil? (:nextCursor page2-result))))

        (finally
          (client/close! test-client)
          (stop-server! server))))))
