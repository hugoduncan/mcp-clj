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
  ;; Test that the request building functions are available
  (testing "public API functions exist"
    (is (fn? http-client/http-get))
    (is (fn? http-client/http-post))
    (is (fn? http-client/http-put))
    (is (fn? http-client/http-delete))
    (is (fn? http-client/request))))
