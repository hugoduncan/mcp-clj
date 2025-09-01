(ns mcp-clj.stdio-server.main
  "Stdio-based MCP server main entry point"
  (:require
   [mcp-clj.mcp-server.core :as mcp-server]
   [mcp-clj.log :as log])
  (:gen-class))

(defn start
  "Start stdio MCP server (uses stdin/stdout)"
  [_]
  (try
    (log/warn :stdio-server {:msg "Starting"})
    (with-open [server (mcp-server/create-server {:transport :stdio})]
      (log/warn :stdio-server {:msg "Started"})
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. #(do
                   (log/info :shutting-down-stdio-server)
                   ((:stop server)))))
      ;; Keep the main thread alive
      @(promise))
    (catch Exception e
      (log/error :stdio-server {:error (.getMessage e)})
      (System/exit 1))))

(defn -main
  "Start stdio MCP server (uses stdin/stdout)"
  [& _args]
  (start {}))
