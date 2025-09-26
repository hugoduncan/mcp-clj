# MCP Client Component

A Clojure implementation of an MCP (Model Context Protocol) client with pluggable transport support (HTTP and stdio) for the 2025-06-18 protocol version.

## Features

- **Pluggable Transports**: Support for HTTP and stdio transports with extensible transport registry
- **Session Management**: Proper state transitions (disconnected → initializing → ready)
- **Async API**: All operations use CompletableFuture for non-blocking behavior
- **Protocol Compliance**: Implements MCP 2025-06-18 initialization handshake
- **Clean Lifecycle**: Proper process management and resource cleanup

## Usage

### Basic Client Creation

```clojure
(require '[mcp-clj.mcp-client.core :as client])

;; Create client with stdio transport
(def stdio-client
  (client/create-client
   {:transport {:type :stdio
                :command "python"
                :args ["-m" "mcp_server" "--stdio"]
                :env {"PYTHONPATH" "/path/to/server"}}
    :client-info {:name "my-clojure-client"
                  :title "My Clojure MCP Client"
                  :version "1.0.0"}
    :capabilities {}}))

;; Create client with HTTP transport
(def http-client
  (client/create-client
   {:transport {:type :http
                :url "http://localhost:8080"
                :num-threads 2}
    :client-info {:name "my-clojure-client"
                  :title "My Clojure MCP Client"
                  :version "1.0.0"}
    :capabilities {}}))

;; Wait for ready state (with timeout) - defaults to 30 seconds
(client/wait-for-ready client 5000)

;; Check if ready
(client/client-ready? client)
;; => true

;; Get session information
(client/get-client-info client)
;; => {:state :ready, :protocol-version "2025-06-18", ...}

;; Clean up
(client/close! client)
```

### Transport Configuration

```clojure
;; Stdio transport configuration
{:transport {:type :stdio
             :command "uvx"
             :args ["mcp-server-git" "--repository" "/path/to/repo"]
             :env {"NODE_ENV" "production"}
             :cwd "/path/to/working/dir"}}

;; HTTP transport configuration
{:transport {:type :http
             :url "http://localhost:8080"
             :num-threads 2}}

;; Custom transport registration
(require '[mcp-clj.client-transport.factory :as transport-factory])

;; Register a custom transport type
(transport-factory/register-transport! :custom
  (fn [options]
    (create-custom-transport options)))

;; Use the custom transport
{:transport {:type :custom
             :custom-option "value"}}
```

### Session State Management

The client maintains session state through these transitions:

- `:disconnected` → `:initializing` → `:ready`
- Any state → `:error` (on failures)
- `:error` → `:disconnected` (for recovery)

```clojure
;; Check session state
(client/client-ready? client)   ; true if :ready
(client/client-error? client)   ; true if :error

;; Get detailed session info
(let [info (client/get-client-info client)]
  (println "State:" (:state info))
  (println "Server info:" (:server-info info))
  (println "Server capabilities:" (:server-capabilities info)))
```

## Architecture

### Components

- **`core.clj`**: Main client API and initialization protocol
- **`client-transport/`**: Pluggable transport system with HTTP and stdio implementations
- **`session.clj`**: Session state management with proper transitions

### Key Design Decisions

- **Automatic Initialization**: Clients automatically begin initialization upon creation
- **Future-based API**: Uses CompletableFuture for non-blocking initialization
- **Minimal capabilities**: Starts with empty capabilities for initialization-only
- **Process lifecycle**: Simple launch/terminate (no auto-restart)
- **Pluggable transports**: Registry-based transport system supporting HTTP and stdio
- **Process management**: Uses `clojure.java.process` for robust process handling

## Testing

### Unit Tests

```bash
clj -M:test -e "(require 'mcp-clj.mcp-client.session-test) (clojure.test/run-tests 'mcp-clj.mcp-client.session-test)"
```

### Integration Tests

The integration test demonstrates end-to-end functionality:

1. **`integration_test.clj`**: Full server process integration
   - Starts real MCP server process
   - Connects client and initializes session
   - Verifies session details and server capabilities
   - Tests error handling and multiple clients

2. **`simple_integration_test.clj`**: Simplified integration tests
   - In-process server simulation
   - State transition verification
   - Configuration validation

3. **`smoke_test.clj`**: Basic functionality verification
   - Client creation and cleanup
   - Session info retrieval
   - State predicates
   - Transport configuration

### Running Integration Tests

```bash
# Run integration tests (requires mcp-server component)
clj -M:integration -e "(require 'mcp-clj.mcp-client.integration-test) (clojure.test/run-tests 'mcp-clj.mcp-client.integration-test)"

# Run smoke tests (no external dependencies)
clj -M:test -e "(require 'mcp-clj.mcp-client.smoke-test) (clojure.test/run-tests 'mcp-clj.mcp-client.smoke-test)"
```

## Protocol Implementation

### Initialization Sequence

1. **Initialize Request**: Client sends initialize with protocol version and capabilities
2. **Initialize Response**: Server responds with its info and capabilities
3. **Initialized Notification**: Client sends initialized notification
4. **Ready State**: Session transitions to ready for normal operations

### Message Flow

```
Client                           Server
  |                               |
  |  {"method": "initialize", ... }|
  |------------------------------>|
  |                               |
  |  {"result": {...}}            |
  |<------------------------------|
  |                               |
  |  {"method": "notifications/   |
  |   initialized", ...}          |
  |------------------------------>|
  |                               |
  |  [ready for requests]         |
```

### Error Handling

- **Connection failures**: Transition to error state with details
- **Protocol mismatches**: Validate server protocol version
- **Timeouts**: Configurable request timeouts (default 30s)
- **Process crashes**: Detect via transport-alive checks

## Dependencies

- `poly/json-rpc`: JSON-RPC 2.0 implementation and executors
- `poly/log`: Logging infrastructure
- `poly/mcp-server`: For integration testing only

## Future Enhancements

- Tool calling functionality
- Prompt and resource operations
- Connection retry and auto-recovery
- Additional transport types (WebSocket, SSE)
- Enhanced capability negotiation
