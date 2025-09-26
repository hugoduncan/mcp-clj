(ns mcp-clj.mcp-client.ls-tool-integration-test
  "Integration test for the ls tool using mcp-client with mcp-server"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-client.tools :as tools])
  (:import
   [java.nio.file Paths]))

(defn relativize
  "Returns the path of `to` relative to `from`.
   Both arguments should be strings representing paths."
  [from to]
  (-> (Paths/get from (into-array String []))
      (.relativize (Paths/get to (into-array String [])))
      str))

(deftest ^:integ ls-tool-integration-test
  (testing "ls tool works through mcp-client connecting to mcp-server"
    (with-open [client (client/create-client
                        {:server       {:command "clojure"
                                        :args    ["-M:stdio-server"]}
                         :client-info  {:name    "ls-integration-test-client"
                                        :version "1.0.0"}
                         :capabilities {}})]

      ;; Wait for client to initialize
      (client/wait-for-ready client 10000)
      (is (client/client-ready? client))

      (testing "can list current directory"
        (let [result (tools/call-tool-impl client "ls" {"path" "."})]
          ;; Result should be the parsed content directly
          (is (vector? result))
          (is (seq result)) ; Should have at least some files

          ;; Check that result contains parsed JSON data
          (let [first-item (first result)]
            (is (map? first-item))
            (is (= "text" (:type first-item)))
            (is (contains? first-item :data)) ; Should have parsed data
            (is (nil? (:text first-item))) ; Text should be nil after parsing

            ;; Check the parsed data structure
            (let [data (:data first-item)]
              (is (map? data))
              (is (contains? data :files))
              (is (vector? (:files data)))
              (is (seq (:files data))) ; Should have files
              (is (contains? data :total-files))
              (is (number? (:total-files data)))))))

      (testing "can list specific subdirectory"
        (let [result (tools/call-tool-impl client "ls" {"path" "components"})]
          (is (vector? result))

          ;; Should find parsed data with component files
          (let [data (:data (first result))]
            (is (map? data))
            (let [file-paths (:files data)]
              (is (some
                   #(re-find #"mcp-client|mcp-server|tools" %)
                   file-paths))))))

      (testing "respects max-files parameter"
        (let [result (tools/call-tool-impl
                      client
                      "ls"
                      {"path" "." "max-files" 5})]
          (is (vector? result))

          (let [data (:data (first result))]
            (is (<= (count (:files data)) 5)))))

      (testing "respects max-depth parameter"
        (let [result (tools/call-tool-impl
                      client
                      "ls"
                      {"path" "." "max-depth" 1})
              dir    (System/getProperty "user.dir")]
          (is (vector? result))

          ;; With depth 1, should not see deeply nested files
          (let [data  (:data (first result))
                paths (:files data)]
            (is (every?
                 (comp
                  #(< (count (filter #{\/} %)) 3)
                  #(relativize dir %))
                 paths)))))

      (testing "handles non-existent path gracefully by throwing exception"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Tool execution failed"
             (tools/call-tool-impl
              client
              "ls"
              {"path" "/non-existent-path-12345"})))

        ;; Verify the exception contains the expected error content
        (try
          (tools/call-tool-impl client "ls" {"path" "non-existent-path-12345"})
          (is false "Should have thrown exception")
          (catch clojure.lang.ExceptionInfo e
            (let [data    (ex-data e)
                  content (:content data)]
              (is (= "ls" (:tool-name data)))
              (is (vector? content))
              (let [error-item (first content)]
                (is (map? error-item))
                (is (= "text" (:type error-item)))
                (is (string? (:text error-item)))
                (is (re-find
                     #"not found|does not exist"
                     (:text error-item)))))))))))
