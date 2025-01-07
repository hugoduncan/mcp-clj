(ns mcp-clj.mcp-server.tools-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-server.tools :as tools]))

(deftest list-tools-test
  (testing "list tools"
    (let [result (tools/list-tools {})]
      (is (vector? (:tools result)))
      (is (pos? (count (:tools result))))
      (is (= "clj-eval" (-> result :tools first :name))))))

(deftest call-tool-test
  (testing "clj-eval tool"
    (testing "successful evaluation"
      (let [result (tools/call-tool
                    {:name      "clj-eval"
                     :arguments {:code "(+ 1 2)"}})]
        (is (= [{:type "text"
                 :text "3"}]
               (:content result)))
        (is (not (:isError result)))))

    (testing "evaluation error"
      (let [result (tools/call-tool
                    {:name      "clj-eval"
                     :arguments {:code "(/ 1 0)"}})]
        (is (:isError result))
        (is (= 1 (count (:content result))))
        (is (= "text" (-> result :content first :type)))
        (is (string? (-> result :content first :text))))))

  (testing "unknown tool"
    (let [result (tools/call-tool {:name "unknown" :arguments {}})]
      (is (:isError result))
      (is (= [{:type "text"
               :text "Tool not found: unknown"}]
             (:content result))))))
