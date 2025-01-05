(ns mcp-clj.json-rpc.server-test
  (:require [clojure.test :refer :all]
            [mcp-clj.json-rpc.server :as server]
            [mcp-clj.json-rpc.protocol :as protocol]))

(deftest server-creation
  (testing "Server creation validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                         #"Port is required"
                         (server/create-server {})))
    
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                         #"Handlers must be a map"
                         (server/create-server {:port 8080}))))

  (testing "Successful server creation"
    (let [handlers {"echo" (fn [params] {:result params})}
          {:keys [server stop]} (server/create-server
                                {:port 8080
                                 :handlers handlers})]
      (try
        (is (some? server))
        (is (fn? stop))
        (finally
          (stop))))))