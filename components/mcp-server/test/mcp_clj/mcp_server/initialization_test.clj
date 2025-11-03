(ns mcp-clj.mcp-server.initialization-test
  "Tests for MCP server initialization and capability advertisement"
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.mcp-server.core :as mcp-server]))

(deftest negotiate-initialization-test
  ;; Test that the server properly negotiates initialization and advertises capabilities
  (testing "negotiate-initialization"
    (testing "advertises resources.subscribe capability"
      (let [server-capabilities {}
            client-params {:protocolVersion "2024-11-05"
                           :capabilities {:roots {:listChanged true}}
                           :clientInfo {:name "test-client"
                                        :version "1.0.0"}}
            result (#'mcp-server/negotiate-initialization server-capabilities nil client-params)
            response (:response result)
            capabilities (get-in response [:capabilities :resources])]
        (is (true? (:subscribe capabilities))
            "Server should advertise resources.subscribe: true")
        (is (true? (:listChanged capabilities))
            "Server should advertise resources.listChanged: true")))

    (testing "advertises resources.subscribe across all protocol versions"
      (doseq [protocol-version ["2024-11-05" "2025-03-26" "2025-06-18"]]
        (testing (str "protocol version " protocol-version)
          (let [server-capabilities {}
                client-params {:protocolVersion protocol-version
                               :capabilities {:roots {:listChanged true}}
                               :clientInfo {:name "test-client"
                                            :version "1.0.0"}}
                result (#'mcp-server/negotiate-initialization server-capabilities nil client-params)
                response (:response result)
                capabilities (get-in response [:capabilities :resources])]
            (is (true? (:subscribe capabilities))
                (str "Server should advertise resources.subscribe: true for " protocol-version))
            (is (true? (:listChanged capabilities))
                (str "Server should advertise resources.listChanged: true for " protocol-version))))))

    (testing "advertises other capabilities"
      (let [server-capabilities {}
            client-params {:protocolVersion "2024-11-05"
                           :capabilities {}
                           :clientInfo {:name "test-client"
                                        :version "1.0.0"}}
            result (#'mcp-server/negotiate-initialization server-capabilities nil client-params)
            response (:response result)
            capabilities (:capabilities response)]
        (is (true? (get-in capabilities [:tools :listChanged]))
            "Server should advertise tools.listChanged")
        (is (true? (get-in capabilities [:prompts :listChanged]))
            "Server should advertise prompts.listChanged")))

    (testing "conditionally includes logging capability"
      (let [server-capabilities {:logging {}}
            client-params {:protocolVersion "2024-11-05"
                           :capabilities {}
                           :clientInfo {:name "test-client"
                                        :version "1.0.0"}}
            result (#'mcp-server/negotiate-initialization server-capabilities nil client-params)
            response (:response result)
            capabilities (:capabilities response)]
        (is (contains? capabilities :logging)
            "Server should include logging capability when configured")))))
