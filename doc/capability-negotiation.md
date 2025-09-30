# MCP Capability Negotiation

## Overview

Capability negotiation is a dynamic process in the Model Context
Protocol (MCP) where clients and servers explicitly declare their
supported features during session initialization. This negotiation
determines what protocol features and primitives are available for the
session.

## Key Characteristics

### Server Capabilities
Servers can declare support for:
- **Resource subscriptions** - Ability to send notifications when resources change
- **Tool support** - Availability of executable tools/functions
- **Prompt templates** - Pre-defined prompt templates for common use cases

### Client Capabilities
Clients can declare support for:
- **Sampling support** - Ability to handle sampling requests
- **Notification handling** - Processing of server-sent notifications

## Negotiation Requirements

- **Mutual Respect**: Both parties must respect declared capabilities throughout the session
- **Feature Gating**: Specific protocol features are only usable if explicitly declared by the appropriate party
- **Extensibility**: Additional capabilities can be negotiated through protocol extensions

## Practical Implications

The capability negotiation ensures that:

1. **Tool Invocation** - Only possible if the server declares tool capabilities
2. **Resource Subscriptions** - Notification delivery requires server's subscription support
3. **Sampling** - Requires explicit client-side capability declaration
4. **Clear Boundaries** - Both sides understand exactly what functionality is available

## Benefits

- **Flexibility** - Systems can evolve and add features progressively
- **Clarity** - Clear understanding of supported functionality prevents protocol errors
- **Extensibility** - Allows for future protocol enhancements while maintaining compatibility
