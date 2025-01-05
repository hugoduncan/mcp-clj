# ADR 001: JSON-RPC Server Design

## Status
Proposed

## Context
The project requires a JSON-RPC server component that can:
- Support the MCP protocol implementation
- Handle extensible message dispatch
- Be managed as a first-class server object
- Support clean shutdown
- Handle EDN/JSON conversion transparently

## Decision

### Server Interface

The server will be implemented as a pure function that creates a server instance:

```clojure
(create-server config) -> {:server server-object
                          :stop   (fn [] ...)}
```

Configuration parameters:
- `:port` - Required. Port number to listen on
- `:handlers` - Required. Map of method names to handler functions

Handler function signature:
```clojure
(handler-fn params) -> edn-response

;; Example handler returning EDN data:
(defn example-handler [params]
  {:result {:status :ok
            :data   [1 2 3]
            :meta   {:timestamp #inst "2024"}}})  ;; EDN data structures
```

Server will:
1. Parse incoming JSON into EDN before passing to handler
2. Convert handler's EDN response to JSON before sending to client
3. Properly handle EDN-specific data types (e.g. keywords, dates, symbols)

### Core Functionality

1. Server Management
   - Server creation returns a map containing the server object and stop function
   - Stop function cleanly shuts down the server and releases resources
   - Server status can be queried through the server object

2. Request Handling
   - Validates incoming JSON-RPC 2.0 message format
   - Converts JSON request to EDN before dispatch
   - Dispatches requests to registered handlers
   - Converts EDN response to JSON
   - Returns properly formatted JSON-RPC 2.0 responses
   - Handles batch requests as per JSON-RPC 2.0 spec

3. Error Handling
   - Standard JSON-RPC 2.0 error responses
   - Invalid requests return -32600 error
   - Method not found returns -32601 error
   - Invalid params return -32602 error
   - Internal errors return -32603 error
   - Parse errors return -32700 error
   - JSON conversion errors return -32603 error

### Example Usage

```clojure
;; Create server with handlers returning EDN
(def server (create-server
             {:port 8080
              :handlers {"echo" (fn [params]
                                {:result params})  ; EDN map returned
                        "get-config" (fn [params]
                                     {:result {:enabled? true
                                              :features [:a :b :c]
                                              :updated-at #inst "2024"}})}}))

;; Stop server when done
((:stop server))
```

## Consequences

### Positive
- Clean separation of server lifecycle management
- Extensible handler mechanism
- First-class server objects
- Standard JSON-RPC 2.0 compliance
- Simple configuration
- Native EDN support for handlers
- Automatic JSON/EDN conversion

### Negative
- Limited to TCP/IP transport
- Synchronous handler execution model
- Single port per server instance
- Overhead from JSON/EDN conversions

### Neutral
- Stateless request handling
- No built-in authentication/authorization
- No persistent connections
- JSON serialization overhead for each request

## Related Documents
- [JSON-RPC 2.0 Specification](https://www.jsonrpc.org/specification)
- Project Scope Document

## Notes
- This component focuses solely on JSON-RPC server functionality
- Security features beyond basic protocol requirements are out of scope
- Performance optimization is not a primary concern for initial implementation
- Handlers work with native EDN data, server handles JSON conversion
