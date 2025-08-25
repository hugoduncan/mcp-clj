(ns mcp-clj.mcp-client.stdio
  "MCP client stdio transport - launches server process and communicates via stdin/stdout"
  (:require
   [clojure.java.process :as process]
   [mcp-clj.json-rpc.stdio-client :as stdio-client]
   [mcp-clj.log :as log])
  (:import
   [java.io BufferedReader
    BufferedWriter
    InputStreamReader
    OutputStreamWriter]
   [java.util.concurrent
    TimeUnit]))

;;; Configuration

(def ^:private request-timeout-ms 30000)

;;; Process Management

(defn- build-process-command
  "Build process command from server configuration"
  [server-config]
  (cond
    ;; Claude Code MCP server configuration format
    (and (map? server-config) (:command server-config))
    {:command (into [(:command server-config)] (:args server-config []))
     :env (:env server-config)
     :dir (:cwd server-config)}

    :else
    (throw (ex-info "Invalid server configuration"
                    {:config server-config
                     :expected "Map with :command and :args"}))))

(defn- start-server-process
  "Start MCP server process with given configuration"
  [server-config]
  (let [{:keys [command env dir]} (build-process-command server-config)
        process-opts (cond-> {:in :pipe
                              :out :pipe
                              :err :inherit}
                       env (assoc :env env)
                       dir (assoc :dir dir))
        _ (log/debug :client/process
                     {:command command :env env :dir dir})
        process (apply process/start process-opts command)]

    (log/debug :stdio/process-started {:command command :env env :dir dir})

    {:process process
     :stdin (BufferedWriter. (OutputStreamWriter. (process/stdin process)))
     :stdout (BufferedReader. (InputStreamReader. (process/stdout process)))}))

;;; JSON I/O

;;; Transport Implementation

(defrecord StdioTransport
           [server-command
            process-info
            json-rpc-client]) ; JSONRPClient instance

(defn create-transport
  "Create stdio transport by launching MCP server process"
  [server-command]
  (let [process-info (start-server-process server-command)
        {:keys [stdin stdout]} process-info
        json-rpc-client (stdio-client/create-json-rpc-client stdout stdin)]
    (->StdioTransport
     server-command
     process-info
     json-rpc-client)))

(defn close!
  "Close transport and terminate server process"
  [transport]
  ;; Close JSON-RPC client (cancels pending requests, closes streams, shuts down executor)
  (stdio-client/close-json-rpc-client! (:json-rpc-client transport))

  ;; Terminate process
  (log/warn :client/killing-process)
  (let [^Process process (get-in transport [:process-info :process])]
    (try
      (.destroy process)
      (when-not (.waitFor process 5000 TimeUnit/MILLISECONDS)
        (log/warn :client/process-force-kill))
      (catch Exception e
        (log/error :client/process-close-error {:error e})))))

(defn transport-alive?
  "Check if transport process is still alive"
  [transport]
  (and @(:running (:json-rpc-client transport))
       (.isAlive ^Process (get-in transport [:process-info :process]))))
