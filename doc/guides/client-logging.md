# MCP Client Logging

Guide to receiving and managing structured log messages from MCP servers.

## Overview

MCP clients built with mcp-clj can receive structured log messages from servers that support the logging utility. This allows clients to monitor server operations, diagnose issues, and provide visibility into server behavior.

The client logging API provides:
- Setting the minimum log level on the server (server-side filtering)
- Subscribing to log messages with callbacks
- Multiple concurrent subscribers support
- Automatic string/keyword conversion for log levels

## Setting Log Level

Control which log messages the server sends by setting a minimum log level. The server will only send messages at or above this level.

```clojure
(require '[mcp-clj.mcp-client.core :as client])

;; Set log level to warning (receive warning, error, critical, alert, emergency)
@(client/set-log-level! mcp-client :warning)

;; Set log level to debug (receive all messages)
@(client/set-log-level! mcp-client :debug)

;; Set log level to error (receive only error, critical, alert, emergency)
@(client/set-log-level! mcp-client :error)
```

**Important**: Log level filtering happens on the **server side**. All subscribed callbacks receive the same filtered messages. The server does the filtering based on the level you set.

### Valid Log Levels

The 8 RFC 5424 severity levels (ordered from most to least severe):

| Keyword | String | Description |
|---------|--------|-------------|
| `:emergency` | "emergency" | System is unusable |
| `:alert` | "alert" | Action must be taken immediately |
| `:critical` | "critical" | Critical conditions |
| `:error` | "error" | Error conditions |
| `:warning` | "warning" | Warning conditions |
| `:notice` | "notice" | Normal but significant events |
| `:info` | "info" | General informational messages |
| `:debug` | "debug" | Detailed debugging information |

## Subscribing to Log Messages

Subscribe to receive log messages from the server with a callback function:

```clojure
(require '[mcp-clj.mcp-client.core :as client])

;; Subscribe to log messages
(def unsub-future
  (client/subscribe-log-messages!
    mcp-client
    (fn [{:keys [level logger data]}]
      (println (str "[" (name level) "]"
                    (when logger (str " " logger ":"))
                    " "
                    (pr-str data))))))

;; Get the unsubscribe function
(def unsub @unsub-future)

;; Later, unsubscribe
(unsub)
```

### Callback Parameters

The callback receives a map with the following keys:

- `:level` - Keyword log level (`:error`, `:warning`, etc.)
- `:logger` - Optional string component name (may be `nil`)
- `:data` - Message data (map, string, or other value)

Example callback data:
```clojure
{:level :error
 :logger "database"
 :data {:error "Connection failed" :host "localhost"}}
```

### Multiple Subscribers

Multiple callbacks can subscribe simultaneously. Each receives all filtered messages:

```clojure
;; Logger for console
(def unsub1
  @(client/subscribe-log-messages!
     mcp-client
     (fn [msg] (println "Console:" msg))))

;; Logger for file
(def unsub2
  @(client/subscribe-log-messages!
     mcp-client
     (fn [msg] (spit "app.log" (str msg "\n") :append true))))

;; Both receive the same messages
;; Later, unsubscribe individually
(unsub1)
(unsub2)
```

## Error Handling

### Invalid Log Level

`set-log-level!` validates the level before sending to the server:

```clojure
;; Throws ExceptionInfo with :invalid-log-level
(client/set-log-level! mcp-client :invalid)
;; => ExceptionInfo: Invalid log level {:level :invalid, :valid-levels (...)}

;; Also rejects common typos
(client/set-log-level! mcp-client :warn)  ; Should be :warning
;; => ExceptionInfo: Invalid log level
```

### Callback Exceptions

Exceptions in callbacks are caught and logged to prevent crashing the client:

```clojure
(client/subscribe-log-messages!
  mcp-client
  (fn [msg]
    (throw (ex-info "Callback error" {}))))  ; Error is logged, client continues

;; Other subscribers still receive messages
```

### Server Capability Check

When calling `set-log-level!`, the client checks if the server declared the `logging` capability. If not, a warning is logged but the request is still sent.

## Complete Example

```clojure
(ns my-app.client
  (:require
    [mcp-clj.mcp-client.core :as client]))

(defn setup-logging
  [mcp-client]
  ;; Set log level to info
  @(client/set-log-level! mcp-client :info)

  ;; Subscribe with formatted output
  (client/subscribe-log-messages!
    mcp-client
    (fn [{:keys [level logger data]}]
      (let [timestamp (.format (java.time.LocalDateTime/now)
                               (java.time.format.DateTimeFormatter/ISO_LOCAL_TIME))
            logger-str (if logger (str " [" logger "]") "")
            level-str (-> level name .toUpperCase)]
        (println (format "%s %s%s: %s"
                        timestamp
                        level-str
                        logger-str
                        (pr-str data)))))))

;; Example output:
;; 14:23:45.123 INFO [api]: {:status "Request processed"}
;; 14:23:46.456 ERROR [database]: {:error "Connection failed", :host "localhost"}
```

## Best Practices

### 1. Set Appropriate Log Levels

For production:
```clojure
@(client/set-log-level! mcp-client :warning)  ; Only warnings and errors
```

For development/debugging:
```clojure
@(client/set-log-level! mcp-client :debug)  ; All messages
```

### 2. Structured Logging

Process structured data in callbacks:

```clojure
(client/subscribe-log-messages!
  mcp-client
  (fn [{:keys [level data]}]
    (when (map? data)
      ;; Extract specific fields
      (when-let [error (:error data)]
        (alert-monitoring-system! error))

      ;; Log to analytics
      (track-event! {:event "server-log"
                     :level level
                     :error-type (:error data)}))))
```

### 3. Unsubscribe When Done

Always unsubscribe to prevent resource leaks:

```clojure
(let [unsub @(client/subscribe-log-messages! client callback)]
  (try
    (do-work)
    (finally
      (unsub))))
```

### 4. Handle Different Data Types

Log data can be strings, maps, or other values:

```clojure
(client/subscribe-log-messages!
  mcp-client
  (fn [{:keys [data]}]
    (cond
      (string? data) (println "Message:" data)
      (map? data) (println "Structured:" (pr-str data))
      :else (println "Other:" (pr-str data)))))
```

## Comparison with Server Logging

| Aspect | Client Logging | Server Logging |
|--------|---------------|----------------|
| Direction | Server → Client | Server → Clients |
| Purpose | Monitor server operations | Send operational info to clients |
| API | `set-log-level!`, `subscribe-log-messages!` | `logging/error`, `logging/info`, etc. |
| Filtering | Server-side (via `set-log-level!`) | Per-client level checking |
| Namespace | `mcp-clj.mcp-client.core` | `mcp-clj.mcp-server.logging` |

## Troubleshooting

**Q: Not receiving log messages**
A: Check that:
1. Server declares `logging` capability in its `initialize` response
2. You've subscribed with `subscribe-log-messages!`
3. Log level is set appropriately (lower levels receive more messages)
4. Server is actually sending messages at your level

**Q: How do I see all log levels?**
A:
```clojure
@(client/set-log-level! mcp-client :debug)  ; Lowest level = all messages
```

**Q: Can I filter by logger name?**
A: No. Filtering happens in your callback:
```clojure
(client/subscribe-log-messages!
  mcp-client
  (fn [{:keys [logger] :as msg}]
    (when (= logger "database")
      (process-db-log! msg))))
```

**Q: Does unsubscribing send a request to the server?**
A: No. Unsubscribing only removes the local callback. The server continues sending messages based on the log level you set with `set-log-level!`.
