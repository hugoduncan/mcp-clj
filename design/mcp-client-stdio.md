# MCP Client with stdio Transport Design

## Overview

This document outlines the design for implementing a Model Context Protocol (MCP) client with stdio transport for the 2025-06-18 protocol version. The client will enable Clojure applications to connect to MCP servers and leverage their tools, prompts, and resources.

## Current State Analysis

### Existing Components
- **components/json-rpc/**: JSON-RPC 2.0 implementation with stdio server support
- **components/mcp-server/**: MCP server implementation for 2025-06-18 protocol
- **Key reusable code**:
  - `json_protocol.clj`: JSON-RPC 2.0 utilities (request/response formatting, validation)
  - `stdio_server.clj`: stdio I/O handling and JSON parsing patterns
  - `executor.clj`: Async request handling

### Protocol Version
Current implementation already targets **2025-06-18** protocol version, so no version migration is needed.

## Requirements

### Functional Requirements
1. **Initialization**: Client initiates MCP connection with `initialize` request
2. **Capability Negotiation**: Declare client capabilities and validate server response
3. **Lifecycle Management**: Handle initialization sequence and connection state
4. **Transport**: Use stdio for server communication
5. **Error Handling**: Proper JSON-RPC error handling and timeouts

### Client Initialization Flow
```
Client                           Server
  |                               |
  |  initialize request           |
  |------------------------------>|
  |                               |
  |  initialize response          |
  |<------------------------------|
  |                               |
  |  initialized notification     |
  |------------------------------>|
  |                               |
  |  [ready for requests]         |
```

## Design

### Component Structure
Create new component: **components/mcp-client/**
```
components/mcp-client/
├── deps.edn
├── src/mcp_clj/mcp_client/
│   ├── core.clj        # Main client API
│   ├── stdio.clj       # stdio transport implementation  
│   ├── session.clj     # Session state management
│   └── lifecycle.clj   # Initialization protocol
└── test/mcp_clj/mcp_client/
    ├── core_test.clj
    ├── stdio_test.clj
    └── lifecycle_test.clj
```

### Key Classes and Functions

#### `mcp_clj.mcp_client.core`
```clojure
(defrecord MCPClient [transport session-state])

(defn create-client
  "Create MCP client with stdio transport"
  [{:keys [server-command client-info capabilities]}])

(defn initialize!
  "Initialize MCP session with server"
  [client])

(defn call-tool
  "Call a server tool"
  [client tool-name arguments])

(defn list-tools
  "List available tools from server" 
  [client])

(defn get-prompt
  "Get a prompt from server"
  [client prompt-name arguments])

(defn read-resource
  "Read a resource from server"
  [client resource-uri])

(defn close!
  "Close client connection"
  [client])
```

#### `mcp_clj.mcp_client.stdio`
```clojure
(defrecord StdioTransport [process in out])

(defn create-transport
  "Create stdio transport by launching server process"
  [server-command])

(defn send-request!
  "Send JSON-RPC request and return response future"
  [transport method params])

(defn send-notification!
  "Send JSON-RPC notification (no response expected)"
  [transport method params])

(defn close!
  "Close transport and terminate server process"
  [transport])
```

#### `mcp_clj.mcp_client.session`
```clojure
(defrecord Session [state capabilities server-info pending-requests])

(def session-states #{:disconnected :initializing :ready :error})

(defn create-session
  "Create new session in disconnected state"
  [client-capabilities])

(defn transition-state!
  "Transition session to new state"
  [session new-state])
```

### Reusable Code Adaptation

#### From `json-rpc/stdio_server.clj`
- **JSON I/O functions**: Adapt `read-json` and `write-json!` for client use
- **Process management**: Create client-side process launching for server
- **Error handling patterns**: Reuse timeout and exception handling

#### From `json-rpc/json_protocol.clj`  
- **Message formatting**: Reuse `json-rpc-result`, `json-rpc-notification` functions
- **Request validation**: Adapt validation for client requests
- **Error codes**: Use existing JSON-RPC error constants

#### From `mcp-server/core.clj`
- **Protocol constants**: Reuse protocol version definitions
- **Message structure patterns**: Adapt handler patterns for client responses

### Client-Specific Requirements

#### Initialization Message
```clojure
{:jsonrpc "2.0"
 :id 1
 :method "initialize"
 :params {:protocolVersion "2025-06-18"
          :capabilities {}  ; Start with minimal capabilities
          :clientInfo {:name "mcp-clj"
                      :title "MCP Clojure Client"
                      :version "0.1.0"}}}
```

#### State Management
- Track initialization state (disconnected → initializing → ready)  
- Maintain server capabilities from initialization response
- Handle pending request/response correlation via request IDs
- Support connection retry and error recovery

#### Process Management
- Launch server subprocess with stdio pipes
- Handle server process termination
- Implement graceful shutdown sequence

## Implementation Plan

### Phase 1: Basic Transport
1. Create `components/mcp-client/` structure
2. Implement `StdioTransport` with process launching
3. Adapt JSON I/O from existing stdio server code
4. Basic request/response correlation

### Phase 2: Session Management  
1. Implement `Session` record and state transitions
2. Create initialization protocol handler
3. Add capability negotiation logic
4. Error handling and validation

### Phase 3: Client API
1. Implement main `MCPClient` API
2. Add tool calling functionality
3. Add prompt and resource operations
4. Connection lifecycle management

### Phase 4: Testing & Integration
1. Unit tests for all components
2. Integration tests with actual MCP servers
3. Error handling and edge case testing
4. Documentation and examples

## Design Decisions

1. **Transport Configuration**: Client accepts `:transport` option key similar to mcp-server
2. **Capabilities**: Start with minimal/no capabilities for initialization-only implementation
3. **Error Recovery**: Deferred for future implementation
4. **API Style**: Async-only using executors from json-rpc component
5. **Process Lifecycle**: No auto-restart for now - simple launch and terminate

## Dependencies

### New Dependencies
```clojure
;; In components/mcp-client/deps.edn
{:deps {poly/json-rpc {:local/root "../../components/json-rpc"}
        poly/log {:local/root "../../components/log"}}}
```

### Test Dependencies  
Reuse existing test infrastructure from other components.

## Success Criteria

1. Client can successfully initialize with MCP servers
2. Can call server tools and receive responses  
3. Proper error handling for network and protocol errors
4. Clean process lifecycle management
5. Full test coverage with both unit and integration tests
6. Clear API documentation with usage examples