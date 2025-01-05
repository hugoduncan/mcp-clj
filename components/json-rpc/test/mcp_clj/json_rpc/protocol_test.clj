(ns mcp-clj.json-rpc.protocol-test
  (:require
   [clojure.test :refer :all]
   [mcp-clj.json-rpc.protocol :as protocol]))

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
