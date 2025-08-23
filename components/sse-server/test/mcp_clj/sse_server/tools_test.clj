(ns mcp-clj.sse-server.tools-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [mcp-clj.sse-server.core :as mcp]
   [mcp-clj.sse-server.tools]))

(def ^:private ^:dynamic *server*)

(defn with-server
  "Test fixture for server lifecycle"
  [f]
  (let [server (mcp/create-server {:port 0 :threads 2 :queue-size 10})]
    (try
      (binding [*server* server]
        (f))
      (finally
        ((:stop server))))))

(use-fixtures :each with-server)

(deftest clj-eval-test
  (testing "clj eval"
    (let [{:keys [implementation]} (get @(:tool-registry *server*) "clj-eval")]

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
