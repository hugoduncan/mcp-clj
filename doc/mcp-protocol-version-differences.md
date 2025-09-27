# MCP Protocol Version Differences

This document summarizes the protocol-level differences between MCP specification versions, focusing on features that affect implementation of multi-version support. Transport-specific details are excluded.

## Version Overview

| Feature | 2024-11-05 | 2025-03-26 | 2025-06-18 |
|---------|------------|------------|------------|
| **Foundation** | JSON-RPC 2.0 | JSON-RPC 2.0 | JSON-RPC 2.0 |
| **JSON-RPC Batching** | ❌ | ✅ | ❌ (Removed) |
| **OAuth 2.1** | ❌ | ✅ | ✅ (Enhanced) |
| **Tool Annotations** | ❌ | ✅ | ✅ |
| **Audio Content** | ❌ | ✅ | ✅ |
| **Structured Content** | ❌ | ❌ | ✅ |
| **Resource Links** | ❌ | ❌ | ✅ |
| **Elicitation Capability** | ❌ | ❌ | ✅ |
| **Protocol Version Header** | Optional | Optional | **Required** |

## Core Protocol Features by Version

### 2024-11-05 (Foundation)

**Server Capabilities:**
- `tools` - Tool discovery and execution
- `prompts` - Prompt templates  
- `resources` - Resource access

**Client Capabilities:**
- `sampling` - Message generation requests
- `roots` - Workspace root directories

**Message Types:**
- Standard JSON-RPC 2.0 requests, responses, notifications
- Strict ID requirements: no null, no reuse within session

**Content Types:**
- `text` - Plain text content
- `image` - Base64-encoded image data
- `resource` - Embedded resource references

**Key Methods:**
- `initialize` - Capability negotiation
- `tools/list`, `tools/call` - Tool operations
- `prompts/list`, `prompts/get` - Prompt operations
- `resources/list`, `resources/read`, `resources/templates/list`, `resources/subscribe` - Resource operations
- `sampling/createMessage` - Message generation
- `notifications/tools/list_changed` - Dynamic tool updates

### 2025-03-26 (Major Enhancement)

**New Protocol Features:**
- **JSON-RPC Batching** - Support for batch requests/responses
- **OAuth 2.1 Framework** - Comprehensive authentication and authorization
- **Tool Annotations** - Behavior descriptions (read-only vs destructive operations)
- **Progress Notifications** - Enhanced with descriptive `message` field
- **Completions Capability** - Argument autocompletion for tools and prompts

**Enhanced Content Types:**
- `audio` - Audio data support (added to text/image)

**Security Enhancements:**
- Standardized OAuth 2.1 integration
- Access control mechanisms
- Authorization scope definitions

**Extended Capabilities:**
- Server: `logging` capability added
- Client: `completions` capability added

### 2025-06-18 (Current)

**Protocol Changes:**
- **Removed JSON-RPC Batching** - Simplified back to individual requests
- **Enhanced OAuth** - MCP servers classified as OAuth Resource Servers
- **Required Protocol Version Header** - `MCP-Protocol-Version` header mandatory
- **RFC 8707 Support** - Resource indicators required for clients

**New Features:**
- **Structured Content** - `structuredContent` field for JSON-based tool outputs
- **Resource Links** - Support in tool call results
- **Elicitation** - Capability for requesting additional user information
- **Enhanced Metadata** - `_meta` field added to interface types
- **Title Fields** - Human-friendly display names throughout

**Enhanced Capabilities Structure:**
- **Client**: `roots`, `sampling`, `elicitation`
- **Server**: `prompts`, `resources`, `tools`, `logging`
- **Sub-capabilities**: `listChanged`, `subscribe` explicitly defined

**Content Type Evolution:**
- `text`, `image`, `audio` (maintained)
- `resource` (enhanced with links)
- **New**: Structured content with JSON schema validation

## Breaking Changes Between Versions

### 2024-11-05 → 2025-03-26
- **Additive Only** - No breaking changes, only new optional features
- New capabilities must be negotiated during initialization
- Batching support is optional and detected via capability

### 2025-03-26 → 2025-06-18
- **JSON-RPC Batching Removed** - Implementations must not send batch requests
- **Protocol Version Header Required** - All messages must include version header
- **Enhanced OAuth Requirements** - Stricter OAuth 2.1 compliance needed
- **Capability Structure Changes** - Sub-capabilities now explicitly defined

### Cross-Version Compatibility
- **Message Format** - JSON-RPC 2.0 consistent across all versions
- **Core Methods** - `initialize`, `tools/*`, `prompts/*`, `resources/*` available in all
- **Content Types** - `text` and `image` supported across all versions
- **ID Requirements** - Consistent across versions (no null, no reuse)

## Version-Specific Protocol Behaviors

### Initialization Negotiation
**2024-11-05:**
```json
{
  "protocolVersion": "2024-11-05",
  "capabilities": {
    "tools": {},
    "prompts": {},
    "resources": {}
  }
}
```

**2025-03-26:**
```json
{
  "protocolVersion": "2025-03-26", 
  "capabilities": {
    "tools": {},
    "prompts": {},
    "resources": {},
    "logging": {}
  }
}
```

**2025-06-18:**
```json
{
  "protocolVersion": "2025-06-18",
  "capabilities": {
    "tools": {
      "listChanged": true
    },
    "prompts": {},
    "resources": {
      "subscribe": true
    },
    "logging": {}
  }
}
```

### Tool Call Responses

**2024-11-05 & 2025-03-26:**
```json
{
  "content": [
    {
      "type": "text", 
      "text": "Result content"
    }
  ],
  "isError": false
}
```

**2025-06-18:**
```json
{
  "content": [
    {
      "type": "text",
      "text": "Result content"
    }
  ],
  "structuredContent": {
    "type": "object",
    "properties": {...}
  },
  "isError": false
}
```

### Error Handling
- **Consistent** - JSON-RPC 2.0 error format across all versions
- **Enhanced in 2025-06-18** - Better error metadata and structured error content

## Implementation Considerations for Multi-Version Support

### Required Version Detection
- Parse `protocolVersion` from initialization request
- Validate against supported versions list
- Negotiate highest mutually supported version

### Feature Availability Checks
- **Batching**: Only available in 2025-03-26
- **Audio Content**: Available in 2025-03-26+
- **Structured Content**: Only available in 2025-06-18
- **Elicitation**: Only available in 2025-06-18
- **Resource Links**: Only available in 2025-06-18

### Capability Structure Handling
- **2024-11-05/2025-03-26**: Flat capability objects
- **2025-06-18**: Nested sub-capabilities (listChanged, subscribe)

### Content Type Filtering
- **2024-11-05**: Support text, image, resource only
- **2025-03-26**: Add audio support
- **2025-06-18**: Add structured content and resource links

### Header Requirements
- **2024-11-05/2025-03-26**: Optional protocol version header
- **2025-06-18**: Mandatory `MCP-Protocol-Version` header

This document provides the foundation for implementing robust multi-version MCP protocol support by understanding what features are available in each version and how they differ at the protocol level.