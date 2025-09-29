(ns mcp-clj.versions-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.versions :as versions]))

(deftest versions-test
  ;; Test that supported-versions returns versions as strings in descending order
  (testing "supported-versions has expected format and order"
    (is (vector? versions/supported-versions))
    (is (every? string? versions/supported-versions))
    ;; Verify order is descending (newest first)
    (is (= ["2025-06-18" "2025-03-26" "2024-11-05"] versions/supported-versions)))

  (testing "get-latest-version returns the first supported version"
    (is (= "2025-06-18" (versions/get-latest-version)))
    (is (= (first versions/supported-versions) (versions/get-latest-version))))

  (testing "supported? correctly identifies supported versions"
    (is (true? (versions/supported? "2025-06-18")))
    (is (true? (versions/supported? "2025-03-26")))
    (is (true? (versions/supported? "2024-11-05")))
    (is (false? (versions/supported? "2023-01-01")))
    (is (false? (versions/supported? "invalid-version")))
    (is (false? (versions/supported? nil)))))
