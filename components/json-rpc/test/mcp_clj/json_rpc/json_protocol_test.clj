(ns mcp-clj.json-rpc.json-protocol-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.json-rpc.json-protocol :as protocol]))

(deftest json-conversion
  (testing "EDN to JSON conversion"
    (let [[json err] (protocol/write-json {:a 1 :b [1 2 3]})]
      (is (nil? err))
      (is (= "{\"a\":1,\"b\":[1,2,3]}" json))))

  (testing "JSON to EDN conversion"
    (let [[data err] (protocol/parse-json "{\"a\":1,\"b\":[1,2,3]}")]
      (is (nil? err))
      (is (= {:a 1 :b [1 2 3]} data)))))

(deftest request-validation
  (testing "Valid request"
    (is (nil? (protocol/validate-request
                {:jsonrpc "2.0"
                 :method  "test"
                 :params  {:a 1}}))))

  (testing "Invalid version"
    (let [response (protocol/validate-request
                     {:jsonrpc "1.0"
                      :method  "test"})]
      (is (= "Invalid JSON-RPC version"
             (get-in response [:error :message]))))))

(deftest exception-info->error-response-test
  ;; Test that ExceptionInfo errors are converted to JSON-RPC error responses
  ;; with proper fallback handling when :code or :message are missing

  (testing "exception-info->error-response"
    (testing "with both :code and :message in ex-data"
      (let [ex (ex-info "Base msg" {:code -32602 :message "Custom error"})
            response (protocol/exception-info->error-response ex 123)]
        (is (= "2.0" (:jsonrpc response)))
        (is (= 123 (:id response)))
        (is (= -32602 (get-in response [:error :code])))
        (is (= "Custom error" (get-in response [:error :message])))
        (is (nil? (get-in response [:error :data])))))

    (testing "with :code, :message, and additional fields in ex-data"
      (let [ex (ex-info "Base msg" {:code -32602
                                    :message "Custom error"
                                    :details "Extra info"
                                    :context {:foo "bar"}})
            response (protocol/exception-info->error-response ex 123)]
        (is (= -32602 (get-in response [:error :code])))
        (is (= "Custom error" (get-in response [:error :message])))
        (is (= {:details "Extra info" :context {:foo "bar"}}
               (get-in response [:error :data])))))

    (testing "with only :code (missing :message) falls back to internal error"
      (let [ex (ex-info "Exception message" {:code -32602})
            response (protocol/exception-info->error-response ex 123)]
        (is (= -32603 (get-in response [:error :code])))
        (is (= "Exception message" (get-in response [:error :message])))))

    (testing "with only :message (missing :code) falls back to internal error"
      (let [ex (ex-info "Exception message" {:message "Custom error"})
            response (protocol/exception-info->error-response ex 123)]
        (is (= -32603 (get-in response [:error :code])))
        (is (= "Exception message" (get-in response [:error :message])))))

    (testing "with empty ex-data falls back to internal error"
      (let [ex (ex-info "Exception message" {})
            response (protocol/exception-info->error-response ex 123)]
        (is (= -32603 (get-in response [:error :code])))
        (is (= "Exception message" (get-in response [:error :message])))))

    (testing "with nil ex-data falls back to internal error"
      (let [ex (ex-info "Exception message" nil)
            response (protocol/exception-info->error-response ex 123)]
        (is (= -32603 (get-in response [:error :code])))
        (is (= "Exception message" (get-in response [:error :message])))))))
