(ns mcp-clj.mcp-client.logging
  "Logging support for MCP client.

  Provides functions for setting log levels and subscribing to log messages
  from MCP servers following the logging utility protocol."
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-client.subscriptions :as subscriptions]
    [mcp-clj.mcp-client.transport :as transport])
  (:import
    (java.util.concurrent
      CompletableFuture)))

;; Log level constants and validation

(def log-levels
  "RFC 5424 severity levels with numeric ordering.
  Lower numbers are more severe."
  {:emergency 0
   :alert 1
   :critical 2
   :error 3
   :warning 4
   :notice 5
   :info 6
   :debug 7})

(defn valid-level?
  "Check if the given level is a valid RFC 5424 log level."
  [level]
  (contains? log-levels level))

(defn keyword->string
  "Convert a log level keyword to its string representation.

  Examples:
    (keyword->string :error) => \"error\"
    (keyword->string :warning) => \"warning\""
  [level]
  (name level))

(defn string->keyword
  "Convert a log level string to its keyword representation.

  Examples:
    (string->keyword \"error\") => :error
    (string->keyword \"warning\") => :warning"
  [level-str]
  (keyword level-str))

;; Core implementation functions

(defn set-log-level-impl!
  "Implementation of set-log-level! - sets minimum log level on the server.

  Validates the level, checks server capability, and sends logging/setLevel request."
  [client level]
  (when-not (valid-level? level)
    (throw (ex-info "Invalid log level"
                    {:level level
                     :valid-levels (keys log-levels)
                     :error-type :invalid-log-level})))

  (let [session @(:session client)
        server-capabilities (get-in session [:server-info :capabilities])]
    (when-not (:logging server-capabilities)
      (log/warn :client/logging-not-supported
                {:msg "Server does not declare logging capability"
                 :server-info (:server-info session)})))

  (let [level-str (keyword->string level)]
    (transport/send-request!
      (:transport client)
      "logging/setLevel"
      {:level level-str}
      30000)))

(defn subscribe-log-messages-impl!
  "Implementation of subscribe-log-messages! - subscribes to log message notifications.

  Returns a CompletableFuture that resolves to an unsubscribe function."
  [client callback-fn]
  (let [registry (:subscription-registry client)]
    (subscriptions/subscribe-log-messages! registry callback-fn)

    (let [future (CompletableFuture.)]
      (.complete future
                 (fn []
                   (subscriptions/unsubscribe-log-messages! registry callback-fn)))
      future)))
