# MCP Client Tool Calling Design

## Overview

This document outlines the design for implementing tool calling functionality in the mcp-client component, following the MCP specification for client-side tool interaction.

## Specification Requirements

Based on the MCP specification, the client must support:

- **Discovery**: `tools/list` requests with pagination support
- **Invocation**: `tools/call` with tool name and arguments
- **Result handling**: Multiple content types (text, images, audio, resources) with `isError` flag
- **Security**: Input validation, timeouts, audit logging
- **Error management**: Both JSON-RPC protocol errors and tool execution errors

## Proposed API

### Core Functions

All functions are standalone and take the client as a parameter:

```clojure
(list-tools client)
;; Returns: {:tools [{:name "tool-name" :description "..." :inputSchema {...}}]}

(call-tool client tool-name arguments)
;; Returns: ToolResult record with content and error status

(available-tools? client)
;; Returns: boolean indicating if tools are available
```

### Data Structures

```clojure
(defrecord ToolResult [content isError])

;; Tool cache in client session
{:tools [{:name "example-tool"
          :description "Example tool description"
          :inputSchema {:type "object" :properties {...}}}]}
```

## Implementation Architecture

### Integration Points

- **Transport Layer**: Use existing `send-request!` in stdio transport for `tools/list` and `tools/call`
- **Session Management**: Extend session state to include tool-related information
- **Error Handling**: Leverage existing JSON-RPC error handling patterns

### Security Features

- **Input Validation**: Validate arguments against tool input schemas
- **Timeouts**: Configurable timeouts for tool execution
- **Audit Logging**: Log tool usage for monitoring

### Error Handling Strategy

1. **Protocol Errors**: JSON-RPC transport errors (network, malformed requests)
2. **Tool Execution Errors**: Server-side tool failures indicated by `isError: true`
3. **Timeout Errors**: Request timeout handling
4. **Validation Errors**: Schema validation failures on client side

## Implementation Plan

### Phase 1: Core Infrastructure
- Add tool result data structures
- Extend session management for tool state
- Implement basic `list-tools` function

### Phase 2: Tool Execution
- Implement `call-tool` function
- Add input validation against schemas
- Handle tool execution results and errors

### Phase 3: Enhanced Features
- Add tool caching mechanism
- Implement `available-tools?` helper
- Add comprehensive error handling and logging

## File Structure

New functionality will be added to:
- `components/mcp-client/src/mcp_clj/mcp_client/tools.clj` - Main tool calling API
- `components/mcp-client/test/mcp_clj/mcp_client/tools_test.clj` - Tests
- Enhanced session management in existing session.clj

## Testing Strategy

- Unit tests for each tool function
- Integration tests with mock server responses
- Error handling tests for various failure scenarios
- Schema validation tests