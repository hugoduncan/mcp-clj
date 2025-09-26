# Task States

[ ] - Planned/TODO
[x] - Completed
[!] - Blocked

Include REFINE to refine the spec

# Small

- [x] when starting a server, wait for the handlers to be set before
      starting to handle requests.  Change the default handlers to be
     `nil`, so we can distinguish between not set, and empty.

- [x] please write a clj-kondo hook to recognise

	  mcp-clj.mcp-server.http-transport-test/with-test-server

      See https://cljdoc.org/d/clj-kondo/clj-kondo/2025.09.22/doc/hooks
      for doc on hooks.

	  install the hook in @.clj-kondo/config.edn

- [ ] change tool calls to return a promise.  the caller can
      then decide blocking policy.

- [ ] when starting a server for an stdio client, make sure the servers stderr
      is forwarded to the client.

- [ ] change the interop wrap to add a protocol to hide the differences
      between Async and Sync in terms of lifecycle

# Medium

- [x] extend the interop to enable use of HTTP transport

- [x] I want to speed up tests
       - mark all tests that
           - start a mcp server
		   - start any external process
         with ^:integ metadata
	   - update kaocha tests.edn config to separate ^:integ tests and
         disable them by default.
       - update CLAUDE.md to describe this testing strategy

- [x] mcp-clj.mcp-client.core and mcp-clj.mcp-client.transport contain
      many conditionals that test the transport type. Pleas convert
      these to use a protocol. Put the protocol into a separate
      namespace in a new, polylith style, client-transport component.

- [x] Looking at mcp-clj.client-transport.protocol and its
      implementations in mcp-clj.client-transport.http and
      mcp-clj.client-transport.stdio, it seems that many of these
      protocol methods could be pushed down the stack onto the json rpc
      clients in mcp-clj.json-rpc.*-client.

- [x] refactor mcp-clj.mcp-client.core/create-client so that all http
      transport options are on a :http key and all stdio transport
      options are on a :stdio key.
	  - update mcp-clj.client-transport.factory/create-transport
	  - remove the redundant mcp-clj.mcp-client.core/create-transport
	  - Update tests accordingly

- [x] refactor mcp-clj.mcp-client.core so that create-client so that
      transport options are on a :transport key.  The value is a map
      with a :type field, and type specific options. The two types we
      currently have are :http and :stdio.
	  - update mcp-clj.client-transport.factory/create-transport
	  - Update tests accordingly

- [x] refactor mcp-clj.mcp-client.core so that transports are pluggable.
      Allow registration of transports, providing a keyword, for the
      :type field in the :transport map. and a factory function to
      instantiate the transport given the options.

- [x] refactor mcp-clj.mcp-server.core so that create-server has
      transport options that are on a :transport key.  The value is a map
      with a :type field, and type specific options. The types we
      currently have are :http, :sse and :stdio.
	  - should remove the need for determine-transport
	  - should simplify create-json-rpc-server
	  - Update tests accordingly

- [ ] refactor mcp-clj.mcp-server.core so that transports are pluggable.
      Allow registration of transports, providing a keyword, for the
      :type field in the :transport map. and a factory function to
      instantiate the transport given the options.

- [ ] I would like to be able to unit test the mcp client talking to
      the mcp server without any external processes.  Create a transport
	  that can be used in process to connect the two.


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

- [x] create a summary of the protocol (not transport) differences
      between the different mcp specifications ["2025-06-18"
      "2025-03-26" "2024-11-05"].
	  Protocols are specified at
	  https://github.com/modelcontextprotocol/modelcontextprotocol/tree/199754c8141b0b709f4a5f9caf38a708bf8552ef/docs/specification

- [x] support different mcp versions. REFINE
      - need conditionals for changes in spec between versions
	  - use @doc/mcp-protocol-version-differences.md as a reference for what to support
	  - don't add any major new features while doing this.
	  - list unsupported features in each version.

	  Implementation completed:
	  - Version-specific capabilities formatting (nested vs flat)
	  - Content type filtering per version (audio support, etc.)
	  - Header validation (MCP-Protocol-Version required in 2025-06-18)
	  - Server info formatting (title field support)
	  - Tool response formatting (structured content support)
	  - Comprehensive test coverage for all version-specific behaviors
	  - Utility functions for version comparison and feature detection


- [ ] proper capabilities handling
