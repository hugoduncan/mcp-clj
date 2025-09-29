(ns mcp-clj.mcp-client.resources-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.client-transport.factory :as client-transport-factory]
    [mcp-clj.in-memory-transport.shared :as shared]
    [mcp-clj.mcp-client.resources :as resources]
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

(defn- create-test-client-with-handler
  "Create a test client with in-memory transport and custom handler"
  [handler]
  ;; Ensure transport is registered before creating client/server
  (ensure-in-memory-transport-registered!)
  (let [shared-transport (shared/create-shared-transport)
        session (atom {})
        ;; Create server using lazy-loaded function
        create-server-fn (do
                           (require 'mcp-clj.in-memory-transport.server)
                           (ns-resolve
                             'mcp-clj.in-memory-transport.server
                             'create-in-memory-server))
        server (create-server-fn
                 {:shared shared-transport}
                 {"resources/list" handler
                  "resources/read" handler})
        ;; Create transport using lazy-loaded function
        create-transport-fn (do (require 'mcp-clj.in-memory-transport.client)
                                (ns-resolve
                                  'mcp-clj.in-memory-transport.client
                                  'create-transport))
        transport (create-transport-fn {:shared shared-transport})]
    {:session session
     :transport transport
     :server server
     :shared-transport shared-transport}))

(deftest test-list-resources-success
  (testing "list-resources-impl returns available resources"
    (let [mock-resources [{:uri "file:///test.txt"
                           :name "test.txt"
                           :description "A test text file"
                           :mimeType "text/plain"}
                          {:uri "file:///data.json"
                           :name "data.json"
                           :description "Test JSON data"}]
          handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "resources/list" {:resources mock-resources}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (resources/list-resources-impl client)
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= mock-resources (:resources result)))
          (is (= 2 (count (:resources result)))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-list-resources-with-pagination
  (testing "list-resources-impl handles pagination correctly"
    (let [handler (fn [request params]
                    (let [method (:method request)]
                      (case method
                        "resources/list"
                        (if (:cursor params)
                          {:resources [{:uri "file:///page2.txt" :name "page2.txt"}]}
                          {:resources [{:uri "file:///page1.txt" :name "page1.txt"}]
                           :nextCursor "page2"})
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        ;; Test first page
        (let [result-future (resources/list-resources-impl client)
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= [{:uri "file:///page1.txt" :name "page1.txt"}] (:resources result)))
          (is (= "page2" (:nextCursor result))))

        ;; Test second page with cursor
        (let [result-future (resources/list-resources-impl client {:cursor "page2"})
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= [{:uri "file:///page2.txt" :name "page2.txt"}] (:resources result)))
          (is (nil? (:nextCursor result))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-read-resource-text-success
  (testing "read-resource-impl returns text resource content"
    (let [mock-text-content "Hello, this is test content!"
          handler (fn [request params]
                    (let [method (:method request)]
                      (case method
                        "resources/read"
                        (if (= "file:///test.txt" (:uri params))
                          {:contents [{:uri "file:///test.txt"
                                       :text mock-text-content}]}
                          {:content [{:type "text" :text "Resource not found"}]
                           :isError true})
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (resources/read-resource-impl client "file:///test.txt")
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= {:contents [{:uri "file:///test.txt"
                              :text mock-text-content}]} result))
          (is (= mock-text-content (get-in result [:contents 0 :text]))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-read-resource-binary-success
  (testing "read-resource-impl returns binary resource content"
    (let [mock-binary-content "SGVsbG8gV29ybGQ=" ; "Hello World" in base64
          handler (fn [request params]
                    (let [method (:method request)]
                      (case method
                        "resources/read"
                        (if (= "file:///image.png" (:uri params))
                          {:contents [{:uri "file:///image.png"
                                       :blob mock-binary-content
                                       :mimeType "image/png"}]}
                          {:content [{:type "text" :text "Resource not found"}]
                           :isError true})
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (resources/read-resource-impl client "file:///image.png")
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= mock-binary-content (get-in result [:contents 0 :blob])))
          (is (= "image/png" (get-in result [:contents 0 :mimeType]))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-read-resource-error
  (testing "read-resource-impl handles errors gracefully"
    (let [handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "resources/read" {:content [{:type "text" :text "Resource not found"}]
                                          :isError true}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (resources/read-resource-impl client "file:///nonexistent.txt")
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (:isError result))
          (is (= "file:///nonexistent.txt" (:resource-uri result)))
          (is (= [{:type "text" :text "Resource not found"}] (:content result))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-available-resources-with-cache
  (testing "available-resources?-impl uses cached resources when available"
    (let [mock-resources [{:uri "file:///test.txt" :name "test.txt"}]
          handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "resources/list" {:resources mock-resources}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        ;; First call should query server and cache results
        (let [result-future (resources/list-resources-impl client)]
          (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS))

        ;; Now available-resources? should use cache
        (is (true? (resources/available-resources?-impl client)))

        (finally
          (stop-server! (:server client)))))))

(deftest test-available-resources-no-cache
  (testing "available-resources?-impl queries server when no cache"
    (let [mock-resources [{:uri "file:///test.txt" :name "test.txt"}]
          handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "resources/list" {:resources mock-resources}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        ;; Should query server directly
        (is (true? (resources/available-resources?-impl client)))

        (finally
          (stop-server! (:server client)))))))

(deftest test-empty-resources-list
  (testing "list-resources-impl handles empty resources list"
    (let [handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "resources/list" {:resources []}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (resources/list-resources-impl client)
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= [] (:resources result))))

        ;; Available resources should return false
        (is (false? (resources/available-resources?-impl client)))

        (finally
          (stop-server! (:server client)))))))
