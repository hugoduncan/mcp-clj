(ns mcp-clj.server-transport.factory
  "Factory for creating MCP server transports with pluggable registry")

;; Transport Registry

(defonce ^:private transport-registry
  (atom {}))

(defn register-transport!
  "Register a transport type with its factory function.

  Parameters:
  - transport-type: Keyword identifying the transport (e.g. :http, :sse, :stdio)
  - factory-fn: Function that takes transport options map and returns server instance

  The factory function will be called with the transport options (with :type removed)
  and should return a server instance with appropriate lifecycle methods.

  Example:
    (register-transport! :custom
      (fn [opts] (create-custom-server opts)))"
  [transport-type factory-fn]
  (when-not (keyword? transport-type)
    (throw (ex-info "Transport type must be a keyword"
                    {:transport-type transport-type})))
  (when-not (fn? factory-fn)
    (throw (ex-info "Factory must be a function"
                    {:factory-fn factory-fn})))
  (swap! transport-registry assoc transport-type factory-fn)
  nil)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}

(defn unregister-transport!
  "Remove a transport type from the registry.

  Parameters:
  - transport-type: Keyword identifying the transport to remove"
  [transport-type]
  (swap! transport-registry dissoc transport-type)
  nil)

(defn list-transports
  "Return a vector of all registered transport types."
  []
  (vec (keys @transport-registry)))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}

(defn transport-registered?
  "Check if a transport type is registered.

  Parameters:
  - transport-type: Keyword to check

  Returns true if the transport type is registered, false otherwise."
  [transport-type]
  (contains? @transport-registry transport-type))

;; Factory Function

(defn create-transport
  "Create server transport based on configuration using the pluggable registry.

  Supports any registered transport type. Built-in types:
  - HTTP transport when :type :http is provided
  - SSE transport when :type :sse is provided
  - Stdio transport when :type :stdio is provided

  Custom transports can be added via register-transport!"
  [transport-config handlers]
  (let [{:keys [type] :as transport-options} transport-config
        options (dissoc transport-options :type)
        factory-fn (get @transport-registry type)]

    (if factory-fn
      (try
        (factory-fn options handlers)
        (catch Exception e
          (throw (ex-info "Transport factory failed"
                          {:transport-type type
                           :options options
                           :error (.getMessage e)}
                          e))))

      ;; Transport type not registered
      (throw
        (ex-info
          "Unregistered transport type"
          {:transport-type type
           :transport-config transport-config
           :registered-types (list-transports)})))))

;; Built-in Transport Factories

(defn- create-stdio-server
  [options handlers]
  (require 'mcp-clj.json-rpc.stdio-server)
  (let [create-server (ns-resolve 'mcp-clj.json-rpc.stdio-server 'create-server)
        set-handlers! (ns-resolve 'mcp-clj.json-rpc.stdio-server 'set-handlers!)
        server-opts (merge {:handlers handlers} (dissoc options :on-sse-connect :on-sse-close))
        server (create-server server-opts)]
    ;; Set handlers immediately after creation
    (when handlers
      (set-handlers! server handlers))
    server))

(defn- create-sse-server
  [{:keys [port on-sse-connect on-sse-close allowed-origins]} handlers]
  (require 'mcp-clj.json-rpc.sse-server)
  (let [create-server (ns-resolve 'mcp-clj.json-rpc.sse-server 'create-server)
        set-handlers! (ns-resolve 'mcp-clj.json-rpc.sse-server 'set-handlers!)
        server-opts {:port (or port 3001)
                     :on-sse-connect on-sse-connect
                     :on-sse-close on-sse-close
                     :allowed-origins (or allowed-origins ["*"])}
        server (create-server server-opts)]
    ;; Set handlers after creation
    (when handlers
      (set-handlers! server handlers))
    server))

(defn- create-http-server
  [{:keys [port num-threads on-connect on-disconnect allowed-origins]} handlers]
  (require 'mcp-clj.json-rpc.http-server)
  (let [create-server (ns-resolve 'mcp-clj.json-rpc.http-server 'create-server)
        set-handlers! (ns-resolve 'mcp-clj.json-rpc.http-server 'set-handlers!)
        server-opts {:port (or port 3001)
                     :num-threads (or num-threads 4)
                     :on-connect (or on-connect (fn [& _]))
                     :on-disconnect (or on-disconnect (fn [& _]))
                     :allowed-origins (or allowed-origins [])}
        server (create-server server-opts)]
    ;; Set handlers after creation
    (when handlers
      (set-handlers! server handlers))
    server))

;; Auto-registration of Built-in Transports

(register-transport! :stdio create-stdio-server)
(register-transport! :sse create-sse-server)
(register-transport! :http create-http-server)

;; Register in-memory transport (lazy-loaded from separate component)
(defn- create-in-memory-server
  [options handlers]
  (require 'mcp-clj.in-memory-transport.server)
  (let [create-server (ns-resolve 'mcp-clj.in-memory-transport.server 'create-in-memory-server)
        ;; Map SSE callbacks to generic connection callbacks for in-memory transport
        adapted-options (-> options
                            (assoc :on-connect (:on-sse-connect options))
                            (assoc :on-disconnect (:on-sse-close options))
                            (dissoc :on-sse-connect :on-sse-close))]
    (create-server adapted-options handlers)))

(register-transport! :in-memory create-in-memory-server)
