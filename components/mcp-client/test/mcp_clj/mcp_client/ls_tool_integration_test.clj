(ns mcp-clj.mcp-client.ls-tool-integration-test
  "Integration test for the ls tool using mcp-client with mcp-server"
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.core :as client]
   [mcp-clj.mcp-client.tools :as tools]))

(deftest ^:integration ls-tool-integration-test
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
          (is (false? (:isError result)))
          (is (vector? (:content result)))
          (is (seq (:content result))) ; Should have at least some files

          ;; Check that result contains file objects with expected structure
          (let [first-file (first (:content result))]
            (is (map? first-file))
            (is (contains? first-file :name))
            (is (contains? first-file :type))
            (is (contains? first-file :path)))))

      (testing "can list specific subdirectory"
        (let [result (tools/call-tool-impl client "ls" {"path" "components"})]
          (is (false? (:isError result)))
          (is (vector? (:content result)))

          ;; Should find some component directories
          (let [names (map :name (:content result))]
            (is (some #{"mcp-client" "mcp-server" "tools"} names)))))

      (testing "respects max-files parameter"
        (let [result (tools/call-tool-impl client "ls" {"path" "." "max-files" 5})]
          (is (false? (:isError result)))
          (is (vector? (:content result)))
          (is (<= (count (:content result)) 5))))

      (testing "respects max-depth parameter"
        (let [result (tools/call-tool-impl client "ls" {"path" "." "max-depth" 1})]
          (is (false? (:isError result)))
          (is (vector? (:content result)))

          ;; With depth 1, should not see deeply nested files
          (let [paths (map :path (:content result))]
            (is (every? #(< (count (filter #{\/} %)) 3) paths)))))

      (testing "handles non-existent path gracefully"
        (let [result (tools/call-tool-impl
                      client
                      "ls"
                      {"path" "/non-existent-path-12345"})]
          (is (true? (:isError result)))
          (is (vector? (:content result)))
          (let [error-msg (first (:content result))]
            (is (map? error-msg))
            (is (= "text" (:type error-msg)))
            (is (re-find #"not found|does not exist" (:text error-msg))))))

      (prn :done)))
  (prn :all-done))
