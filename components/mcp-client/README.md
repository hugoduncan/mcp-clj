# MCP Client Component

A Clojure implementation of an MCP (Model Context Protocol) client with stdio transport support for the 2025-06-18 protocol version.

## Features

- **Stdio Transport**: Launch and communicate with MCP servers via stdin/stdout
- **Session Management**: Proper state transitions (disconnected → initializing → ready)
- **Async API**: All operations use CompletableFuture for non-blocking behavior
- **Protocol Compliance**: Implements MCP 2025-06-18 initialization handshake
- **Clean Lifecycle**: Proper process management and resource cleanup

## Usage

### Basic Client Creation

```clojure
(require '[mcp-clj.mcp-client.core :as client])

;; Create client with stdio transport
(def client
  (client/create-client
   {:transport {:type :stdio 
                :command ["python", "-m", "mcp_server", "--stdio"]}
    :client-info {:name "my-clojure-client"
                  :title "My Clojure MCP Client"
                  :version "1.0.0"}
    :capabilities {}}))

;; Initialize connection
(client/initialize! client)

;; Wait for ready state (with timeout)
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
;; Map-style configuration (recommended)
{:transport {:type :stdio 
             :command ["uvx", "mcp-server-git", "--repository", "/path/to/repo"]}}

;; Vector-style configuration (backward compatibility)
{:transport ["python", "-m", "mcp_server", "--stdio"]}
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
- **`stdio.clj`**: Stdio transport implementation with process management
- **`session.clj`**: Session state management with proper transitions

### Key Design Decisions

- **Async-only API**: Uses executors from json-rpc component
- **Minimal capabilities**: Starts with empty capabilities for initialization-only
- **Process lifecycle**: Simple launch/terminate (no auto-restart)
- **Transport abstraction**: Supports `:transport` option key like mcp-server

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
- Additional transport types (HTTP, WebSocket)
- Enhanced capability negotiation
