(ns mcp-clj.stdio-server.main
  "Stdio-based MCP server main entry point"
  (:gen-class)
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]))

(defn start
  "Start stdio MCP server (uses stdin/stdout)"
  [_]
  (try
    (log/info :stdio-server {:msg "Starting"})
    (with-open [server (mcp-server/create-server {:transport {:type :stdio}})]
      (log/info :stdio-server {:msg "Started"})
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
