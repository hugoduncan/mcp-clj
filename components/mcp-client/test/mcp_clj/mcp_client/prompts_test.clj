(ns mcp-clj.mcp-client.prompts-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.client-transport.factory :as client-transport-factory]
   [mcp-clj.in-memory-transport.shared :as shared]
   [mcp-clj.mcp-client.prompts :as prompts]
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
        session (atom {:server-capabilities {:prompts {}}})
        ;; Create server using lazy-loaded function
        create-server-fn (do
                           (require 'mcp-clj.in-memory-transport.server)
                           (ns-resolve
                            'mcp-clj.in-memory-transport.server
                            'create-in-memory-server))
        server (create-server-fn
                {:shared shared-transport}
                {"prompts/list" handler
                 "prompts/get" handler})
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

(deftest test-list-prompts-success
  (testing "list-prompts-impl returns available prompts"
    (let [mock-prompts [{:name "test-prompt"
                         :description "A test prompt"
                         :arguments [{:name "input" :description "Test input"}]}
                        {:name "another-prompt"
                         :description "Another test prompt"}]
          handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "prompts/list" {:prompts mock-prompts}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (prompts/list-prompts-impl client)
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= mock-prompts (:prompts result)))
          (is (= 2 (count (:prompts result)))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-list-prompts-with-pagination
  (testing "list-prompts-impl handles pagination correctly"
    (let [handler (fn [request params]
                    (let [method (:method request)]
                      (case method
                        "prompts/list"
                        (if (:cursor params)
                          {:prompts [{:name "page2-prompt"}]}
                          {:prompts [{:name "page1-prompt"}]
                           :nextCursor "page2"})
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        ;; Test first page
        (let [result-future (prompts/list-prompts-impl client)
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= [{:name "page1-prompt"}] (:prompts result)))
          (is (= "page2" (:nextCursor result))))

        ;; Test second page with cursor
        (let [result-future (prompts/list-prompts-impl client {:cursor "page2"})
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= [{:name "page2-prompt"}] (:prompts result)))
          (is (nil? (:nextCursor result))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-get-prompt-success
  (testing "get-prompt-impl returns specific prompt"
    (let [mock-prompt {:description "Test prompt description"
                       :messages [{:role "user"
                                   :content {:type "text"
                                             :text "Please help with {{task}}"}}]}
          handler (fn [request params]
                    (let [method (:method request)]
                      (case method
                        "prompts/get"
                        (if (= "test-prompt" (:name params))
                          mock-prompt
                          {:content [{:type "text" :text "Prompt not found"}]
                           :isError true})
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (prompts/get-prompt-impl client "test-prompt")
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= mock-prompt result))
          (is (= "Test prompt description" (:description result)))
          (is (= 1 (count (:messages result)))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-get-prompt-with-arguments
  (testing "get-prompt-impl passes arguments for templating"
    (let [handler (fn [request params]
                    (let [method (:method request)]
                      (case method
                        "prompts/get"
                        (if (and (= "test-prompt" (:name params))
                                 (:arguments params))
                          {:description "Test prompt with args"
                           :messages [{:role "user"
                                       :content {:type "text"
                                                 :text "Task: coding"}}]}
                          {:content [{:type "text" :text "Missing arguments"}]
                           :isError true})
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (prompts/get-prompt-impl client "test-prompt" {:task "coding"})
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= "Test prompt with args" (:description result)))
          (is (= "Task: coding" (get-in result [:messages 0 :content :text]))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-get-prompt-error
  (testing "get-prompt-impl handles errors gracefully"
    (let [handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "prompts/get" {:content [{:type "text" :text "Prompt not found"}]
                                       :isError true}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (prompts/get-prompt-impl client "nonexistent")
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (:isError result))
          (is (= "nonexistent" (:prompt-name result)))
          (is (= [{:type "text" :text "Prompt not found"}] (:content result))))

        (finally
          (stop-server! (:server client)))))))

(deftest test-available-prompts-with-cache
  (testing "available-prompts?-impl uses cached prompts when available"
    (let [mock-prompts [{:name "test-prompt"}]
          handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "prompts/list" {:prompts mock-prompts}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        ;; First call should query server and cache results
        (let [result-future (prompts/list-prompts-impl client)]
          (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS))

        ;; Now available-prompts? should use cache
        (is (true? (prompts/available-prompts?-impl client)))

        (finally
          (stop-server! (:server client)))))))

(deftest test-available-prompts-no-cache
  (testing "available-prompts?-impl queries server when no cache"
    (let [mock-prompts [{:name "test-prompt"}]
          handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "prompts/list" {:prompts mock-prompts}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        ;; Should query server directly
        (is (true? (prompts/available-prompts?-impl client)))

        (finally
          (stop-server! (:server client)))))))

(deftest test-empty-prompts-list
  (testing "list-prompts-impl handles empty prompts list"
    (let [handler (fn [request _params]
                    (let [method (:method request)]
                      (case method
                        "prompts/list" {:prompts []}
                        nil)))
          client (create-test-client-with-handler handler)]

      (try
        (let [result-future (prompts/list-prompts-impl client)
              result (.get ^CompletableFuture result-future 5 TimeUnit/SECONDS)]

          (is (= [] (:prompts result))))

        ;; Available prompts should return false
        (is (false? (prompts/available-prompts?-impl client)))

        (finally
          (stop-server! (:server client)))))))
