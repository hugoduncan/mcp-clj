(ns mcp-clj.mcp-server-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.data.json :as json]
   [hato.client :as hato]
   [mcp-clj.mcp-server.core :as mcp]))

(def valid-client-info
  {:clientInfo      {:name "test-client" :version "1.0"}
   :protocolVersion "0.1"
   :capabilities    {:tools {}}})

(deftest lifecycle-test
  (let [state  (atom {:initialized? false})
        server {:state state}]

    (testing "initialize request"
      (let [response (mcp/handle-initialize server valid-client-info)]
        (is (= "mcp-clj" (get-in response [:serverInfo :name]))
            "Returns server info")
        (is (= "0.1" (:protocolVersion response))
            "Returns protocol version")
        (is (map? (:capabilities response))
            "Returns capabilities")
        (is (false? (get-in response [:capabilities :tools :listChanged]))
            "Tools capability configured correctly")
        (is (= (:clientInfo valid-client-info)
               (:client-info @state))
            "Client info stored in state")))

    (testing "initialize with invalid version"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unsupported protocol version"
           (mcp/handle-initialize
            server
            (assoc valid-client-info :protocolVersion "0.2")))
          "Throws on version mismatch"))

    (testing "initialized notification"
      (is (nil? (mcp/handle-initialized server nil))
          "Returns nil for notification")
      (is (:initialized? @state)
          "Server marked as initialized"))

    (testing "ping after initialization"
      (is (= {} (mcp/ping server nil))
          "Ping returns empty map when initialized"))

    (testing "ping before initialization"
      (swap! state assoc :initialized? false)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Server not initialized"
           (mcp/ping server nil))
          "Ping throws when not initialized"))))

(def valid-client-info
  {:clientInfo      {:name "test-client" :version "1.0"}
   :protocolVersion "0.1"
   :capabilities    {:tools {}}})

(defn send-request
  "Send a JSON-RPC request and get response"
  [url request]
  (let [response (hato/post (str url "/message")
                            {:headers {"Content-Type" "application/json"}
                             :body    (json/write-str request)})
        body     (json/read-str (:body response))]
    (println "sent:" request)
    (println "received:" body)
    body))

(deftest integration-test
  (testing "server request handling"
    (let [server   (mcp/create-server {:port 0})
          port     (get-in server [:json-rpc-server :port])
          base-url (format "http://localhost:%d" port)

          initialize-request {:jsonrpc "2.0"
                              :method  "initialize"
                              :id      1
                              :params  valid-client-info}

          initialized-notification {:jsonrpc "2.0"
                                    :method  "notifications/initialized"}  ; No id for notification

          ping-request {:jsonrpc "2.0"
                        :method  "ping"
                        :id      2}]

      (try
        (let [init-response (send-request base-url initialize-request)]
          (is (= 1 (get init-response "id"))
              "Initialize response has correct id")
          (is (get-in init-response ["result" "serverInfo"])
              "Initialize response contains server info"))

        ;; Send initialized notification
        (send-request base-url initialized-notification)

        (let [ping-response (send-request base-url ping-request)]
          (is (= 2 (get ping-response "id"))
              "Ping response has correct id")
          (is (= {} (get ping-response "result"))
              "Ping response result is empty map"))

        (finally
          ((:stop server)))))))
