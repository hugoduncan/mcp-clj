(ns mcp-clj.sse-server.main
  "SSE-based MCP server main entry point"
  (:gen-class)
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]))

(defn parse-args
  "Parse command line arguments for SSE server"
  [args]
  (let [port-idx (.indexOf args "--port")
        port (if (and (not= port-idx -1)
                      (< (inc port-idx) (count args)))
               (Integer/parseInt (nth args (inc port-idx)))
               3001)]
    {:port port}))

(defn -main
  "Start SSE MCP server with specified port (default 3001)"
  [& args]
  (try
    (let [{:keys [port]} (parse-args args)]
      (log/info :starting-sse-server {:port port})
      (let [server (mcp-server/create-server {:transport {:type :sse :port port}})]
        (log/info :sse-server-started)
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. #(do
                                      (log/info :shutting-down-sse-server)
                                      ((:stop server)))))
        ;; Keep the main thread alive
        @(promise)))
    (catch Exception e
      (log/error :sse-server-start-failed {:error (.getMessage e)})
      (System/exit 1))))
