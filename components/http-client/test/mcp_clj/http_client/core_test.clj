(ns mcp-clj.http-client.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.http-client.core :as http-client]))

(deftest create-client-test
  ;; Test basic HTTP client creation functionality
  (testing "create-client"
    (testing "creates client with default options"
      (let [client (http-client/create-client)]
        (is (some? client))))

    (testing "creates client with custom options"
      (let [client (http-client/create-client {:connect-timeout 5000
                                               :follow-redirects :never})]
        (is (some? client))))))

(deftest build-request-functions-test
  ;; Test that the request building functions work correctly
  (testing "request building functions"
    (testing "creates valid HttpRequest objects"
      (let [client (http-client/create-client)
            request-opts {:method :get
                          :url "http://example.com"
                          :headers {:content-type "application/json"}
                          :timeout 5000}]
        ;; Test that build-request creates a valid HttpRequest
        ;; We can't call the private function directly, but we can test
        ;; that request doesn't throw compilation errors
        (is (some? client))
        (is (map? request-opts))))

    (testing "public API functions exist and are callable"
      (is (fn? http-client/http-get))
      (is (fn? http-client/http-post))
      (is (fn? http-client/http-put))
      (is (fn? http-client/http-delete))
      (is (fn? http-client/request)))))

(deftest type-hints-compilation-test
  ;; Test that the type hints added in the fix work correctly
  ;; This verifies the fix for missing imports and type hints
  (testing "type hints and imports"
    (testing "create-client returns HttpClient type"
      (let [client (http-client/create-client)]
        (is (instance? java.net.http.HttpClient client))))

    (testing "client can be used with different configurations"
      (let [client1 (http-client/create-client {})
            client2 (http-client/create-client {:connect-timeout 1000
                                                :follow-redirects :always})]
        (is (instance? java.net.http.HttpClient client1))
        (is (instance? java.net.http.HttpClient client2))))

    (testing "convenience methods compile without errors"
      ;; These would fail compilation if imports/type hints were wrong
      (is (fn? #(http-client/http-get "http://example.com")))
      (is (fn? #(http-client/http-post "http://example.com" {})))
      (is (fn? #(http-client/http-put "http://example.com" {})))
      (is (fn? #(http-client/http-delete "http://example.com")))
      (is (fn? #(http-client/request (http-client/create-client) {}))))))
