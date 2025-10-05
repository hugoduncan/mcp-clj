# MCP Server Logging

Guide to using the MCP logging utility to send structured log messages to your clients.

## Overview

The MCP logging utility allows MCP servers built with mcp-clj to send structured log messages to their clients (like Claude Desktop). This provides a standardized way for servers to communicate diagnostic and operational information to clients.

**Important**: This is separate from the internal `mcp-clj.log` component, which is used for debugging the mcp-clj framework itself.

## Enabling Logging

To enable logging capability, pass `:logging {}` in the `:capabilities` map when creating your server:

```clojure
(require '[mcp-clj.mcp-server.core :as mcp])

(def server
  (mcp/create-server
    {:transport {:type :stdio}
     :capabilities {:logging {}}}))  ; Enable logging
```

When enabled, the server will:
- Declare the `logging` capability during initialization
- Accept `logging/setLevel` requests from clients
- Allow sending log messages via `notifications/message`

## Sending Log Messages

Use the convenience functions from `mcp-clj.mcp-server.logging`:

```clojure
(require '[mcp-clj.mcp-server.logging :as logging])

;; Send error-level message
(logging/error server {:error "Connection failed" :host "localhost"}
               :logger "database")

;; Send info-level message
(logging/info server {:status "Server started"})

;; Send warning without logger
(logging/warn server {:msg "High memory usage"})
```

### Available Log Levels

The MCP protocol supports 8 RFC 5424 severity levels (ordered from most to least severe):

| Level | Function | Description | Example Use Case |
|-------|----------|-------------|------------------|
| emergency | `(logging/emergency ...)` | System is unusable | Complete system failure |
| alert | `(logging/alert ...)` | Action must be taken immediately | Data corruption detected |
| critical | `(logging/critical ...)` | Critical conditions | System component failures |
| error | `(logging/error ...)` | Error conditions | Operation failures |
| warning | `(logging/warn ...)` | Warning conditions | Deprecated feature usage |
| notice | `(logging/notice ...)` | Normal but significant events | Configuration changes |
| info | `(logging/info ...)` | General informational messages | Operation progress updates |
| debug | `(logging/debug ...)` | Detailed debugging information | Function entry/exit points |

### Generic Log Function

For dynamic log level selection:

```clojure
(logging/log-message server :error {:data "Something went wrong"}
                     :logger "my-component")
```

## Client Log Level Control

Clients can set their minimum log level using the `logging/setLevel` request. The server will only send messages at or above the client's threshold.

**Default behavior**: When a client hasn't set a log level, it defaults to `:error`, meaning it receives error, critical, alert, and emergency messages.

Example client request:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "logging/setLevel",
  "params": {
    "level": "warning"
  }
}
```

After this request, the client will receive warning, error, critical, alert, and emergency messages.

## Best Practices

### 1. Choose Appropriate Log Levels

- **debug**: Verbose information useful during development
- **info**: General operational messages
- **warning**: Potential issues that don't prevent operation
- **error**: Failures that need attention
- **critical/alert/emergency**: Severe failures requiring immediate action

### 2. Use Logger Names

Organize logs by component or subsystem:

```clojure
(logging/error server {:msg "Query timeout"} :logger "database")
(logging/info server {:msg "Request processed"} :logger "api")
```

### 3. Structure Your Data

Use maps with clear keys rather than concatenated strings:

```clojure
;; Good
(logging/error server
  {:error "Connection failed"
   :host "localhost"
   :port 5432
   :retry-count 3}
  :logger "database")

;; Less useful
(logging/error server
  "Connection to localhost:5432 failed after 3 retries"
  :logger "database")
```

## Security Considerations

**NEVER** include sensitive data in log messages:

❌ Avoid:
- Credentials or secrets
- Personal identifying information (PII)
- Internal system details that could aid attacks
- API keys or tokens

✅ Do:
```clojure
;; Sanitize before logging
(logging/error server
  {:error "Authentication failed"
   :username (mask-username username)  ; Mask sensitive parts
   :ip-address client-ip}
  :logger "auth")
```

## Difference from Internal Logging

| Feature | MCP Logging (`mcp-clj.mcp-server.logging`) | Internal Logging (`mcp-clj.log`) |
|---------|------------------------------------------|----------------------------------|
| Purpose | Send logs to MCP clients | Debug mcp-clj framework |
| Destination | MCP clients (Claude Desktop, etc.) | stderr |
| When to use | Application-level logging | Framework debugging |
| Audience | End users/client applications | mcp-clj developers |

## Example: Complete Server with Logging

```clojure
(ns my-app.server
  (:require
    [mcp-clj.mcp-server.core :as mcp]
    [mcp-clj.mcp-server.logging :as logging]))

(defn my-tool-implementation
  [args]
  (logging/info server {:msg "Tool invoked" :args args} :logger "tools")
  (try
    (let [result (do-something args)]
      (logging/debug server {:result result} :logger "tools")
      result)
    (catch Exception e
      (logging/error server
        {:error (.getMessage e)
         :args args}
        :logger "tools")
      (throw e))))

(def server
  (mcp/create-server
    {:transport {:type :stdio}
     :capabilities {:logging {}}
     :tools {"my-tool" {:name "my-tool"
                        :description "Example tool"
                        :inputSchema {:type "object"}
                        :implementation my-tool-implementation}}}))
```

## Troubleshooting

**Q: My log messages aren't appearing**  
A: Check that:
1. Logging capability is enabled (`:capabilities {:logging {}}`)
2. Message level meets or exceeds client's threshold
3. Client has called `initialized` after `initialize`

**Q: Can I send logs to specific clients?**  
A: Currently, log messages are sent to all connected clients (filtered by their individual log levels). Per-client targeting is a future enhancement.

**Q: What happens if I log before a client connects?**  
A: Messages are only sent to connected, initialized clients. Messages sent before client connection are not queued.
