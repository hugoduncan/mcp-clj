(ns mcp-clj.mcp-server-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hato.client :as hato]
   [mcp-clj.mcp-server.core :as mcp])
  (:import
   [java.util.concurrent BlockingQueue
    LinkedBlockingQueue
    TimeUnit]))

(def test-tool
  "Test tool for server testing"
  {:name "test-tool"
   :description "A test tool for server testing"
   :inputSchema {:type "object"
                 :properties {"value" {:type "string"}}
                 :required ["value"]}
   :implementation (fn [{:keys [value]}]
                     {:content [{:type "text"
                                 :text (str "test-response:" value)}]})})

(def error-test-tool
  "Test tool that always returns an error"
  {:name "error-test-tool"
   :description "A test tool that always returns an error"
   :inputSchema {:type "object"
                 :properties {"value" {:type "string"}}
                 :required ["value"]}
   :implementation (fn [_]
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
                {:port       0
                 :threads    2
                 :queue-size 10
                 :tools      {"test-tool"       test-tool
                              "error-test-tool" error-test-tool}
                 :prompts    {"test-prompt" test-prompt}})]
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
  (-> (hato/post url
                 {:headers {"Content-Type" "application/json"}
                  :body (json/write-str request)})
      #_:body
      #_(json/read-str :key-fn keyword)))

(defn make-request
  "Create JSON-RPC request"
  [method params id]
  {:jsonrpc "2.0"
   :method method
   :params params
   :id id})

(defn- poll [^BlockingQueue queue]
  (.poll queue 2 TimeUnit/SECONDS))

(defn- offer [^BlockingQueue queue value]
  (.offer queue value))

(defn wait-for-sse-events
  [reader queue done]
  (loop [resp {}]
    (when-not @done
      (when-let [line (try
                        (.readLine reader)
                        (catch java.io.IOException _))]
        (prn :read-line line)
        (cond
          (or (empty? line)
              (.startsWith line ":"))
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
                        (json/read-str v :key-fn keyword)
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

(defn run-plan [state]
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
  (fn apply-key [state data]
    (prn :apply-key :data data)
    (prn state-key (get data data-key))
    (assoc state state-key (get data data-key))))

(defn- update-state-apply-data
  [state-key]
  (fn apply-data [state data]
    (prn :apply-data :state state :data data)
    (prn state-key data)
    (assoc state state-key data)))

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

(defn- initialisation-plan []
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

(defn port []
  (:port @(:json-rpc-server *server*)))

(deftest lifecycle-test
  (testing "server lifecycle with SSE"
    (let [port     (port)
          url      (format "http://localhost:%d" port)
          queue    (LinkedBlockingQueue.)
          state    {:url    url
                    :queue  queue
                    :failed false}
          response (hato/post (str url "/sse")
                              {:headers {"Accept" "text/event-stream"}
                               :as      :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f    (future
                     (try
                       (wait-for-sse-events reader queue done)
                       (catch Throwable e
                         (prn :error e)
                         (flush))))]
          (testing "initialisation"
            (let [state           (assoc state :plan (initialisation-plan))
                  [state' result] (run-plan state)]
              (is (= :passed result))
              (is (not (:failed state')))))
          (future-cancel f))))))

(deftest tools-test
  (testing "A server with tools"
    (let [port     (port)
          url      (format "http://localhost:%d" port)
          queue    (LinkedBlockingQueue.)
          state    {:url    url
                    :queue  queue
                    :failed false}
          response (hato/post (str url "/sse")
                              {:headers {"Accept" "text/event-stream"}
                               :as      :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f    (future
                     (try
                       (wait-for-sse-events reader queue done)
                       (catch Throwable e
                         (prn :error e)
                         (flush))))]
          (testing "initialisation"
            (let [state           (assoc state :plan (initialisation-plan))
                  [state' result] (run-plan state)]
              (is (= :passed result))
              (testing "tool interactions"
                (let [state
                      (assoc
                       state'
                       :plan
                       [{:action :send
                         :msg    (json-request
                                  "tools/list"
                                  {}
                                  0)}
                        {:action :receive
                         :data
                         {:event "message"
                          :data
                          (json-result
                           {:tools
                            [{:name        "test-tool",
                              :description "A test tool for server testing",
                              :inputSchema
                              {:type       "object",
                               :properties {:value {:type "string"}},
                               :required   ["value"]}}
                             {:name        "error-test-tool",
                              :description "A test tool that always returns an error",
                              :inputSchema
                              {:type       "object",
                               :properties {:value {:type "string"}},
                               :required   ["value"]}}]}
                           {}
                           0)}}])
                      [state' result] (run-plan state)
                      _               (testing "tools/list"
                                        (is (= :passed result) (pr-str state))
                                        (is (not (:failed state'))))
                      state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request
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
                                              :text "test-response:me"}]}
                                           nil
                                           0)}}])
                      [state' result] (testing "makes a successful tools/call"
                                        (run-plan state))
                      _               (testing "makes a successful tools/call"
                                        (is (= :passed result))
                                        (is (not (:failed state'))))
                      state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request
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
                      _               (testing "tools/call with an error"
                                        (is (= :passed result))
                                        (is (not (:failed state'))))
                      state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request
                                                  "tools/call"
                                                  {:name "unkown"
                                                   :arguments
                                                   {:code "(/ 1 0)"}}
                                                  0)}
                                        {:action :receive
                                         :data
                                         {:event "message"
                                          :data
                                          (json-result
                                           {:content
                                            [{:type "text"
                                              :text "Tool not found: unkown"}]
                                            :isError true}
                                           nil
                                           0)}}])
                      [state' result] (run-plan state)
                      _               (testing "tools/call with unknown tool"
                                        (is (= :passed result))
                                        (is (not (:failed state'))))]))))
          (future-cancel f))))))

(deftest tool-change-notifications-test
  (testing "tool change notifications"
    (let [port      (port)
          url       (format "http://localhost:%d" port)
          queue     (LinkedBlockingQueue.)
          state     {:url    url
                     :queue  queue
                     :failed false}
          test-tool {:name           "test-tool"
                     :description    "A test tool"
                     :inputSchema    {:type       "object"
                                      :properties {"value" {:type "string"}}
                                      :required   ["value"]}
                     :implementation (fn [{:keys [value]}]
                                       {:content [{:type "text"
                                                   :text (str "Got: " value)}]})}
          response  (hato/post (str url "/sse")
                               {:headers {"Accept" "text/event-stream"}
                                :as      :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f    (future
                     (try
                       (wait-for-sse-events reader queue done)
                       (catch Throwable e
                         (prn :error e)
                         (flush))))]
          (testing "initialisation and tool changes"
            (let [state           (assoc
                                   state
                                   :plan
                                   (into
                                    (initialisation-plan)
                                    [;; Add tool and check for notification
                                     {:action :clj
                                      :fn     #(mcp/add-tool! *server* test-tool)}
                                     {:action :receive
                                      :data   {:event "message"
                                               :data  {:jsonrpc "2.0"
                                                       :method  "notifications/tools/list_changed"}}}
                                     ;; Remove tool and check for notification
                                     {:action :clj
                                      :fn     #(mcp/remove-tool! *server* "test-tool")}
                                     {:action :receive
                                      :data   {:event "message"
                                               :data  {:jsonrpc "2.0"
                                                       :method  "notifications/tools/list_changed"}}}]))
                  [state' result] (run-plan state)]
              (is (= :passed result))
              (is (not (:failed state')))))
          (future-cancel f))))))

(deftest tool-management-test
  (testing "tool management"
    (let [test-tool {:name "test-tool"
                     :description "A test tool"
                     :inputSchema {:type "object"
                                   :properties {"value" {:type "string"}}
                                   :required ["value"]}
                     :implementation (fn [{:keys [value]}]
                                       {:content [{:type "text"
                                                   :text (str "Got: " value)}]})}
          server (mcp/create-server {:port 0 :threads 2})]
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

(deftest custom-tools-test
  (testing "server with custom tools"
    (let [custom-tool {:name           "echo"
                       :description    "Echo the input"
                       :inputSchema    {:type       "object"
                                        :properties {"text" {:type "string"}}
                                        :required   ["text"]}
                       :implementation (fn [{:keys [text]}]
                                         {:content [{:type "text"
                                                     :text text}]})}
          port        (port)
          url         (format "http://localhost:%d" port)
          queue       (LinkedBlockingQueue.)
          state       {:url    url
                       :queue  queue
                       :failed false}]
      ;; Add custom tool to server
      (mcp/add-tool! *server* custom-tool)

      (let [response (hato/post (str url "/sse")
                                {:headers {"Accept" "text/event-stream"}
                                 :as      :stream})]
        (with-open [reader (io/reader (:body response))]
          (let [done (volatile! nil)
                f    (future
                       (try
                         (wait-for-sse-events reader queue done)
                         (catch Throwable e
                           (prn :error e)
                           (flush))))]
            (testing "using custom tool"
              (let [state           (assoc state :plan (initialisation-plan))
                    [state' result] (run-plan state)]
                (is (= :passed result))
                (testing "tool interactions"
                  (let [state           (assoc
                                         state'
                                         :plan
                                         [{:action :send
                                           :msg    (json-request
                                                    "tools/call"
                                                    {:name      "echo"
                                                     :arguments {:text "hello"}}
                                                    0)}
                                          {:action :receive
                                           :data
                                           {:event "message"
                                            :data  (json-result
                                                    {:content
                                                     [{:type "text"
                                                       :text "hello"}]}
                                                    nil
                                                    0)}}])
                        [state' result] (run-plan state)]
                    (is (= :passed result))))))
            (future-cancel f)))))))

(deftest resource-test
  (testing "server lifecycle with SSE"
    (let [port     (port)
          url      (format "http://localhost:%d" port)
          queue    (LinkedBlockingQueue.)
          state    {:url    url
                    :queue  queue
                    :failed false}
          response (hato/post (str url "/sse")
                              {:headers {"Accept" "text/event-stream"}
                               :as      :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f    (future
                     (try
                       (wait-for-sse-events reader queue done)
                       (catch Throwable e
                         (prn :error e)
                         (flush))))]
          (testing "initialisation"
            (let [state           (assoc state :plan (initialisation-plan))
                  [state' result] (run-plan state)]
              (is (= :passed result))
              (testing "resource interactions"
                (let [state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request
                                                  "resources/list" {} 0)}
                                        {:action :receive
                                         :data
                                         {:event "message"
                                          :data  (json-result
                                                  {:resources []}
                                                  nil
                                                  0)}}])
                      [state' result] (run-plan state)
                      _               (testing "resources/list"
                                        (is (= :passed result)))]))))
          (future-cancel f))))))

(deftest prompt-test
  (testing "server lifecycle with SSE"
    (let [port     (port)
          url      (format "http://localhost:%d" port)
          queue    (LinkedBlockingQueue.)
          state    {:url    url
                    :queue  queue
                    :failed false}
          response (hato/post (str url "/sse")
                              {:headers {"Accept" "text/event-stream"}
                               :as      :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f    (future
                     (try
                       (wait-for-sse-events reader queue done)
                       (catch Throwable e
                         (prn :error e)
                         (flush))))]
          (testing "initialisation"
            (let [state           (assoc state :plan (initialisation-plan))
                  [state' result] (run-plan state)]
              (is (= :passed result))
              (testing "prompt interactions"
                (let [state
                      (assoc
                       state'
                       :plan
                       [{:action :send
                         :msg    (json-request
                                  "prompts/list"
                                  {}
                                  0)}
                        {:action :receive
                         :data
                         {:event "message"
                          :data
                          (json-result
                           {:prompts
                            [{:name        "test-prompt",
                              :description "A test prompt for server testing",
                              :arguments
                              [{:name        "reply"
                                :description "something"
                                :required    true}]}]}
                           nil
                           0)}}])
                      [state' result] (run-plan state)
                      _               (testing "prompts/list"
                                        (is (= :passed result)))]))))
          (future-cancel f))))))

#_(deftest error-handling-test
    (testing "error handling"
      (let [port (port)
            url (format "http://localhost:%d" port)]

        (testing "invalid protocol version"
          (let [response (send-request
                          url
                          (make-request "initialize"
                                        (assoc valid-client-info
                                               :protocolVersion "0.2")
                                        1))]
            (is (= -32001 (get-in response [:error :code])))))

        (testing "uninitialized ping"
          (let [response (send-request url (make-request "ping" {} 1))]
            (is (= -32002 (get-in response [:error :code]))))))))
