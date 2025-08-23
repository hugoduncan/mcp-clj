(ns mcp-clj.tools.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.tools.core :as tools]))

(deftest clj-eval-test
  (testing "clj eval implementation"
    (let [{:keys [implementation]} tools/clj-eval-tool]

      (testing "successful evaluation"
        (let [result (implementation {:code "(+ 1 2)"})]
          (is (= {:content [{:type "text"
                             :text "3\n"}]}
                 result))))

      (testing "divide by zero error"
        (let [result (implementation {:code "(/ 1 0)"})]
          (is (:isError result))
          (is (= "text" (-> result :content first :type)))
          (is (.contains
               (-> result :content first :text)
               "Divide by zero"))))

      (testing "invalid syntax"
        (let [result (implementation {:code "(/ 1 0"})]
          (is (:isError result))
          (is (= "text" (-> result :content first :type)))
          (is (.contains
               (-> result :content first :text)
               "EOF while reading")))))))

(deftest valid-tool-test
  (testing "tool validation"
    (testing "valid tool"
      (is (tools/valid-tool? tools/clj-eval-tool)))
    
    (testing "invalid tool - missing name"
      (is (not (tools/valid-tool? (dissoc tools/clj-eval-tool :name)))))
    
    (testing "invalid tool - missing implementation"
      (is (not (tools/valid-tool? (dissoc tools/clj-eval-tool :implementation)))))))

(deftest tool-definition-test
  (testing "tool definition extraction"
    (let [definition (tools/tool-definition tools/clj-eval-tool)]
      (is (= "clj-eval" (:name definition)))
      (is (string? (:description definition)))
      (is (map? (:inputSchema definition)))
      (is (nil? (:implementation definition))))))

(deftest default-tools-test
  (testing "default tools registry"
    (is (map? tools/default-tools))
    (is (contains? tools/default-tools "clj-eval"))
    (is (tools/valid-tool? (get tools/default-tools "clj-eval")))))