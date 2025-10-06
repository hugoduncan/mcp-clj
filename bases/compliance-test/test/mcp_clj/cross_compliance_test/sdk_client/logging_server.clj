(ns mcp-clj.cross-compliance-test.sdk-client.logging-server
  "Test server for cross-compliance logging tests.

  This server enables the logging capability for testing with Java SDK client."
  (:gen-class)
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-clj.mcp-server.logging :as server-logging]))

(defn trigger-logs
  [server-atom params]
  (let [server @server-atom
        levels (:levels params)
        message (:message params)
        logger (:logger params)
        log-fn-map {:debug server-logging/debug
                    :info server-logging/info
                    :notice server-logging/notice
                    :warning server-logging/warn
                    :error server-logging/error
                    :critical server-logging/critical
                    :alert server-logging/alert
                    :emergency server-logging/emergency}]
    (doseq [level levels]
      (let [log-fn (get log-fn-map (keyword level))]
        ;; Send data as string to work around Java SDK bug
        ;; The SDK incorrectly expects data to be a string, but MCP spec allows any JSON type
        (if logger
          (log-fn server message :logger logger)
          (log-fn server message))))
    [{:type "text"
      :text (str "Triggered " (count levels) " log message(s)")}]))

(defn -main
  "Start stdio MCP server with logging capability enabled"
  [& _args]
  (try
    (log/info :test-logging-server {:msg "Starting with logging capability"})

    ;; Atom to hold server reference for trigger-logs tool
    (let [server-atom (atom nil)

          ;; Define tools as a map
          tools
          {"trigger-logs"
           {:name "trigger-logs"
            :description "Trigger log messages at specified levels for testing"
            :inputSchema
            {:type "object"
             :properties
             {:levels
              {:type "array"
               :items {:type "string"
                       :enum ["debug" "info" "notice" "warning"
                              "error" "critical" "alert" "emergency"]}
               :description "Log levels to emit"}
              :message {:type "string"
                        :description "Message to log"}
              :logger {:type "string"
                       :description "Optional logger name"}}
             :required ["levels" "message"]}
            :implementation (partial trigger-logs server-atom)}}

          ;; Create server with logging capability
          server (mcp-server/create-server
                   {:transport {:type :stdio}
                    :tools tools
                    :server-info {:name "test-logging-server"
                                  :version "1.0.0"}
                    :capabilities {:logging {}}})]

      ;; Store server reference for trigger-logs tool
      (reset! server-atom server)

      (log/info :test-logging-server {:msg "Started"})

      (.addShutdownHook
        (Runtime/getRuntime)
        (Thread. #(do
                    (log/info :shutting-down-test-logging-server)
                    ((:stop server)))))

      ;; Keep the main thread alive
      @(promise))

    (catch Exception e
      (log/error :test-logging-server {:error (.getMessage e)
                                       :stack-trace (with-out-str (.printStackTrace e))})
      (.printStackTrace e)
      (System/exit 1))))
