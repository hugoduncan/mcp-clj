(ns mcp-clj.tools.clj-eval-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.tools.clj-eval :as clj-eval]))

(deftest clj-eval-test
  (testing "clj eval implementation"
    (let [{:keys [implementation]} clj-eval/clj-eval-tool]

      (testing "successful evaluation"
        (let [result (implementation {:code "(+ 1 2)"})]
          (is (= {:content [{:type "text" :text "3"}]
                  :isError false}
                 result))))

      (testing "divide by zero error"
        (let [result (implementation {:code "(/ 1 0)"})]
          (is (:isError result))
          (is (= "text" (-> result :content first :type)))
          (is (str/includes?
                (-> result :content first :text)
                "Divide by zero"))))

      (testing "invalid syntax"
        (let [result (implementation {:code "(/ 1 0"})]
          (is (:isError result))
          (is (= "text" (-> result :content first :type)))
          (is (str/includes?
                (-> result :content first :text)
                "EOF while reading")))))))
