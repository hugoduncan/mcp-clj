(ns mcp-clj.tools.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.tools.clj-eval :as clj-eval]
    [mcp-clj.tools.core :as tools]))

(deftest valid-tool-test
  (testing "tool validation"
    (testing "valid tool"
      (is (tools/valid-tool? clj-eval/clj-eval-tool)))

    (testing "invalid tool - missing name"
      (is (not (tools/valid-tool? (dissoc clj-eval/clj-eval-tool :name)))))

    (testing "invalid tool - missing implementation"
      (is (not (tools/valid-tool? (dissoc clj-eval/clj-eval-tool :implementation)))))))

(deftest tool-definition-test
  (testing "tool definition extraction"
    (let [definition (tools/tool-definition clj-eval/clj-eval-tool)]
      (is (= "clj-eval" (:name definition)))
      (is (string? (:description definition)))
      (is (map? (:inputSchema definition)))
      (is (nil? (:implementation definition))))))

(deftest default-tools-test
  (testing "default tools registry"
    (is (map? tools/default-tools))
    (is (contains? tools/default-tools "clj-eval"))
    (is (tools/valid-tool? (get tools/default-tools "clj-eval")))))
