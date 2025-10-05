(ns mcp-clj.cross-compliance-test.sdk-client.logging-server
  "Test server for cross-compliance logging tests.
  
  This server enables the logging capability for testing with Java SDK client."
  (:gen-class)
  (:require
   [mcp-clj.log :as log]
   [mcp-clj.mcp-server.core :as mcp-server]
   [mcp-clj.tools.registry :as tools-registry]))

(defn -main
  "Start stdio MCP server with logging capability enabled"
  [& _args]
  (try
    (log/info :test-logging-server {:msg "Starting with logging capability"})

    ;; Create default tools
    (let [tools (tools-registry/create-registry)]
      (tools-registry/add-tool!
       tools
       {:name "clj-eval"
        :description "Evaluate Clojure code"
        :input-schema {:type "object"
                       :properties {:code {:type "string"
                                           :description "Clojure code to evaluate"}}
                       :required ["code"]}
        :implementation (fn [params]
                          [{:type "text"
                            :text (str (eval (read-string (:code params))))}])})

      (tools-registry/add-tool!
       tools
       {:name "ls"
        :description "List directory contents"
        :input-schema {:type "object"
                       :properties {:path {:type "string"
                                           :description "Directory path"}}
                       :required ["path"]}
        :implementation (fn [params]
                          (let [files (seq (.listFiles (java.io.File. (:path params))))]
                            [{:type "text"
                              :text (clojure.string/join "\n" (map #(.getName %) files))}]))})

      ;; Create server with logging capability
      (with-open [server (mcp-server/create-server
                          {:transport {:type :stdio}
                           :tools tools
                           :server-info {:name "test-logging-server"
                                         :version "1.0.0"}
                           :capabilities {:logging {}}})]
        (log/info :test-logging-server {:msg "Started"})

        (.addShutdownHook
         (Runtime/getRuntime)
         (Thread. #(do
                     (log/info :shutting-down-test-logging-server)
                     ((:stop server)))))

        ;; Keep the main thread alive
        @(promise)))

    (catch Exception e
      (log/error :test-logging-server {:error (.getMessage e)
                                       :stack-trace (with-out-str (.printStackTrace e))})
      (System/exit 1))))
