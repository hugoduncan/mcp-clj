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

(def valid-client-info
  {:clientInfo      {:name "test-client" :version "1.0"}
   :protocolVersion mcp/protocol-version
   :capabilities    {:tools {:listChanged false}}})

(def ^:private ^:dynamic  *server*)

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

(defn send-request
  "Send JSON-RPC request"
  [url request]
  (prn :send-request url request)
  (-> (hato/post url
                 {:headers {"Content-Type" "application/json"}
                  :body    (json/write-str request)})
      #_:body
      #_(json/read-str :key-fn keyword)))

(defn make-request
  "Create JSON-RPC request"
  [method params id]
  {:jsonrpc "2.0"
   :method  method
   :params  params
   :id      id})

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
  (let [{:keys [action data msg apply-fn]} (first (:plan state))]
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
                       true     (update :plan rest)))
                   (assoc state :failed true)))
      :send    (do
                 (prn :send msg)
                 (let [url  (str (:url state) (:uri state))
                       resp (send-request url msg)]
                   (prn :resp resp)
                   (if (< (:status resp) 300)
                     (update state :plan rest)
                     (assoc state :failed resp))))

      (assoc state :failed :unknown-action))))

(defn run-plan [state]
  (loop [state state]
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
  [method params]
  {:jsonrpc "2.0"
   :method  method
   :params  params})

(defn- json-result
  [result & [options]]
  (merge {:jsonrpc "2.0"
          :result  result}
         options))

(deftest lifecycle-test
  (testing "server lifecycle with SSE"
    (let [port     (get-in *server* [:json-rpc-server :port])
          url      (format "http://localhost:%d" port)
          queue    (LinkedBlockingQueue.)
          state    {:url    url
                    :queue  queue
                    :failed false}
          response (hato/get (str url "/sse")
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
            (let [state (assoc
                         state
                         :plan
                         [{:action   :receive
                           :data     {:event "endpoint"}
                           :apply-fn (update-state-apply-key :uri :data)}
                          {:action :send
                           :msg    (json-request
                                    "initialize"
                                    {:protocolVersion "2024-11-05"
                                     :capabilities    {:roots
                                                       {:listChanged true}
                                                       :tools
                                                       {:listChanged true}}
                                     :clientInfo      {:name    "mcp"
                                                       :version "0.1.0"}})}
                          {:action :receive
                           :data   {:event "message"}}
                          {:action :send
                           :msg    (json-request
                                    "notifications/initialized"
                                    {})}])

                  [state' result] (run-plan state)]
              (is (= :passed result))              ))
          (future-cancel f))))))

(deftest tools-test
  (testing "server lifecycle with SSE"
    (let [port     (get-in *server* [:json-rpc-server :port])
          url      (format "http://localhost:%d" port)
          queue    (LinkedBlockingQueue.)
          state    {:url    url
                    :queue  queue
                    :failed false}
          response (hato/get (str url "/sse")
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
            (let [state (assoc
                         state
                         :plan
                         [{:action   :receive
                           :data     {:event "endpoint"}
                           :apply-fn (update-state-apply-key :uri :data)}
                          {:action :send
                           :msg    (json-request
                                    "initialize"
                                    {:protocolVersion "2024-11-05"
                                     :capabilities    {:roots
                                                       {:listChanged true}
                                                       :tools
                                                       {:listChanged true}}
                                     :clientInfo      {:name    "mcp"
                                                       :version "0.1.0"}})}
                          {:action :receive
                           :data   {:event "message"}}
                          {:action :send
                           :msg    (json-request
                                    "notifications/initialized"
                                    {})}])

                  [state' result] (run-plan state)]
              (is (= :passed result))
              (testing "tool interactions"
                (let [state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request "tools/list" {})}
                                        {:action :receive
                                         :data
                                         {:event "message"
                                          :data
                                          (json-result
                                           {:tools
                                            [{:name "clj-eval",
                                              :description
                                              "Evaluates a Clojure expression and returns the result",
                                              :inputSchema
                                              {:type       "object",
                                               :properties {:code {:type "string"}},
                                               :required   ["code"]}}]})}}])
                      [state' result] (run-plan state)
                      _               (testing "tools/list"
                                        (is (= :passed result)))
                      state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request
                                                  "tools/call"
                                                  {:name      "clj-eval"
                                                   :arguments {:code "(+ 1 2)"}})}
                                        {:action :receive
                                         :data   {:event "message"
                                                  :data
                                                  (json-result
                                                   {:content
                                                    [{:type "text"
                                                      :text "3"}]})}}])
                      [state' result] (run-plan state)
                      _               (testing "successful tools/call"
                                        (is (= :passed result)))
                      state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request
                                                  "tools/call"
                                                  {:name "clj-eval"
                                                   :arguments
                                                   {:code "(/ 1 0)"}})}
                                        {:action :receive
                                         :data
                                         {:event "message"
                                          :data
                                          (json-result
                                           {:content
                                            [{:type "text"
                                              :text "Error: Divide by zero"}]
                                            :isError true})}}])
                      [state' result] (run-plan state)
                      _               (testing "tools/call with eval error"
                                        (is (= :passed result)))
                      state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request
                                                  "tools/call"
                                                  {:name "clj-eval"
                                                   :arguments
                                                   {:code "(/ 1 0"}})}
                                        {:action :receive
                                         :data
                                         {:event "message"
                                          :data
                                          (json-result
                                           {:content
                                            [{:type "text"
                                              :text "Error: EOF while reading"}]
                                            :isError true})}}])
                      [state' result] (run-plan state)
                      _               (testing "tools/call with invalid clojure"
                                        (is (= :passed result)))
                      state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request
                                                  "tools/call"
                                                  {:name "unkown"
                                                   :arguments
                                                   {:code "(/ 1 0)"}})}
                                        {:action :receive
                                         :data
                                         {:event "message"
                                          :data
                                          (json-result
                                           {:content
                                            [{:type "text"
                                              :text "Tool not found: unkown"}]
                                            :isError true})}}])
                      [state' result] (run-plan state)
                      _               (testing "tools/call with unknown tool"
                                        (is (= :passed result)))]))))
          (future-cancel f))))))

(deftest resource-test
  (testing "server lifecycle with SSE"
    (let [port     (get-in *server* [:json-rpc-server :port])
          url      (format "http://localhost:%d" port)
          queue    (LinkedBlockingQueue.)
          state    {:url    url
                    :queue  queue
                    :failed false}
          response (hato/get (str url "/sse")
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
            (let [state (assoc
                         state
                         :plan
                         [{:action   :receive
                           :data     {:event "endpoint"}
                           :apply-fn (update-state-apply-key :uri :data)}
                          {:action :send
                           :msg    (json-request
                                    "initialize"
                                    {:protocolVersion "2024-11-05"
                                     :capabilities    {:roots
                                                       {:listChanged true}
                                                       :tools
                                                       {:listChanged true}}
                                     :clientInfo      {:name    "mcp"
                                                       :version "0.1.0"}})}
                          {:action :receive
                           :data   {:event "message"}}
                          {:action :send
                           :msg    (json-request
                                    "notifications/initialized"
                                    {})}])

                  [state' result] (run-plan state)]
              (is (= :passed result))
              (testing "resource interactions"
                (let [state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request "resources/list" {})}
                                        {:action :receive
                                         :data
                                         {:event "message"
                                          :data  (json-result {:resources []})}}])
                      [state' result] (run-plan state)
                      _               (testing "resources/list"
                                        (is (= :passed result)))]))))
          (future-cancel f))))))

(deftest prompt-test
  (testing "server lifecycle with SSE"
    (let [port     (get-in *server* [:json-rpc-server :port])
          url      (format "http://localhost:%d" port)
          queue    (LinkedBlockingQueue.)
          state    {:url    url
                    :queue  queue
                    :failed false}
          response (hato/get (str url "/sse")
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
            (let [state (assoc
                         state
                         :plan
                         [{:action   :receive
                           :data     {:event "endpoint"}
                           :apply-fn (update-state-apply-key :uri :data)}
                          {:action :send
                           :msg    (json-request
                                    "initialize"
                                    {:protocolVersion "2024-11-05"
                                     :capabilities    {:roots
                                                       {:listChanged true}
                                                       :tools
                                                       {:listChanged true}}
                                     :clientInfo      {:name    "mcp"
                                                       :version "0.1.0"}})}
                          {:action :receive
                           :data   {:event "message"}}
                          {:action :send
                           :msg    (json-request
                                    "notifications/initialized"
                                    {})}])

                  [state' result] (run-plan state)]
              (is (= :passed result))
              (testing "prompt interactions"
                (let [state           (assoc
                                       state'
                                       :plan
                                       [{:action :send
                                         :msg    (json-request "prompts/list" {})}
                                        {:action :receive
                                         :data
                                         {:event "message"
                                          :data  (json-result {:prompts []})}}])
                      [state' result] (run-plan state)
                      _               (testing "prompts/list"
                                        (is (= :passed result)))]))))
          (future-cancel f))))))

#_(deftest error-handling-test
    (testing "error handling"
      (let [port (get-in *server* [:json-rpc-server :port])
            url  (format "http://localhost:%d" port)]

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
