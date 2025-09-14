# Task States

[ ] - Planned/TODO
[x] - Completed
[!] - Blocked

Include REFINE to refine the spec

# Small

- [x] when starting a server, wait for the handlers to be set before
      starting to handle requests.  Change the default handlers to be
     `nil`, so we can distinguish between not set, and empty.

- [ ] change tool calls to return a promise.  the caller can
      then decide blocking policy.

- [ ] when starting a server for an stdio client, make sure the servers stderr
      is forwarded to the client.

- [ ] change the interop wrap to add a protocol to hide the differences
      between Async and Sync in terms of lifecycle

# Medium

- [x] extend the interop to enable use of HTTP transport

- [ ] extend the interop to enable use of SSE transport ? not sure this
      is worth doing still

# Large

- [x] proper mcp version negotiation. REFINE
      See @design/lifecycle.mdx

- [X] I think we should use the offical Java SDK in tests as a know good
      implementation of the protocol.  When we test our mcp server we should
	  use the SDK as a client.  When we test our client, we should use the
	  Java SDK as a server   .

      - Please look at the official MCP Java SDK at
        https://modelcontextprotocol.io/sdk/java/mcp-overview.

      - we should start by making a clojure api to create clients and
        servers using the SDK, providing just the functionality we need
        for testing.

- [x] examine the (deprecated) SSE implementation, and the new protocol spec for HTTP.
       - summarise the differences
	   - design, plan and implement a new HTTP server following the
         protocol spec (do not replace the SSE server - that stays as it
         is)

- [x]  add support for the new protocol spec for HTTP to the
       mcp-clj.mcp-client.core/create-client.  We just added support the
       HTTP transport in the server.

- [ ] support different mcp versions properly. REFINE
      - need conditionals for changes in spec between versions


- [ ] proper capabilities handling
