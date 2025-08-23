(ns mcp-clj.stdio-server.main
  "Stdio-based MCP server main entry point"
  (:require
   [mcp-clj.mcp-server.core :as mcp-server]
   [mcp-clj.log :as log])
  (:gen-class))

(defn -main
  "Start stdio MCP server (uses stdin/stdout)"
  [& _args]
  (try
    (log/info :starting-stdio-server)
    (let [server (mcp-server/create-server {:transport :stdio})]
      (log/info :stdio-server-started)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(do
                                    (log/info :shutting-down-stdio-server)
                                    ((:stop server)))))
      ;; Keep the main thread alive
      @(promise))
    (catch Exception e
      (log/error :stdio-server-start-failed {:error (.getMessage e)})
      (System/exit 1))))