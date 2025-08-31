(ns mcp-clj.mcp-server.version-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-server.version :as version]))

(deftest negotiate-version-test
  (testing "version negotiation according to MCP specification"

    (testing "client requests supported version"
      (let [result (version/negotiate-version "2025-06-18")]
        (is (= "2025-06-18" (:negotiated-version result)))
        (is (true? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2024-11-05"] (:supported-versions result)))))

    (testing "client requests older supported version"
      (let [result (version/negotiate-version "2024-11-05")]
        (is (= "2024-11-05" (:negotiated-version result)))
        (is (true? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2024-11-05"] (:supported-versions result)))))

    (testing "client requests unsupported old version - fallback to latest"
      (let [result (version/negotiate-version "2024-01-01")]
        (is (= "2025-06-18" (:negotiated-version result)))
        (is (false? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2024-11-05"] (:supported-versions result)))))

    (testing "client requests unsupported future version - fallback to latest"
      (let [result (version/negotiate-version "2026-01-01")]
        (is (= "2025-06-18" (:negotiated-version result)))
        (is (false? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2024-11-05"] (:supported-versions result)))))

    (testing "client requests malformed version - fallback to latest"
      (let [result (version/negotiate-version "0.2")]
        (is (= "2025-06-18" (:negotiated-version result)))
        (is (false? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2024-11-05"] (:supported-versions result)))))))

(deftest supported?-test
  (testing "version support checking"
    (is (true? (version/supported? "2025-06-18")))
    (is (true? (version/supported? "2024-11-05")))
    (is (false? (version/supported? "2024-01-01")))
    (is (false? (version/supported? "0.2")))))

(deftest get-latest-version-test
  (testing "latest version retrieval"
    (is (= "2025-06-18" (version/get-latest-version)))))

(deftest version-specific-behavior-test
  (testing "version-specific behavior dispatch"

    (testing "supported version with capabilities"
      (let [context {:capabilities {:tools {:listChanged true}}}
            result  (version/handle-version-specific-behavior
                    "2025-06-18" :capabilities context)]
        (is (= (:capabilities context) result))))

    (testing "older supported version with capabilities"
      (let [context {:capabilities {:tools {:listChanged true}}}
            result  (version/handle-version-specific-behavior
                    "2024-11-05" :capabilities context)]
        (is (= (:capabilities context) result))))

    (testing "unsupported feature throws exception"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unsupported feature for protocol version"
           (version/handle-version-specific-behavior
            "2025-06-18" :unknown-feature {}))))))
