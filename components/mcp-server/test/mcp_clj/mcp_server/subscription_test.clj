(ns mcp-clj.mcp-server.subscription-test
  "Integration tests for resource subscription functionality"
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [hato.client :as hato]
    [mcp-clj.mcp-server.core :as mcp])
  (:import
    (java.io
      BufferedReader)
    (java.util.concurrent
      BlockingQueue
      LinkedBlockingQueue
      TimeUnit)))

(def test-resource
  {:name "test-resource"
   :uri "file:///test.txt"
   :mime-type "text/plain"
   :description "A test resource"
   :implementation (fn [_server _uri]
                     {:contents [{:uri "file:///test.txt"
                                  :mimeType "text/plain"
                                  :text "test content"}]})})

#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^:private ^:dynamic *server*)

(defn with-server
  [f]
  (let [server (mcp/create-server
                 {:transport {:type :sse :port 0}
                  :resources {"test-resource" test-resource}})]
    (try
      (binding [*server* server]
        (f))
      (finally
        ((:stop server))))))

(use-fixtures :each with-server)

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
        (cond
          (or (empty? line)
              (str/starts-with? line ":"))
          (do
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

(defn send-request
  [url request]
  (hato/post url
             {:headers {"Content-Type" "application/json"}
              :body (json/generate-string request)}))

(defn port
  []
  (:port @(:json-rpc-server *server*)))

(deftest ^:integ resource-subscription-test
  ;; Test resource subscriptions with session tracking and notifications
  (testing "resource subscription lifecycle"
    (let [port (port)
          url (format "http://localhost:%d" port)
          queue (LinkedBlockingQueue.)
          response (hato/get (str url "/sse")
                             {:headers {"Accept" "text/event-stream"}
                              :as :stream})]
      (with-open [reader (io/reader (:body response))]
        (let [done (volatile! nil)
              f (future
                  (try
                    (wait-for-sse-events reader queue done)
                    (catch Throwable e
                      (prn :subscription-test-error e))))]

          (testing "gets endpoint URL"
            (let [msg (poll queue)]
              (is msg "Should receive endpoint message")
              (is (= "endpoint" (:event msg)))
              (let [uri (:data msg)]
                (is (string? uri))
                (is (str/starts-with? uri "/messages?session_id="))

                (testing "initializes session"
                  (send-request
                    (str url uri)
                    {:jsonrpc "2.0"
                     :method "initialize"
                     :params {:protocolVersion "2024-11-05"
                              :capabilities {:roots {:listChanged true}}
                              :clientInfo {:name "test-client"
                                           :version "1.0.0"}}
                     :id 1})

                  (let [msg (poll queue)]
                    (is msg "Should receive initialize response")
                    (is (= "message" (:event msg)))
                    (let [data (:data msg)]
                      (is (= "2024-11-05" (:protocolVersion (:result data))))
                      (is (true? (get-in data [:result :capabilities :resources :subscribe]))
                          "Server should advertise subscribe capability")
                      (is (true? (get-in data [:result :capabilities :resources :listChanged]))
                          "Server should advertise listChanged capability")))

                  (send-request
                    (str url uri)
                    {:jsonrpc "2.0"
                     :method "notifications/initialized"
                     :params {}})

                  (testing "subscribes to resource"
                    (send-request
                      (str url uri)
                      {:jsonrpc "2.0"
                       :method "resources/subscribe"
                       :params {:uri "file:///test.txt"}
                       :id 2})

                    (let [msg (poll queue)]
                      (is msg "Should receive subscribe response")
                      (is (= "message" (:event msg)))
                      (let [data (:data msg)]
                        (is (= {} (:result data))
                            "Subscribe should return empty result on success")))

                    (testing "receives notification when resource updates"
                      (mcp/notify-resource-updated! *server* "file:///test.txt")

                      (let [msg (poll queue)]
                        (is msg "Should receive resource updated notification")
                        (is (= "message" (:event msg)))
                        (let [data (:data msg)]
                          (is (= "notifications/resources/updated" (:method data)))
                          (is (= "file:///test.txt" (get-in data [:params :uri]))))))

                    (testing "unsubscribes from resource"
                      (send-request
                        (str url uri)
                        {:jsonrpc "2.0"
                         :method "resources/unsubscribe"
                         :params {:uri "file:///test.txt"}
                         :id 3})

                      (let [msg (poll queue)]
                        (is msg "Should receive unsubscribe response")
                        (is (= "message" (:event msg)))
                        (let [data (:data msg)]
                          (is (= {} (:result data))
                              "Unsubscribe should return empty result")))

                      (testing "does not receive notification after unsubscribe"
                        (mcp/notify-resource-updated! *server* "file:///test.txt")

                        (let [msg (poll queue)]
                          (is (nil? msg)
                              "Should not receive notification after unsubscribe")))))

                  (testing "subscribing to non-existent resource returns error"
                    (send-request
                      (str url uri)
                      {:jsonrpc "2.0"
                       :method "resources/subscribe"
                       :params {:uri "file:///nonexistent.txt"}
                       :id 4})

                    (let [msg (poll queue)]
                      (is msg "Should receive subscribe error response")
                      (is (= "message" (:event msg)))
                      (let [data (:data msg)]
                        (is (get data :error)
                            "Should return JSON-RPC error for non-existent resource")
                        (is (= -32602 (get-in data [:error :code]))
                            "Should return -32602 (Invalid params) error code")
                        (is (str/includes?
                              (str (get-in data [:error :message]))
                              "not found")
                            "Error message should mention 'not found'"))))))))

          (future-cancel f))))))
