(ns mcp-clj.json-rpc.server-test
  (:require
   [clojure.test :refer :all]
   [mcp-clj.json-rpc.server :as server]
   [mcp-clj.json-rpc.protocol :as protocol])
  (:import
   [java.net HttpURLConnection URL]))

(defn- send-request
  "Helper function to send a JSON-RPC request to the server"
  [url request-data]
  (let [conn      (.openConnection (URL. url))
        json-data (first (protocol/write-json request-data))]
    (doto conn
      (.setRequestMethod "POST")
      (.setRequestProperty "Content-Type" "application/json")
      (.setRequestProperty "Content-Length" (str (count (.getBytes json-data))))
      (.setDoOutput true))

    (with-open [os (.getOutputStream conn)]
      (.write os (.getBytes json-data))
      (.flush os))

    (let [response-code (.getResponseCode conn)
          response-body (slurp (.getInputStream conn))]
      {:status response-code
       :body   (first (protocol/parse-json response-body))})))

(deftest server-creation
  (testing "Server creation validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Port is required"
                          (server/create-server {})))

    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Handlers must be a map"
                          (server/create-server {:port 8080}))))

  (testing "Successful server creation"
    (let [handlers              {"echo" (fn [params] params)}
          {:keys [server stop]} (server/create-server
                                 {:port     8080
                                  :handlers handlers})]
      (try
        (is (some? server))
        (is (fn? stop))
        (finally
          (stop))))))

(deftest server-functionality
  (testing "Echo method response"
    (let [test-port      8081
          test-data      {:message "Hello" :number 42}
          handlers       {"echo" (fn [params] params)}
          {:keys [stop]} (server/create-server
                          {:port     test-port
                           :handlers handlers})]
      (try
        (let [request               {:jsonrpc "2.0"
                                     :method  "echo"
                                     :params  test-data
                                     :id      1}
              {:keys [status body]} (send-request
                                     (str "http://localhost:" test-port)
                                     request)]
          (is (= 200 status))
          (is (= "2.0" (:jsonrpc body)))
          (is (= 1 (:id body)))
          (is (= test-data (:result body))))
        (finally
          (stop))))))
