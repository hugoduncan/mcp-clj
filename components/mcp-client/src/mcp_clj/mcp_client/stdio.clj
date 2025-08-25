(ns mcp-clj.mcp-client.stdio
  "MCP client stdio transport - launches server process and communicates via stdin/stdout"
  (:require
   [clojure.java.process :as process]
   [mcp-clj.json-rpc.executor :as executor]
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
            json-rpc-client ; JSONRPClient instance
            reader-future
            running])

(defn- generate-request-id
  "Generate unique request ID"
  [transport]
  (stdio-client/generate-request-id (:json-rpc-client transport)))

(defn- handle-response
  "Handle JSON-RPC response by completing the corresponding future"
  [transport {:keys [id result error] :as response}]
  (stdio-client/handle-response (:json-rpc-client transport) response))

(defn- handle-notification
  "Handle JSON-RPC notification (no response expected)"
  [transport notification]
  (stdio-client/handle-notification notification))

(defn- message-reader-loop
  "Background loop to read messages from server"
  [transport]
  (let [{:keys [stdout]} (:process-info transport)]
    (stdio-client/message-reader-loop stdout (:running transport) (:json-rpc-client transport))))

(defn create-transport
  "Create stdio transport by launching MCP server process"
  [server-command]
  (let [process-info (start-server-process server-command)
        {:keys [stdin stdout]} process-info
        json-rpc-client (stdio-client/create-json-rpc-client stdout stdin)
        running (atom true)
        transport (->StdioTransport
                   server-command
                   process-info
                   json-rpc-client
                   nil
                   running)
        ;; Start background message reader
        reader-future (executor/submit!
                       (:executor json-rpc-client)
                       #(message-reader-loop transport))]
    (assoc transport :reader-future reader-future)))

(defn send-request!
  "Send JSON-RPC request and return CompletableFuture of response"
  [transport method params]
  (stdio-client/send-request! (:json-rpc-client transport) method params request-timeout-ms))

(defn send-notification!
  "Send JSON-RPC notification (no response expected)"
  [transport method params]
  (stdio-client/send-notification! (:json-rpc-client transport) method params))

(defn close!
  "Close transport and terminate server process"
  [transport]
  (reset! (:running transport) false)

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
  (and @(:running transport)
       (.isAlive ^Process (get-in transport [:process-info :process]))))
