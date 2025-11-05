(ns mcp-clj.mcp-server-test
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [hato.client :as hato]
    [mcp-clj.client-transport.factory :as client-transport-factory]
    [mcp-clj.in-memory-transport.shared :as shared]
    [mcp-clj.mcp-client.core :as client]
    [mcp-clj.mcp-client.transport :as client-transport]
    [mcp-clj.mcp-server.core :as mcp]
    [mcp-clj.server-transport.factory :as server-transport-factory])
  (:import
    (java.io
      BufferedReader)
    (java.util.concurrent
      BlockingQueue
      LinkedBlockingQueue
      TimeUnit)))

(defn ensure-in-memory-transport-registered!
  "Ensure in-memory transport is registered in both client and server factories"
  []
  (client-transport-factory/register-transport!
    :in-memory
    (fn [options]
      (require 'mcp-clj.in-memory-transport.client)
      (let [create-fn (ns-resolve
                        'mcp-clj.in-memory-transport.client
                        'create-transport)]
        (create-fn options))))
  (server-transport-factory/register-transport!
    :in-memory
    (fn [options handlers]
      (require 'mcp-clj.in-memory-transport.server)
      (let [create-server (ns-resolve
                            'mcp-clj.in-memory-transport.server
                            'create-in-memory-server)]
        (create-server options handlers)))))

(ensure-in-memory-transport-registered!)

(def test-tool
  "Test tool for server testing"
  {:name "test-tool"
   :description "A test tool for server testing"
   :inputSchema {:type "object"
                 :properties {"value" {:type "string"}}
                 :required ["value"]}
   :implementation (fn [_server {:keys [value]}]
                     {:content [{:type "text"
                                 :text (str "test-response:" value)}]
                      :isError false})})

(def error-test-tool
  "Test tool that always returns an error"
  {:name "error-test-tool"
   :description "A test tool that always returns an error"
   :inputSchema {:type "object"
                 :properties {"value" {:type "string"}}
                 :required ["value"]}
   :implementation (fn [_server _]
                     {:content [{:type "text"
                                 :text "test-error"}]
                      :isError true})})

(def test-prompt
  {:name "test-prompt"
   :description "A test prompt for server testing"
   :messages [{:role "system"
               :content {:type "text"
                         :text "Hello"}}
              {:role "user"
               :content {:type "text"
                         :text "Please say {{reply}}"}}]
   :arguments [{:name "reply"
                :description "something"
                :required true}]})

#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^:private ^:dynamic *server*)

(defn with-server
  "Test fixture for server lifecycle"
  [f]
  (let [server (mcp/create-server
                 {:transport {:type :sse :port 0}
                  :tools {"test-tool" test-tool
                          "error-test-tool" error-test-tool}
                  :prompts {"test-prompt" test-prompt}})]
    (try
      (binding [*server* server]
        (f))
      (finally
        ((:stop server))))))

(use-fixtures :each with-server)

(defn send-request
  "Send JSON-RPC request"
  [url request]
  (prn :send-request url request)
  (hato/post url
             {:headers {"Content-Type" "application/json"}
              :body (json/generate-string request)}))

(defn- poll
  [^BlockingQueue queue]
  (.poll queue 2 TimeUnit/SECONDS))

(defn- offer
  [^BlockingQueue queue value]
  (.offer queue value))

(defn wait-for-sse-events
  [^BufferedReader reader queue done]
  (loop [resp {}]
    (when-not @done
      (when-let [line (try
                        (.readLine reader)
                        (catch java.io.IOException _))]
        (prn :read-line line)
        (cond
          (or (empty? line)
              (str/starts-with? line ":"))
          (do
            (prn :enqueue resp)
            (offer queue resp)
            (recur {}))
          :else
          (when-let [[k v] (str/split line #":" 2)]
            (let [v (str/trim v)]
              (recur
                (assoc resp (keyword k)
                       (if (= "message" (:event resp))
                         (json/parse-string v true)
                         v))))))))))

(defn step-plan
  [state]
  (let [{:keys [action data fn msg apply-fn]} (first (:plan state))]
    (condp = action
      :receive (let [msg (poll (:queue state))]
                 (prn :receive msg)
                 (if msg
                   (do
                     (is msg)
                     (is (= data (select-keys msg (keys data))))
                     (prn :qpply-fn apply-fn)
                     (cond-> state
                       apply-fn (apply-fn msg)
                       true (update :plan rest)))
                   (assoc state :failed {:missing-response data})))
      :send (do
              (prn :send msg)
              (let [url (str (:url state) (:uri state))
                    resp (send-request url (assoc msg :id (:id state)))]
                (prn :resp resp)
                (if (< (:status resp) 300)
                  (-> state
                      (update :plan rest)
                      (update :id inc))
                  (assoc state :failed resp))))
      :notify (do
                (prn :notify msg)
                (let [url (str (:url state) (:uri state))
                      resp (send-request url msg)]
                  (prn :resp resp)
                  (if (< (:status resp) 300)
                    (-> state
                        (update :plan rest))
                    (assoc state :failed resp))))
      :clj (do
             (fn) ; Execute the provided function
             (update state :plan rest))

      (assoc state :failed {:unknown-action action}))))

(defn run-plan
  [state]
  (loop [state (assoc state :id 0)]
    (let [state' (step-plan state)]
      (prn :run-plan :state' state')
      (cond
        (:failed state')
        [state' :failed]
        (not (seq (:plan state')))
        [state' :passed]
        (seq (:plan state'))
        (recur state')))))

(defn- update-state-apply-key
  [state-key data-key]
  (fn apply-key
    [state data]
    (prn :apply-key :data data)
    (prn state-key (get data data-key))
    (assoc state state-key (get data data-key))))

(defn- json-request
  [method params & [id]]
  (cond->
    {:jsonrpc "2.0"
     :method method
     :params params}
    id (assoc :id id)))

(defn- json-result
  [result & [options id]]
  (cond-> (merge {:jsonrpc "2.0"
                  :result result}
                 options)
    id (assoc :id id)))

(defn- initialisation-plan
  []
  [{:action :receive
    :data {:event "endpoint"}
    :apply-fn (update-state-apply-key :uri :data)}
   {:action :send
    :msg (json-request
           "initialize"
           {:protocolVersion "2024-11-05"
            :capabilities {:roots
                           {:listChanged true}
                           :tools
                           {:listChanged true}}
            :clientInfo {:name "mcp"
                         :version "0.1.0"}})}
   {:action :receive
    :data {:event "message"}}
   {:action :notify
    :msg (json-request
           "notifications/initialized"
           {})}])

(defn port
  []
  (:port @(:json-rpc-server *server*)))

(deftest ^:integ lifecycle-test
  (testing "Server lifecycle"
    (let [port (port)
          url (format "http://localhost:%d" port)
          queue (LinkedBlockingQueue.)
          state {:url url
                 :queue queue
                 :failed false}
          response (hato/get (str url "/sse")
                             {:headers {"Accept" "text/event-stream"}
                              :as :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f (future
                  (try
                    (wait-for-sse-events reader queue done)
                    (catch Throwable e
                      (prn :error e)
                      (flush))))]
          (testing "initialisation"
            (let [state (assoc state :plan (initialisation-plan))
                  [_state' result] (run-plan state)]
              (is (= :passed result))))
          (future-cancel f))))))

(deftest ^:integ tools-test
  (testing "A server with tools"
    (let [port (port)
          url (format "http://localhost:%d" port)
          queue (LinkedBlockingQueue.)
          state {:url url
                 :queue queue
                 :failed false}
          response (hato/get (str url "/sse")
                             {:headers {"Accept" "text/event-stream"}
                              :as :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f (future
                  (try
                    (wait-for-sse-events reader queue done)
                    (catch Throwable e
                      (prn :error e)
                      (flush))))]
          (testing "initialisation"
            (let [state (assoc state :plan (initialisation-plan))
                  [state' result] (run-plan state)]
              (is (= :passed result))
              (testing "tool interactions"
                (let [state
                      (assoc
                        state'
                        :plan
                        [{:action :send
                          :msg (json-request
                                 "tools/list"
                                 {}
                                 0)}
                         {:action :receive
                          :data
                          {:event "message"
                           :data
                           (json-result
                             {:tools
                              [{:name "test-tool",
                                :description "A test tool for server testing",
                                :inputSchema
                                {:type "object",
                                 :properties {:value {:type "string"}},
                                 :required ["value"]}}
                               {:name "error-test-tool",
                                :description "A test tool that always returns an error",
                                :inputSchema
                                {:type "object",
                                 :properties {:value {:type "string"}},
                                 :required ["value"]}}]}
                             {}
                             0)}}])
                      [state' result] (run-plan state)
                      _ (testing "tools/list"
                          (is (= :passed result) (pr-str state))
                          (is (not (:failed state'))))
                      state (assoc
                              state'
                              :plan
                              [{:action :send
                                :msg (json-request
                                       "tools/call"
                                       {:name "test-tool"
                                        :arguments
                                        {:value "me"}}
                                       0)}
                               {:action :receive
                                :data
                                {:event "message"
                                 :data
                                 (json-result
                                   {:content
                                    [{:type "text"
                                      :text "test-response:me"}]
                                    :isError false}
                                   nil
                                   0)}}])
                      [state' result] (testing "makes a successful tools/call"
                                        (run-plan state))
                      _ (testing "makes a successful tools/call"
                          (is (= :passed result))
                          (is (not (:failed state'))))
                      state (assoc
                              state'
                              :plan
                              [{:action :send
                                :msg (json-request
                                       "tools/call"
                                       {:name "error-test-tool"
                                        :arguments
                                        {:value "me"}}
                                       0)}
                               {:action :receive
                                :data
                                {:event "message"
                                 :data
                                 (json-result
                                   {:content
                                    [{:type "text"
                                      :text "test-error"}]
                                    :isError true}
                                   nil
                                   0)}}])
                      [state' result] (testing "tools/call with an error"
                                        (run-plan state))
                      _ (testing "tools/call with an error"
                          (is (= :passed result))
                          (is (not (:failed state'))))
                      state (assoc
                              state'
                              :plan
                              [{:action :send
                                :msg (json-request
                                       "tools/call"
                                       {:name "unkown"
                                        :arguments
                                        {:code "(/ 1 0)"}}
                                       0)}
                               {:action :receive
                                :data
                                {:event "message"
                                 :data
                                 {:jsonrpc "2.0"
                                  :id 0
                                  :error {:code -32602
                                          :message "Unknown tool: unkown"
                                          :data {:data {:name "unkown"}}}}}}])
                      [state' result] (run-plan state)
                      _ (testing "tools/call with unknown tool"
                          (is (= :passed result))
                          (is (not (:failed state'))))]))))
          (future-cancel f))))))

(deftest ^:integ tool-change-notifications-test
  (testing "tool change notifications"
    (let [port (port)
          url (format "http://localhost:%d" port)
          queue (LinkedBlockingQueue.)
          state {:url url
                 :queue queue
                 :failed false}
          test-tool {:name "dynamic-tool"
                     :description "A test tool"
                     :inputSchema {:type "object"
                                   :properties {"value" {:type "string"}}
                                   :required ["value"]}
                     :implementation (fn [_server {:keys [value]}]
                                       {:content [{:type "text"
                                                   :text (str "Got: " value)}]
                                        :isError false})}
          response (hato/get (str url "/sse")
                             {:headers {"Accept" "text/event-stream"}
                              :as :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f (future
                  (try
                    (wait-for-sse-events reader queue done)
                    (catch Throwable e
                      (prn :error e)
                      (flush))))]
          (testing "initialisation"
            (let [state (assoc state :plan (initialisation-plan))
                  [_state' result] (run-plan state)]
              (is (= :passed result))

              ;; Add a tool dynamically - this should trigger notifications
              (mcp/add-tool! *server* test-tool)

              ;; Remove the tool - this should also trigger notifications
              (mcp/remove-tool! *server* "dynamic-tool")))
          (future-cancel f))))))

(deftest tool-management-test
  (testing "tool management"
    (let [test-tool {:name "test-tool"
                     :description "A test tool"
                     :inputSchema {:type "object"
                                   :properties {"value" {:type "string"}}
                                   :required ["value"]}
                     :implementation (fn [_server {:keys [value]}]
                                       {:content [{:type "text"
                                                   :text (str "Got: " value)}]
                                        :isError false})}
          server (mcp/create-server {:transport {:type :sse :port 0}})]
      (try
        ;; Test adding a tool
        (mcp/add-tool! server test-tool)
        (is (= test-tool (get @(:tool-registry server) "test-tool")))

        ;; Test updating a tool
        (let [updated-tool (assoc test-tool
                                  :description "Updated description")]
          (mcp/add-tool! server updated-tool)
          (is (= updated-tool (get @(:tool-registry server) "test-tool"))))

        ;; Test removing a tool
        (mcp/remove-tool! server "test-tool")
        (is (nil? (get @(:tool-registry server) "test-tool")))

        ;; Test adding invalid tool
        (is (thrown? clojure.lang.ExceptionInfo
              (mcp/add-tool! server (dissoc test-tool :implementation))))

        (finally
          ((:stop server)))))))

(deftest nil-params-handling-test
  ;; Verifies the fix for NullPointerException when JSON-RPC request has nil params.
  ;;
  ;; Background: The MCP server's request-handler (core.clj:385) previously tried to attach
  ;; session-id metadata to params using (with-meta params {:session-id ...}).
  ;; When params is nil, this threw NullPointerException because nil cannot have metadata.
  ;;
  ;; PR #37 fix changed line 385 to:
  ;;   (with-meta (or params {}) {:session-id session-id})
  ;;
  ;; This test verifies the fix works and nil params are handled gracefully.
  (testing "request-handler with nil params"
    (ensure-in-memory-transport-registered!)
    (let [shared-transport (shared/create-shared-transport)
          test-server (mcp/create-server
                        {:transport {:type :in-memory :shared shared-transport}})
          test-client (client/create-client
                        {:transport {:type :in-memory :shared shared-transport}
                         :client-info {:name "test-client" :version "1.0.0"}
                         :capabilities {}})]
      (try
        (client/wait-for-ready test-client 5000)

        (testing "handles nil params gracefully after fix"
          (let [transport (:transport test-client)
                result @(client-transport/send-request!
                          transport "ping" nil 5000)]
            (is (some? result) "Should return a result without error")
            (is (= {} result) "Ping with nil params should return empty map")))

        (finally
          (client/close! test-client)
          ((:stop test-server)))))))

(deftest ^:integ custom-tools-test
  (testing "server with custom tools"
    (let [custom-tool {:name "echo"
                       :description "Echo the input"
                       :inputSchema {:type "object"
                                     :properties {"text" {:type "string"}}
                                     :required ["text"]}
                       :implementation (fn [_server {:keys [text]}]
                                         {:content [{:type "text"
                                                     :text text}]
                                          :isError false})}
          port (port)
          url (format "http://localhost:%d" port)
          queue (LinkedBlockingQueue.)
          state {:url url
                 :queue queue
                 :failed false}]
      ;; Add custom tool to server
      (mcp/add-tool! *server* custom-tool)

      (let [response (hato/get (str url "/sse")
                               {:headers {"Accept" "text/event-stream"}
                                :as :stream})]
        (with-open [reader (io/reader (:body response))]
          (let [done (volatile! nil)
                f (future
                    (try
                      (wait-for-sse-events reader queue done)
                      (catch Throwable e
                        (prn :error e)
                        (flush))))]
            (testing "using custom tool"
              (let [state (assoc state :plan (initialisation-plan))
                    [state' result] (run-plan state)]
                (is (= :passed result))
                (testing "tool interactions"
                  (let [state (assoc
                                state'
                                :plan
                                [{:action :send
                                  :msg (json-request
                                         "tools/call"
                                         {:name "echo"
                                          :arguments {:text "hello"}}
                                         0)}
                                 {:action :receive
                                  :data
                                  {:event "message"
                                   :data (json-result
                                           {:content
                                            [{:type "text"
                                              :text "hello"}]
                                            :isError false}
                                           nil
                                           0)}}])
                        [_state' result] (run-plan state)]
                    (is (= :passed result))))))
            (future-cancel f)))))))

(deftest ^:integ resource-test
  (testing "server lifecycle with resources"
    (let [port (port)
          url (format "http://localhost:%d" port)
          queue (LinkedBlockingQueue.)
          state {:url url
                 :queue queue
                 :failed false}
          response (hato/get (str url "/sse")
                             {:headers {"Accept" "text/event-stream"}
                              :as :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f (future
                  (try
                    (wait-for-sse-events reader queue done)
                    (catch Throwable e
                      (prn :error e)
                      (flush))))]
          (testing "initialisation"
            (let [state (assoc state :plan (initialisation-plan))
                  [state' result] (run-plan state)]
              (is (= :passed result))
              (testing "resource interactions"
                (let [state
                      (assoc
                        state'
                        :plan
                        [{:action :send
                          :msg (json-request
                                 "resources/list"
                                 {}
                                 0)}
                         {:action :receive
                          :data
                          {:event "message"
                           :data
                           (json-result
                             {:resources []}
                             {}
                             0)}}])
                      [_state' result] (run-plan state)
                      _ (testing "resources/list"
                          (is (= :passed result)))]))))
          (future-cancel f))))))

(deftest ^:integ prompt-test
  (testing "server lifecycle with SSE"
    (let [port (port)
          url (format "http://localhost:%d" port)
          queue (LinkedBlockingQueue.)
          state {:url url
                 :queue queue
                 :failed false}
          response (hato/get (str url "/sse")
                             {:headers {"Accept" "text/event-stream"}
                              :as :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f (future
                  (try
                    (wait-for-sse-events reader queue done)
                    (catch Throwable e
                      (prn :error e)
                      (flush))))]
          (testing "initialisation"
            (let [state (assoc state :plan (initialisation-plan))
                  [state' result] (run-plan state)]
              (is (= :passed result))
              (testing "prompt interactions"
                (let [state
                      (assoc
                        state'
                        :plan
                        [{:action :send
                          :msg (json-request
                                 "prompts/list"
                                 {}
                                 0)}
                         {:action :receive
                          :data
                          {:event "message"
                           :data
                           (json-result
                             {:prompts
                              [{:name "test-prompt",
                                :description "A test prompt for server testing",
                                :arguments
                                [{:name "reply"
                                  :description "something"
                                  :required true}]}]}
                             nil
                             0)}}])
                      [_state' result] (run-plan state)
                      _ (testing "prompts/list"
                          (is (= :passed result)))]))))
          (future-cancel f))))))

#_(deftest version-negotiation-test
    (testing "MCP protocol version negotiation"
      (let [port (port)
            url (format "http://localhost:%d" port)
            queue (LinkedBlockingQueue.)
            state {:url url
                   :queue queue
                   :failed false}
            response (hato/get (str url "/sse")
                               {:headers {"Accept" "text/event-stream"}
                                :as :stream})]
        (with-open [reader (io/reader (:body response))]
          (let [done (volatile! nil)
                f (future
                    (try
                      (wait-for-sse-events reader queue done)
                      (catch Throwable e
                        (prn :error e)
                        (flush))))]

            (testing "supported version 2024-11-05 negotiation succeeds"
              (let [plan
                    [{:action :receive
                      :data {:event "endpoint"}
                      :apply-fn (update-state-apply-key :uri :data)}
                     {:action :send
                      :msg (json-request
                            "initialize"
                            {:protocolVersion "2024-11-05"
                             :capabilities {:tools {:listChanged true}}
                             :clientInfo {:name "test-client" :version "1.0.0"}}
                            1)}
                     {:action :receive
                      :data {:event "message"
                             :data
                             (json-result
                              {:serverInfo {:name "mcp-clj"
                                            :version "0.1.0"}
                               :protocolVersion "2024-11-05"
                               :capabilities {:tools {:listChanged true}
                                              :resources {:listChanged false
                                                          :subscribe false}
                                              :prompts {:listChanged true}}
                               :instructions "mcp-clj is used to interact with a clojure REPL."}
                              nil
                              1)}}]
                    state (assoc state :plan plan)
                    [state' result] (run-plan state)]
                (is (= :passed result))
                (is (not (:failed state')))))

            (testing "latest version 2025-06-18 negotiation succeeds"
              (let [plan
                    [{:action :send
                      :msg (json-request
                            "initialize"
                            {:protocolVersion "2025-06-18"
                             :capabilities {:tools {:listChanged true}}
                             :clientInfo {:name "test-client" :version "1.0.0"}}
                            2)}
                     {:action :receive
                      :data {:event "message"
                             :data
                             (json-result
                              {:serverInfo {:name "mcp-clj"
                                            :title "MCP Clojure Server"
                                            :version "0.1.0"}
                               :protocolVersion "2025-06-18"
                               :capabilities {:tools {:listChanged true}
                                              :resources {:listChanged false
                                                          :subscribe false}
                                              :prompts {:listChanged true}}
                               :instructions "mcp-clj is used to interact with a clojure REPL."}
                              nil
                              2)}}]
                    state (assoc state :plan plan)
                    [state' result] (run-plan state)]
                (is (= :passed result))
                (is (not (:failed state')))))

            (testing "unsupported version falls back with warning"
              (let [plan
                    [{:action :send
                      :msg (json-request
                            "initialize"
                            {:protocolVersion "0.2"
                             :capabilities {:tools {:listChanged true}}
                             :clientInfo {:name "test-client" :version "1.0.0"}}
                            3)}
                     {:action :receive
                      :data {:event "message"
                             :data
                             (json-result
                              {:serverInfo {:name "mcp-clj"
                                            :title "MCP Clojure Server"
                                            :version "0.1.0"}
                               :protocolVersion "2025-06-18"
                               :capabilities {:tools {:listChanged true}
                                              :resources {:listChanged false
                                                          :subscribe false}
                                              :prompts {:listChanged true}}
                               :instructions "mcp-clj is used to interact with a clojure REPL."
                               :warnings ["Client version 0.2 not supported. Using 2025-06-18. Supported versions: [\"2025-06-18\" \"2024-11-05\"]"]}
                              nil
                              3)}}]
                    state (assoc state :plan plan)
                    [state' result] (run-plan state)]
                (is (= :passed result))
                (is (not (:failed state')))))

            (future-cancel f))))))

#_(deftest error-handling-test
    (testing "error handling - OUTDATED: This test expects old rigid version checking behavior"
      (let [port (port)
            url (format "http://localhost:%d" port)]

        (testing "invalid protocol version - NOW HANDLED BY NEGOTIATION"
          ;; This test is outdated - with proper MCP negotiation,
          ;; unsupported versions should fallback, not error
          (let [response (send-request
                          url
                          (make-request "initialize"
                                        (assoc valid-client-info
                                               :protocolVersion "0.2")
                                        1))]
            ;; Old expectation was error -32001, now should succeed with fallback
            (is (contains? response :result))))

        (testing "uninitialized ping"
          (let [response (send-request url (make-request "ping" {} 1))]
            (is (= -32002 (get-in response [:error :code]))))))))
