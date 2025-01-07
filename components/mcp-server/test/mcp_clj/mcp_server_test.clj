(ns mcp-clj.mcp-server-test
  (:require
   [clojure.data.json :as json]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hato.client :as hato]
   [mcp-clj.mcp-server.core :as mcp])
  (:import
   [java.util.concurrent
    CountDownLatch
    CyclicBarrier
    TimeUnit]))

(def valid-client-info
  {:clientInfo      {:name "test-client" :version "1.0"}
   :protocolVersion "0.1"
   :capabilities    {:tools {}}})

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
  (prn :send-request url)
  (-> (hato/post url
                 {:headers {"Content-Type" "application/json"}
                  :body    (json/write-str request)})
      :body
      (json/read-str :key-fn keyword)))

(defn make-request
  "Create JSON-RPC request"
  [method params id]
  {:jsonrpc "2.0"
   :method  method
   :params  params
   :id      id})

(deftest lifecycle-test
  (testing "server lifecycle"
    (let [port          (get-in *server* [:json-rpc-server :port])
          url           (format "http://localhost:%d" port)
          init-response (send-request url (make-request "initialize" valid-client-info 1))]

      (testing "initialization"
        (is (= 1 (get-in init-response [:id])))
        (is (get-in init-response [:result :serverInfo])))

      (testing "initialized notification"
        (let [response (send-request url
                                     {:jsonrpc "2.0"
                                      :method  "notifications/initialized"})]
          (is (nil? response))))

      (testing "ping after initialization"
        (let [response (send-request url (make-request "ping" {} 2))]
          (is (= 2 (:id response)))
          (is (= {} (:result response))))))))

(deftest error-handling-test
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

(deftest sse-test
  (testing "sse handling"
    (let [port     (get-in *server* [:json-rpc-server :port])
          _        (assert (pos-int? port))
          url      (format "http://localhost:%d" port)
          events   (atom [])
          barrier  (CountDownLatch. 1)
          response (hato/get (str url "/sse")
                             {:headers {"Accept" "text/event-stream"}
                              :as      :stream})]

      (testing "sse connection"
        (with-open [reader (clojure.java.io/reader (:body response))]
          (let [done (volatile! nil)

                f
                (future
                  (try
                    (loop []
                      (when-let [line (.readLine reader)]
                        (prn :read-line line)
                        (when-not (or (empty? line)
                                      (.startsWith line ":"))
                          (when-let [data (second (re-find #"^data: (.+)$" line))]
                            (prn :data data)
                            (prn :data' (json/read-str data :key-fn keyword))
                            (swap! events conj (json/read-str data :key-fn keyword))
                            (.countDown barrier)))
                        (when-not @done
                          (recur))))
                    (catch Exception _)))]

            (is (.await barrier 2 TimeUnit/SECONDS))
            (let [uri (first @events)]

              (prn :events @events)
              (send-request
               (str url uri)
               (make-request "initialize" valid-client-info 1)))

            (prn :events @events)
            (let [init-response (second @events)]
              (is (= "2.0" (:jsonrpc init-response)))
              (is (get-in init-response [:result :serverInfo])))

            (future-cancel f)))))))
