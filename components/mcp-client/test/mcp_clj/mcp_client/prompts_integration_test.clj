(ns mcp-clj.mcp-client.prompts-integration-test
  "Integration tests for MCP client prompts functionality using real MCP server"
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.mcp-client.core :as client]
    [mcp-clj.mcp-client.prompts :as prompts]))

(deftest ^:integ prompts-list-integration-test
  (testing "MCP client can list prompts from real MCP server"
    (with-open [client (client/create-client
                         {:transport    {:type    :stdio
                                         :command "clojure"
                                         :args    ["-M:stdio-server"]}
                          :client-info  {:name    "prompts-list-integration-test"
                                         :version "1.0.0"}
                          :capabilities {}})]

      ;; Wait for client to initialize
      (client/wait-for-ready client 20000)
      (is (client/client-ready? client))

      (testing "can list available prompts"
        (let [future (prompts/list-prompts-impl client)
              result @future]
          (is (map? result))
          (is (contains? result :prompts))
          (is (vector? (:prompts result)))
          (is (seq (:prompts result))) ; Should have at least the built-in repl prompt

          ;; Check that we get the built-in repl prompt
          (let [prompt-names (set (map :name (:prompts result)))]
            (is (contains? prompt-names "repl"))

            ;; Find the repl prompt and check its structure
            (let [repl-prompt (first (filter #(= "repl" (:name %)) (:prompts result)))]
              (is (some? repl-prompt))
              (is (= "repl" (:name repl-prompt)))
              (is (string? (:description repl-prompt)))
              (is (vector? (:arguments repl-prompt)))
              (is (seq (:arguments repl-prompt))) ; Should have the code argument

              ;; Check the code argument structure
              (let [code-arg (first (filter #(= "code" (:name %)) (:arguments repl-prompt)))]
                (is (some? code-arg))
                (is (= "code" (:name code-arg)))
                (is (string? (:description code-arg))))))))

      (testing "available-prompts? returns true"
        (is (true? (prompts/available-prompts?-impl client)))))))

(deftest ^:integ prompts-get-integration-test
  (testing "MCP client can get specific prompts from real MCP server"
    (with-open [client (client/create-client
                         {:transport    {:type    :stdio
                                         :command "clojure"
                                         :args    ["-M:stdio-server"]}
                          :client-info  {:name    "prompts-get-integration-test"
                                         :version "1.0.0"}
                          :capabilities {}})]

      ;; Wait for client to initialize
      (client/wait-for-ready client 20000)
      (is (client/client-ready? client))

      (testing "can get repl prompt without arguments"
        (let [future (prompts/get-prompt-impl client "repl")
              result @future]
          (is (map? result))
          (is (not (:isError result false)))
          (is (string? (:description result)))
          (is (vector? (:messages result)))
          (is (seq (:messages result))) ; Should have messages

          ;; Check message structure
          (let [messages (:messages result)]
            (is (>= (count messages) 1)) ; Should have at least one message

            ;; Check first message is system message
            (let [first-msg (first messages)]
              (is (= "system" (:role first-msg)))
              (is (map? (:content first-msg)))
              (is (= "text" (:type (:content first-msg))))
              (is (string? (:text (:content first-msg))))
              (is (re-find #"REPL" (:text (:content first-msg)))))

            ;; Check there's a user message with template
            (let [user-msgs (filter #(= "user" (:role %)) messages)]
              (is (seq user-msgs))
              (let [user-msg (first user-msgs)]
                (is (= "user" (:role user-msg)))
                (is (string? (get-in user-msg [:content :text])))
                (is (re-find #"\{\{code\}\}" (get-in user-msg [:content :text]))))))))

      (testing "can get repl prompt with code argument"
        (let [future (prompts/get-prompt-impl client "repl" {"code" "(+ 1 2)"})
              result @future]
          (is (map? result))
          (is (not (:isError result false)))
          (is (string? (:description result)))
          (is (vector? (:messages result)))

          ;; Check that template substitution occurred
          (let [messages (:messages result)
                user-msgs (filter #(= "user" (:role %)) messages)]
            (is (seq user-msgs))
            (let [user-msg (first user-msgs)
                  text (get-in user-msg [:content :text])]
              (is (string? text))
              (is (re-find #"\(\+ 1 2\)" text))
              (is (not (re-find #"\{\{code\}\}" text))))))) ; Template should be replaced

      (testing "handles non-existent prompt gracefully"
        (let [future (prompts/get-prompt-impl client "non-existent-prompt")
              result @future]
          (is (map? result))
          (is (:isError result))
          (is (= "non-existent-prompt" (:prompt-name result)))
          (is (vector? (:content result)))
          (let [error-content (first (:content result))]
            (is (= "text" (:type error-content)))
            (is (re-find #"not found" (:text error-content)))))))))

(deftest ^:integ prompts-public-api-integration-test
  (testing "MCP client public API for prompts works with real server"
    (with-open [client (client/create-client
                         {:transport    {:type    :stdio
                                         :command "clojure"
                                         :args    ["-M:stdio-server"]}
                          :client-info  {:name    "prompts-api-integration-test"
                                         :version "1.0.0"}
                          :capabilities {}})]

      ;; Wait for client to initialize
      (client/wait-for-ready client 20000)
      (is (client/client-ready? client))

      (testing "public list-prompts function works"
        (let [future (client/list-prompts client)
              result @future]
          (is (map? result))
          (is (vector? (:prompts result)))
          (is (seq (:prompts result)))))

      (testing "public get-prompt function works without arguments"
        (let [future (client/get-prompt client "repl")
              result @future]
          (is (map? result))
          (is (not (:isError result false)))
          (is (vector? (:messages result)))))

      (testing "public get-prompt function works with arguments"
        (let [future (client/get-prompt client "repl" {"code" "(println \"hello\")"})
              result @future]
          (is (map? result))
          (is (not (:isError result false)))
          (is (vector? (:messages result)))
          ;; Check template substitution
          (let [user-msgs (filter #(= "user" (:role %)) (:messages result))]
            (is (seq user-msgs))
            (let [text (get-in (first user-msgs) [:content :text])]
              (is (re-find #"println" text))))))

      (testing "public available-prompts? function works"
        (is (true? (client/available-prompts? client)))))))
