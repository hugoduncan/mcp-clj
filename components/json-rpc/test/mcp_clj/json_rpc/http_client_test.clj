(ns mcp-clj.json-rpc.http-client-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.json-rpc.http-client :as http-client])
  (:import
   (java.util.concurrent ConcurrentHashMap)
   (java.util.concurrent.atomic AtomicLong)))

(deftest create-http-json-rpc-client-test
  ;; Test client creation and basic functionality without HTTP server
  (testing "create-http-json-rpc-client"
    (testing "creates client with default options"
      (let [client (http-client/create-http-json-rpc-client
                    {:url "http://example.com"})]
        (is (some? client))
        (is (= "http://example.com" (:base-url client)))
        (is (instance? ConcurrentHashMap (:pending-requests client)))
        (is (some? (:request-id-counter client)))
        (is (some? (:session-id client)))))

    (testing "creates client with custom options"
      (let [notification-handler (fn [_] nil)
            client (http-client/create-http-json-rpc-client
                    {:url "http://example.com"
                     :session-id "test-session"
                     :notification-handler notification-handler
                     :num-threads 4})]
        (is (some? client))
        (is (= "test-session" @(:session-id client)))
        (is (= notification-handler (:notification-handler client)))))))

(deftest make-headers-test
  ;; Test header creation functionality
  (testing "make-headers"
    (testing "creates basic headers without session"
      (let [client (http-client/create-http-json-rpc-client
                    {:url "http://example.com"})
            headers (#'http-client/make-headers client)]
        (is (= "application/json" (get headers "Content-Type")))
        (is (= "application/json" (get headers "Accept")))
        (is (nil? (get headers "X-Session-ID")))))

    (testing "includes session ID when present"
      (let [client (http-client/create-http-json-rpc-client
                    {:url "http://example.com"
                     :session-id "test-session"})
            headers (#'http-client/make-headers client)]
        (is (= "application/json" (get headers "Content-Type")))
        (is (= "application/json" (get headers "Accept")))
        (is (= "test-session" (get headers "X-Session-ID")))))))

(deftest compilation-and-imports-test
  ;; Test that the imports and type hints added in the fix work correctly
  (testing "compilation and imports"
    (testing "public API functions exist and compile"
      (is (fn? http-client/create-http-json-rpc-client))
      (is (fn? http-client/send-request!))
      (is (fn? http-client/send-notification!))
      (is (fn? http-client/close-http-json-rpc-client!))
      (is (fn? http-client/update-session-id!)))

    (testing "client record and internal functions work"
      (let [client (http-client/create-http-json-rpc-client
                    {:url "http://example.com"})]
        ;; Test that we can call internal functions without compilation errors
        (is (number? (#'http-client/generate-request-id client)))
        (is (map? (#'http-client/make-headers client)))))))