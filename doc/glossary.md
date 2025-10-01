# Project Glossary

**Base**: Entry point implementation that composes components into a deployable artifact (e.g., sse-server, stdio-server).

**Capabilities**: Feature flags exchanged during initialization indicating supported MCP features (tools, prompts, resources, sampling, roots, logging).

**Client Info**: Metadata about an MCP client including name, version, and optionally title, exchanged during initialization.

**Component**: Reusable functionality module with isolated dependencies in the polylith architecture (e.g., mcp-server, json-rpc, tools).

**Cursor**: Opaque string token representing a position in paginated result sets; clients must treat as opaque.

**EDN**: Extended Data Notation - Clojure's data format used internally by handlers before JSON conversion.

**Handler**: Function that processes JSON-RPC method calls by accepting EDN parameters and returning EDN responses.

**Implementation**: The actual function in a tool, prompt, or resource that executes when invoked by a client.

**In-Memory Transport**: Transport layer for testing that enables bidirectional client-server communication without external processes or network.

**Initialize**: First MCP protocol method called by client to establish session, negotiate protocol version, and exchange capabilities.

**Initialized**: Notification sent by client after receiving initialize response to confirm session establishment and readiness.

**inputSchema**: JSON Schema definition specifying required and optional parameters for a tool.

**JSON-RPC Client**: Component implementing JSON-RPC 2.0 client protocol with request/response handling and notification support.

**JSON-RPC Server**: Component implementing JSON-RPC 2.0 server protocol with automatic EDN/JSON conversion and handler dispatch.

**Lifecycle**: The three-phase connection flow: initialization (capability negotiation), operation (normal communication), and shutdown (graceful termination).

**listChanged**: Capability sub-flag indicating support for notifications when lists of tools, prompts, or resources change.

**MCP**: Model Context Protocol - Anthropic's protocol for enabling communication between AI assistants and context providers.

**Negotiation**: Process during initialization where client and server agree on protocol version to use for the session.

**Notification**: JSON-RPC message sent without expecting a response, used for events like "tools/list_changed" or "initialized".

**Pagination**: Cursor-based approach for retrieving large result sets in smaller chunks; servers determine page size.

**Polylith**: Architecture style organizing code into components, bases, and projects with isolated dependencies and clear boundaries.

**Prompt**: MCP feature that provides pre-defined message templates with parameters that can be filled in by clients.

**Protocol Version**: MCP specification version string (e.g., "2024-11-05", "2025-03-26") following YYYY-MM-DD format; negotiated during initialization.

**Registry**: Component managing dynamic collections of tools, prompts, or resources with support for add/remove and change notifications.

**Resource**: MCP feature that provides access to data sources like files or API endpoints with URI-based addressing.

**Roots**: Filesystem directories/files that clients expose to servers, defining operational boundaries and access scope.

**Sampling**: MCP capability allowing servers to request LLM completions from clients with human-in-the-loop approval.

**Server Info**: Metadata about an MCP server including name, version, and optionally title, sent during initialization.

**Session**: Client connection state including session ID, initialization status, client info, capabilities, and protocol version.

**SSE**: Server-Sent Events - HTTP-based transport using EventSource for server-to-client streaming with endpoint message posting.

**STDIO**: Standard Input/Output transport for MCP communication over stdin/stdout, commonly used with Claude Desktop.

**Subscribe**: Capability sub-flag for resources indicating support for receiving notifications when individual resource content changes.

**Tool**: MCP feature that exposes executable functionality with name, description, input schema, and implementation function.

**Transport**: Abstraction layer for communication between client and server (stdio, SSE, HTTP, in-memory).

**Version-Aware**: Code that adapts behavior based on negotiated protocol version to maintain compatibility across MCP specification versions.
