(ns mcp-clj.client-transport.stdio
  "Stdio transport implementation for MCP client"
  (:require
    [clojure.java.process :as process]
    [mcp-clj.client-transport.protocol :as transport-protocol]
    [mcp-clj.json-rpc.protocols :as json-rpc-protocol]
    [mcp-clj.json-rpc.stdio-client :as stdio-client]
    [mcp-clj.log :as log])
  (:import
    (java.io
      BufferedReader
      BufferedWriter
      InputStreamReader
      OutputStreamWriter)
    (java.util.concurrent
      TimeUnit)))

;; Process Management

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

;; Transport Implementation

(defrecord StdioTransport
  [server-command
   process-info
   json-rpc-client] ; JSONRPClient instance

  transport-protocol/Transport

  (send-request!
    [_ method params timeout-ms]
    (json-rpc-protocol/send-request! json-rpc-client method params timeout-ms))


  (send-notification!
    [_ method params]
    (json-rpc-protocol/send-notification! json-rpc-client method params))


  (close!
    [_]
    ;; Close JSON-RPC client (cancels pending requests, closes streams, shuts down executor)
    (json-rpc-protocol/close! json-rpc-client)

    ;; Terminate process
    (log/warn :client/killing-process)
    (let [^Process process (:process process-info)]
      (try
        (.destroy process)
        (when-not (.waitFor process (long 5000) TimeUnit/MILLISECONDS)
          (log/warn :client/process-force-kill))
        (catch Exception e
          (log/error :client/process-close-error {:error e})))))


  (alive?
    [_]
    (and (json-rpc-protocol/alive? json-rpc-client)
         (.isAlive ^Process (:process process-info))))


  (get-json-rpc-client
    [_]
    json-rpc-client))

(defn create-transport
  "Create stdio transport by launching MCP server process"
  [{:keys [notification-handler] :as options}]
  (let [server-command (dissoc options :notification-handler)
        process-info (start-server-process server-command)
        {:keys [stdin stdout]} process-info
        json-rpc-client (stdio-client/create-json-rpc-client
                          stdout
                          stdin
                          {:notification-handler notification-handler})]
    (->StdioTransport
      server-command
      process-info
      json-rpc-client)))
