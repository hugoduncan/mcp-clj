(ns mcp-clj.json-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.json :as json]))

(deftest parse-test
  ;; Test JSON parsing with automatic keyword conversion for keys.
  ;; Validates correct handling of primitives, nested structures, and errors.
  (testing "parse"
    (testing "parses simple JSON"
      (is (= {:a 1} (json/parse "{\"a\":1}")))
      (is (= {:b "text"} (json/parse "{\"b\":\"text\"}"))))

    (testing "converts keys to keywords"
      (is (= {:foo-bar 42}
             (json/parse "{\"foo-bar\":42}"))))

    (testing "parses nested structures"
      (is (= {:outer {:inner "val"}}
             (json/parse "{\"outer\":{\"inner\":\"val\"}}")))
      (is (= {:list [1 2 3]}
             (json/parse "{\"list\":[1,2,3]}"))))

    (testing "parses arrays"
      (is (= [1 2 3] (json/parse "[1,2,3]")))
      (is (= [{:a 1} {:b 2}]
             (json/parse "[{\"a\":1},{\"b\":2}]"))))

    (testing "parses JSON primitives"
      (is (= nil (json/parse "null")))
      (is (= true (json/parse "true")))
      (is (= false (json/parse "false")))
      (is (= 42 (json/parse "42")))
      (is (= 3.14 (json/parse "3.14")))
      (is (= "text" (json/parse "\"text\""))))

    (testing "parses empty structures"
      (is (= {} (json/parse "{}")))
      (is (= [] (json/parse "[]"))))

    (testing "throws on invalid JSON"
      (is (thrown? Exception (json/parse "{")))
      (is (thrown? Exception (json/parse "{invalid}"))))))

(deftest parse-error-test
  ;; Test comprehensive error handling for invalid JSON inputs.
  ;; Validates that parse errors are thrown with informative messages.
  (testing "parse errors"
    (testing "throws on unclosed structures"
      (is (thrown? Exception (json/parse "{")))
      (is (thrown? Exception (json/parse "[")))
      (is (thrown? Exception (json/parse "{\"a\":1")))
      (is (thrown? Exception (json/parse "[1,2"))))

    (testing "throws on invalid syntax"
      (is (thrown? Exception (json/parse "{invalid}")))
      (is (thrown? Exception (json/parse "{\"a\":}")))
      (is (thrown? Exception (json/parse "{\"a\":,}")))
      (is (thrown? Exception (json/parse "[,1,2]"))))

    (testing "throws on trailing commas"
      (is (thrown? Exception (json/parse "{\"a\":1,}")))
      (is (thrown? Exception (json/parse "[1,2,]"))))

    (testing "throws on unclosed strings"
      (is (thrown? Exception (json/parse "{\"a\":\"unclosed")))
      (is (thrown? Exception (json/parse "[\"unclosed]"))))

    (testing "throws on invalid escape sequences"
      (is (thrown? Exception (json/parse "{\"a\":\"\\x\"}")))
      (is (thrown? Exception (json/parse "{\"a\":\"\\u12\"}"))))

    (testing "throws on invalid numbers"
      (is (thrown? Exception (json/parse "{\"a\":1.}")))
      (is (thrown? Exception (json/parse "{\"a\":.5}")))
      (is (thrown? Exception (json/parse "{\"a\":1e}")))
      (is (thrown? Exception (json/parse "{\"a\":01}"))))

    (testing "throws on invalid keywords"
      (is (thrown? Exception (json/parse "{\"a\":TRUE}")))
      (is (thrown? Exception (json/parse "{\"a\":False}")))
      (is (thrown? Exception (json/parse "{\"a\":NULL}"))))

    (testing "throws on non-JSON input"
      (is (thrown? Exception (json/parse "not json")))
      (is (thrown? Exception (json/parse "123abc")))
      (is (thrown? Exception (json/parse "abc123"))))))

(deftest write-test
  ;; Test EDN to JSON conversion with keyword key conversion.
  ;; Validates correct handling of primitives, nested structures, and edge cases.
  (testing "write"
    (testing "writes simple EDN to JSON"
      (is (= "{\"a\":1}" (json/write {:a 1})))
      (is (= "{\"b\":\"text\"}" (json/write {:b "text"}))))

    (testing "converts keyword keys to strings"
      (is (= "{\"foo-bar\":42}"
             (json/write {:foo-bar 42}))))

    (testing "writes nested structures"
      (is (= "{\"outer\":{\"inner\":\"val\"}}"
             (json/write {:outer {:inner "val"}})))
      (is (= "{\"list\":[1,2,3]}"
             (json/write {:list [1 2 3]}))))

    (testing "writes arrays"
      (is (= "[1,2,3]" (json/write [1 2 3])))
      (is (= "[{\"a\":1},{\"b\":2}]"
             (json/write [{:a 1} {:b 2}]))))

    (testing "writes EDN primitives"
      (is (= "null" (json/write nil)))
      (is (= "true" (json/write true)))
      (is (= "false" (json/write false)))
      (is (= "42" (json/write 42)))
      (is (= "3.14" (json/write 3.14)))
      (is (= "\"text\"" (json/write "text"))))

    (testing "writes empty structures"
      (is (= "{}" (json/write {})))
      (is (= "[]" (json/write []))))))

(deftest roundtrip-test
  ;; Test that parse and write are inverse operations.
  ;; Validates data integrity through JSON serialization roundtrip.
  (testing "roundtrip"
    (testing "preserves data through parse and write"
      (let [data {:a 1 :b "text" :c {:nested true}}]
        (is (= data (json/parse (json/write data)))))

      (let [data [{:x 1} {:y 2}]]
        (is (= data (json/parse (json/write data)))))

      (let [data {:nil nil :bool true :num 42 :str "val"}]
        (is (= data (json/parse (json/write data))))))))
