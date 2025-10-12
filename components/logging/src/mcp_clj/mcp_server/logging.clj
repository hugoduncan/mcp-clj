(ns mcp-clj.mcp-server.logging
  "MCP server logging utility for sending structured log messages to clients.

  This component provides the public API for MCP server authors to send log
  messages to their clients using the MCP logging utility protocol.

  This is separate from the internal mcp-clj.log component which is used for
  debugging the mcp-clj framework itself."
  (:require
    [mcp-clj.json-rpc.protocols :as json-rpc-protocols]))

;; Log level constants and utilities

(def ^:private log-levels
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

(def default-log-level
  "Default minimum log level when client hasn't set one.
  Defaults to :error as recommended by requirements."
  :error)

(defn valid-level?
  "Check if the given level is a valid RFC 5424 log level."
  [level]
  (contains? log-levels level))

(defn- level>=
  "Check if message-level should be sent given the threshold-level.
  Returns true if message is at or above (more severe than) threshold."
  [message-level threshold-level]
  (<= (get log-levels message-level)
      (get log-levels threshold-level)))

;; Session log level management

(defn get-session-log-level
  "Get the minimum log level for a session.
  Returns the session's configured level or the default if not set."
  [session]
  (or (:log-level session) default-log-level))

(defn should-send-to-session?
  "Check if a message at message-level should be sent to the given session."
  [session message-level]
  (let [threshold (get-session-log-level session)]
    (level>= message-level threshold)))

;; Core logging API

(defn log-message
  "Send a log message to client session(s) at the specified level.

  Args:
    context - Map with :server key and optional :session-id key
    level - One of: :debug :info :notice :warning :error :critical :alert :emergency
    data - Any JSON-serializable data (map, string, vector, etc.)

  Options:
    :logger - Optional string identifying the logger/component name

  When :session-id is present in context, sends notification only to that specific
  session if it is initialized and has requested this log level or higher.

  When :session-id is nil or not present in context, broadcasts to all initialized
  sessions that have requested this log level or higher.

  Security: DO NOT include sensitive data (credentials, PII, internal system
  details) in log messages. Server authors are responsible for sanitization.

  Examples:
    ;; Send to specific session (from tool implementation)
    (log-message {:server server :session-id \"session-123\"} :error
                 {:error \"Connection failed\" :host \"localhost\"}
                 :logger \"database\")
    
    ;; Broadcast to all sessions
    (log-message {:server server} :info {:status \"Server started\"})"
  [context level data & {:keys [logger]}]
  (when-not (valid-level? level)
    (throw (ex-info "Invalid log level"
                    {:level level
                     :valid-levels (keys log-levels)})))

  (let [{:keys [server session-id]} context
        rpc-server @(:json-rpc-server server)
        params (cond-> {:level (name level)
                        :data data}
                 logger (assoc :logger logger))]

    (if session-id
      ;; Send to specific session
      (let [session (get @(:session-id->session server) session-id)]
        (when (and session
                   (:initialized? session)
                   (should-send-to-session? session level))
          (json-rpc-protocols/notify!
            rpc-server
            session-id
            "notifications/message"
            params)))

      ;; Broadcast to all initialized sessions
      (let [sessions @(:session-id->session server)]
        (doseq [[sid session] sessions]
          (when (and (:initialized? session)
                     (should-send-to-session? session level))
            (json-rpc-protocols/notify!
              rpc-server
              sid
              "notifications/message"
              params)))))))

;; Convenience functions for each log level

(defn debug
  "Send a debug-level log message to a specific session.

  Args:
    context - Map with :server and :session-id keys
    data - Any JSON-serializable data

  Options:
    :logger - Optional string identifying the logger/component"
  [context data & opts]
  (apply log-message context :debug data opts))

(defn info
  "Send an info-level log message to a specific session.

  Args:
    context - Map with :server and :session-id keys
    data - Any JSON-serializable data

  Options:
    :logger - Optional string identifying the logger/component"
  [context data & opts]
  (apply log-message context :info data opts))

(defn notice
  "Send a notice-level log message to a specific session.

  Args:
    context - Map with :server and :session-id keys
    data - Any JSON-serializable data

  Options:
    :logger - Optional string identifying the logger/component"
  [context data & opts]
  (apply log-message context :notice data opts))

(defn warn
  "Send a warning-level log message to a specific session.

  Args:
    context - Map with :server and :session-id keys
    data - Any JSON-serializable data

  Options:
    :logger - Optional string identifying the logger/component"
  [context data & opts]
  (apply log-message context :warning data opts))

(defn error
  "Send an error-level log message to a specific session.

  Args:
    context - Map with :server and :session-id keys
    data - Any JSON-serializable data

  Options:
    :logger - Optional string identifying the logger/component"
  [context data & opts]
  (apply log-message context :error data opts))

(defn critical
  "Send a critical-level log message to a specific session.

  Args:
    context - Map with :server and :session-id keys
    data - Any JSON-serializable data

  Options:
    :logger - Optional string identifying the logger/component"
  [context data & opts]
  (apply log-message context :critical data opts))

(defn alert
  "Send an alert-level log message to a specific session.

  Args:
    context - Map with :server and :session-id keys
    data - Any JSON-serializable data

  Options:
    :logger - Optional string identifying the logger/component"
  [context data & opts]
  (apply log-message context :alert data opts))

(defn emergency
  "Send an emergency-level log message to a specific session.

  Args:
    context - Map with :server and :session-id keys
    data - Any JSON-serializable data

  Options:
    :logger - Optional string identifying the logger/component"
  [context data & opts]
  (apply log-message context :emergency data opts))
