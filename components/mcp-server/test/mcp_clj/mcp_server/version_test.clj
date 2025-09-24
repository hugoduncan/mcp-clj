(ns mcp-clj.mcp-server.version-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-server.version :as version]))

(deftest negotiate-version-test
  (testing "version negotiation according to MCP specification"

    (testing "client requests supported version"
      (let [result (version/negotiate-version "2025-06-18")]
        (is (= "2025-06-18" (:negotiated-version result)))
        (is (true? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2025-03-26" "2024-11-05"] (:supported-versions result)))))

    (testing "client requests older supported version"
      (let [result (version/negotiate-version "2024-11-05")]
        (is (= "2024-11-05" (:negotiated-version result)))
        (is (true? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2025-03-26" "2024-11-05"] (:supported-versions result)))))

    (testing "client requests unsupported old version - fallback to latest"
      (let [result (version/negotiate-version "2024-01-01")]
        (is (= "2025-06-18" (:negotiated-version result)))
        (is (false? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2025-03-26" "2024-11-05"] (:supported-versions result)))))

    (testing "client requests unsupported future version - fallback to latest"
      (let [result (version/negotiate-version "2026-01-01")]
        (is (= "2025-06-18" (:negotiated-version result)))
        (is (false? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2025-03-26" "2024-11-05"] (:supported-versions result)))))

    (testing "client requests malformed version - fallback to latest"
      (let [result (version/negotiate-version "0.2")]
        (is (= "2025-06-18" (:negotiated-version result)))
        (is (false? (:client-was-supported? result)))
        (is (= ["2025-06-18" "2025-03-26" "2024-11-05"] (:supported-versions result)))))))

(deftest supported?-test
  (testing "version support checking"
    (is (true? (version/supported? "2025-06-18")))
    (is (true? (version/supported? "2024-11-05")))
    (is (false? (version/supported? "2024-01-01")))
    (is (false? (version/supported? "0.2")))))

(deftest get-latest-version-test
  (testing "latest version retrieval"
    (is (= "2025-06-18" (version/get-latest-version)))))

(deftest version-specific-behavior-test
  (testing "version-specific behavior dispatch"

    (testing "supported version with capabilities"
      (let [context {:capabilities {:tools {:listChanged true}}}
            result (version/handle-version-specific-behavior
                    "2025-06-18" :capabilities context)]
        (is (= (:capabilities context) result))))

    (testing "older supported version with capabilities"
      (let [context {:capabilities {:tools {:listChanged true}}}
            result (version/handle-version-specific-behavior
                    "2024-11-05" :capabilities context)]
        (is (= {:tools {}} result))))

    (testing "unsupported feature throws exception"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unsupported feature for protocol version"
           (version/handle-version-specific-behavior
            "2025-06-18" :unknown-feature {})))))

(deftest capabilities-formatting-test
  (testing "capabilities formatting per version"

    (testing "2025-06-18 supports nested capabilities"
      (let [caps {:tools {:listChanged true} :resources {:subscribe false}}
            result (version/handle-version-specific-behavior
                    "2025-06-18" :capabilities {:capabilities caps})]
        (is (= caps result))))

    (testing "2025-03-26 flattens nested capabilities"
      (let [caps {:tools {:listChanged true} :resources {:subscribe false}}
            result (version/handle-version-specific-behavior
                    "2025-03-26" :capabilities {:capabilities caps})]
        (is (= {:tools {} :resources {}} result))))

    (testing "2024-11-05 flattens nested capabilities"
      (let [caps {:tools {:listChanged true} :resources {:subscribe false}}
            result (version/handle-version-specific-behavior
                    "2024-11-05" :capabilities {:capabilities caps})]
        (is (= {:tools {} :resources {}} result))))))

(deftest server-info-formatting-test
  (testing "server info formatting per version"

    (testing "2025-06-18 includes title field"
      (let [info {:name "test" :version "1.0" :title "Test Server"}
            result (version/handle-version-specific-behavior
                    "2025-06-18" :server-info {:server-info info})]
        (is (= info result))))

    (testing "2025-03-26 removes title field"
      (let [info {:name "test" :version "1.0" :title "Test Server"}
            result (version/handle-version-specific-behavior
                    "2025-03-26" :server-info {:server-info info})]
        (is (= {:name "test" :version "1.0"} result))))

    (testing "2024-11-05 removes title field"
      (let [info {:name "test" :version "1.0" :title "Test Server"}
            result (version/handle-version-specific-behavior
                    "2024-11-05" :server-info {:server-info info})]
        (is (= {:name "test" :version "1.0"} result))))))

(deftest content-type-filtering-test
  (testing "content type filtering per version"

    (testing "2025-06-18 supports all basic content types"
      (let [content [{:type "text"} {:type "image"} {:type "resource"} {:type "audio"}]
            result (version/handle-version-specific-behavior
                    "2025-06-18" :content-types {:content-types content})]
        (is (= 4 (count result)))
        (is (= #{:text :image :resource :audio} (set (map #(keyword (:type %)) result))))))

    (testing "2025-03-26 supports text, image, resource, and audio"
      (let [content [{:type "text"} {:type "image"} {:type "resource"} {:type "audio"}]
            result (version/handle-version-specific-behavior
                    "2025-03-26" :content-types {:content-types content})]
        (is (= 4 (count result)))
        (is (= #{:text :image :resource :audio} (set (map #(keyword (:type %)) result))))))

    (testing "2024-11-05 supports only text, image, and resource"
      (let [content [{:type "text"} {:type "image"} {:type "resource"} {:type "audio"}]
            result (version/handle-version-specific-behavior
                    "2024-11-05" :content-types {:content-types content})]
        (is (= 3 (count result)))
        (is (= #{:text :image :resource} (set (map #(keyword (:type %)) result))))))))

(deftest tool-response-formatting-test
  (testing "tool response formatting per version"

    (testing "2025-06-18 can include structured content"
      (let [base-response {:content [{:type "text" :text "result"}] :isError false}
            structured {:type "object" :properties {}}
            context (assoc base-response :structured-content structured)
            result (version/handle-version-specific-behavior
                    "2025-06-18" :tool-response context)]
        (is (= (:content base-response) (:content result)))
        (is (= (:isError base-response) (:isError result)))
        (is (= structured (:structuredContent result)))))

    (testing "2025-03-26 does not include structured content"
      (let [base-response {:content [{:type "text" :text "result"}] :isError false}
            structured {:type "object" :properties {}}
            context (assoc base-response :structured-content structured)
            result (version/handle-version-specific-behavior
                    "2025-03-26" :tool-response context)]
        (is (= (:content base-response) (:content result)))
        (is (= (:isError base-response) (:isError result)))
        (is (nil? (:structuredContent result)))))

    (testing "2024-11-05 does not include structured content"
      (let [base-response {:content [{:type "text" :text "result"}] :isError false}
            result (version/handle-version-specific-behavior
                    "2024-11-05" :tool-response base-response)]
        (is (= (:content base-response) (:content result)))
        (is (= (:isError base-response) (:isError result)))
        (is (nil? (:structuredContent result)))))))

(deftest header-validation-test
  (testing "header validation per version"

    (testing "2025-06-18 requires MCP-Protocol-Version header"
      (let [headers-with {"mcp-protocol-version" "2025-06-18"}
            headers-without {}
            result-with (version/handle-version-specific-behavior
                         "2025-06-18" :headers {:headers headers-with})
            result-without (version/handle-version-specific-behavior
                            "2025-06-18" :headers {:headers headers-without})]
        (is (:valid? result-with))
        (is (= "2025-06-18" (:protocol-version result-with)))
        (is (not (:valid? result-without)))
        (is (contains? result-without :error))))

    (testing "2025-03-26 does not require MCP-Protocol-Version header"
      (let [headers-with {"mcp-protocol-version" "2025-03-26"}
            headers-without {}
            result-with (version/handle-version-specific-behavior
                         "2025-03-26" :headers {:headers headers-with})
            result-without (version/handle-version-specific-behavior
                            "2025-03-26" :headers {:headers headers-without})]
        (is (:valid? result-with))
        (is (= "2025-03-26" (:protocol-version result-with)))
        (is (:valid? result-without))
        (is (nil? (:protocol-version result-without)))))

    (testing "2024-11-05 does not require MCP-Protocol-Version header"
      (let [headers-with {"mcp-protocol-version" "2024-11-05"}
            headers-without {}
            result-with (version/handle-version-specific-behavior
                         "2024-11-05" :headers {:headers headers-with})
            result-without (version/handle-version-specific-behavior
                            "2024-11-05" :headers {:headers headers-without})]
        (is (:valid? result-with))
        (is (= "2024-11-05" (:protocol-version result-with)))
        (is (:valid? result-without))
        (is (nil? (:protocol-version result-without)))))))

(deftest version-utility-functions-test
  (testing "version comparison utilities"

    (testing "version-gte?"
      (is (version/version-gte? "2025-06-18" "2024-11-05"))
      (is (version/version-gte? "2025-06-18" "2025-06-18"))
      (is (not (version/version-gte? "2024-11-05" "2025-06-18"))))

    (testing "supports-audio?"
      (is (version/supports-audio? "2025-06-18"))
      (is (version/supports-audio? "2025-03-26"))
      (is (not (version/supports-audio? "2024-11-05"))))

    (testing "supports-structured-content?"
      (is (version/supports-structured-content? "2025-06-18"))
      (is (not (version/supports-structured-content? "2025-03-26")))
      (is (not (version/supports-structured-content? "2024-11-05"))))

    (testing "requires-protocol-header?"
      (is (version/requires-protocol-header? "2025-06-18"))
      (is (not (version/requires-protocol-header? "2025-03-26")))
      (is (not (version/requires-protocol-header? "2024-11-05"))))

    (testing "supports-nested-capabilities?"
      (is (version/supports-nested-capabilities? "2025-06-18"))
      (is (not (version/supports-nested-capabilities? "2025-03-26")))
      (is (not (version/supports-nested-capabilities? "2024-11-05"))))))

(deftest validate-content-types-test
  (testing "content type validation utility"

    (testing "filters content based on version"
      (let [content [{:type "text"} {:type "audio"}]]
        (is (= 2 (count (version/validate-content-types "2025-06-18" content))))
        (is (= 2 (count (version/validate-content-types "2025-03-26" content))))
        (is (= 1 (count (version/validate-content-types "2024-11-05" content))))))

    (testing "returns nil for empty content"
      (is (nil? (version/validate-content-types "2025-06-18" [])))
      (is (nil? (version/validate-content-types "2025-06-18" nil))))))

(deftest validate-headers-test
  (testing "header validation utility"

    (testing "validates headers based on version"
      (let [headers {"mcp-protocol-version" "2025-06-18"}]
        (is (:valid? (version/validate-headers "2025-06-18" headers)))
        (is (:valid? (version/validate-headers "2025-03-26" headers)))
        (is (:valid? (version/validate-headers "2024-11-05" headers)))))

    (testing "validates missing headers based on version"
      (let [headers {}]
        (is (not (:valid? (version/validate-headers "2025-06-18" headers))))
        (is (:valid? (version/validate-headers "2025-03-26" headers)))
        (is (:valid? (version/validate-headers "2024-11-05" headers))))))))
