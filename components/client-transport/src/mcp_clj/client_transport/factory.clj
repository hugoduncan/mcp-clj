(ns mcp-clj.client-transport.factory
  "Factory for creating MCP client transports with pluggable registry"
  (:require
    [mcp-clj.client-transport.http :as http]
    [mcp-clj.client-transport.protocol :as transport-protocol]
    [mcp-clj.client-transport.stdio :as stdio]))

;; Transport Registry

(defonce ^:private transport-registry
  (atom {}))

(defn register-transport!
  "Register a transport type with its factory function.

  Parameters:
  - transport-type: Keyword identifying the transport (e.g. :http, :stdio)
  - factory-fn: Function that takes transport options map and returns Transport

  The factory function will be called with the transport options (with :type removed)
  and should return an object implementing mcp-clj.client-transport.protocol/Transport.

  Example:
    (register-transport! :custom
      (fn [opts] (create-custom-transport opts)))"
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
  "Create transport based on configuration using the pluggable registry.

  Supports any registered transport type. Built-in types:
  - HTTP transport when :transport {:type :http ...} is provided
  - Stdio transport when :transport {:type :stdio ...} is provided

  Custom transports can be added via register-transport!"
  [{:keys [transport] :as config}]
  (if-not transport
    (throw
      (ex-info
        "Missing transport configuration"
        {:config config
         :supported (str ":transport map with :type from " (list-transports))}))

    (let [{:keys [type] :as transport-config} transport
          transport-options (dissoc transport-config :type)
          factory-fn (get @transport-registry type)]

      (if factory-fn
        (try
          (let [transport-instance (factory-fn transport-options)]
            ;; Validate that the factory returned a proper transport
            (when-not (satisfies? transport-protocol/Transport transport-instance)
              (throw (ex-info "Transport factory did not return a valid Transport"
                              {:transport-type type
                               :returned transport-instance})))
            transport-instance)
          (catch Exception e
            (throw (ex-info "Transport factory failed"
                            {:transport-type type
                             :options transport-options
                             :error (.getMessage e)}
                            e))))

        ;; Transport type not registered
        (throw
          (ex-info
            "Unregistered transport type"
            {:config config
             :transport-type type
             :registered-types (list-transports)}))))))

;; Auto-registration of Built-in Transports

(register-transport! :http http/create-transport)

;; Register in-memory transport (lazy-loaded from separate component)
(defn- create-in-memory-transport
  [options]
  (require 'mcp-clj.in-memory-transport.client)
  (let [create-fn (ns-resolve 'mcp-clj.in-memory-transport.client 'create-transport)]
    (create-fn options)))

(register-transport! :in-memory create-in-memory-transport)
(register-transport! :stdio stdio/create-transport)
