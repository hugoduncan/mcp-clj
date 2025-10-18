(ns mcp-clj.java-sdk.sdk-server-main
  "Main entry point for running a Java SDK MCP server with stdio transport.

  This allows the SDK server to be started as a subprocess for testing.

  Usage: clj -M:dev:test -m mcp-clj.java-sdk.sdk-server-main"
  (:gen-class)
  (:require
    [mcp-clj.java-sdk.interop :as java-sdk]
    [mcp-clj.log :as log]))

(defn create-test-tools
  "Create a set of test tools for the SDK server"
  []
  [{:name "echo"
    :description "Echo the input message"
    :input-schema {:type "object"
                   :properties {:message {:type "string"}}
                   :required ["message"]}
    :implementation (fn [args]
                      (log/info :sdk-server/echo-called {:args args})
                      {:content [{:type "text"
                                  :text (str "Echo: " (:message args))}]})}

   {:name "add"
    :description "Add two numbers"
    :input-schema {:type "object"
                   :properties {:a {:type "number"}
                                :b {:type "number"}}
                   :required ["a" "b"]}
    :implementation (fn [args]
                      (log/info :sdk-server/add-called {:args args})
                      (let [result (+ (:a args) (:b args))]
                        {:content [{:type "text"
                                    :text (str result)}]}))}

   {:name "get-time"
    :description "Get current time"
    :input-schema {:type "object"
                   :properties {}}
    :implementation (fn [_args]
                      (log/info :sdk-server/get-time-called)
                      {:content [{:type "text"
                                  :text (str (java.util.Date.))}]})}])

(defn -main
  "Main entry point for SDK server.

  Starts a Java SDK MCP server with stdio transport and registers test tools."
  [& args]
  (log/info :sdk-server-main/starting {:args args})

  (try
    ;; Create SDK server with stdio transport
    (with-open [^java.lang.AutoCloseable server (java-sdk/create-java-server
                                                  {:name    "java-sdk-test-server"
                                                   :version "1.0.0"
                                                   ;; Use sync for simpler subprocess handling
                                                   :async?  false})]
      (let [tools (create-test-tools)]

        (log/info :sdk-server-main/server-created {:name (:name server)})

        ;; Register all test tools
        (doseq [tool tools]
          (java-sdk/register-tool server tool)
          (log/info :sdk-server-main/tool-registered {:tool-name (:name tool)}))

        ;; Start the server - this will block and handle stdio communication
        (log/info :sdk-server-main/starting-server)
        (java-sdk/start-server server)

        ;; Server is now running and handling requests via stdio
        ;; It will run until the process is terminated
        (log/info :sdk-server-main/server-running)

        ;; Add shutdown hook for cleanup
        (.addShutdownHook
          (Runtime/getRuntime)
          (Thread. (fn []
                     (log/info :sdk-server-main/shutting-down)
                     (try
                       (java-sdk/stop-server server)
                       (catch Exception e
                         (log/error :sdk-server-main/shutdown-error {:error e}))))))

        ;; Keep the process alive
        ;; The server's start method should block, but if not, we wait
        (Thread/sleep Long/MAX_VALUE)))

    (catch Exception e
      (log/error :sdk-server-main/error {:error e})
      (System/exit 1))))

;; For REPL testing
(comment
  ;; Start server in REPL (will block)
  (-main)

  ;; Or start in background thread for testing
  (future (-main)))
