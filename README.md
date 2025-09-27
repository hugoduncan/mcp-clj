# mcp-clj

An implementation of the Model-Channel Protocol (MCP) in Clojure,
designed to expose Clojure REPL functionality over an SSE transport.

## Project Description

mcp-clj is a Clojure implementation of the Model-Channel Protocol (MCP)
defined by Anthropic. It provides both client and server components for
MCP communication, with a specific focus on exposing Clojure REPL
functionality. The project aims to maintain compatibility with
Anthropic's MCP specification while providing a simple and reliable
implementation.

NOTE: this is still ALPHA.  Breaking changes can be expected.

## Usage

Add mcp-clj as a dependency to your project.

1. Add the mcp-project as a dependency:

```clojure
:deps {org.hugoduncan/mcp-clj
        {:git/url   "https://github.com/hugoduncan/mcp-clj"
         :git/sha   "replace with latest git sha"
         :deps/root "projects/server"}}
```

2. In the project, start the server:

```clojure
(require 'mcp-clj.mcp-server.core)
(def server (mcp-clj.mcp-server.core/create-server {:port 3001}))
```

This will start the server on port 3001. You can then connect to the
server using an MCP client.

## How this differs from clojure-mcp

This project differs from
[bhauman/clojure-mcp](https://github.com/bhauman/clojure-mcp) in its
approach to REPL integration:

- **Self-contained evaluation**: mcp-clj evaluates Clojure expressions
  directly within the server process itself, rather than connecting to
  an external nREPL server. This makes it simpler to set up but means it
  cannot connect to remote REPLs.

- **No nREPL dependency**: This implementation does not require or use
  nREPL connections. The server maintains its own Clojure runtime and
  evaluates expressions in that context.

- **HTTP transport support**: While it cannot connect to remote REPLs
  via nREPL, mcp-clj can run as an HTTP server, allowing remote clients
  to connect to it over the network.

- **No working directory changes**: The server operates in a fixed
  working directory and does not support changing it during runtime.

- **Trade-offs**: The self-contained approach means simpler deployment
  and no nREPL configuration, but you lose the ability to connect to
  existing running Clojure applications or remote development
  environments.

  - Much fewer tools

## Configuration

### Configuring Claude Desktop

To configure Claude Desktop to use mcp-clj, you need to use
[mcp-proxy](https://github.com/sparfenyuk/mcp-proxy).

In `claude_desktop_config.json`, add:

```json
    "mcp-proxy": {
      "command": "mcp-proxy",
      "args": [
        "http://localhost:3001/sse"
      ],
      "env": {
        "API_ACCESS_TOKEN": "ABC"
      }
    }
```

## Contributing

Contributions to mcp-clj are welcome! Please follow these steps to contribute:

1. Fork the repository.
2. Create a new branch for your feature or bugfix.
3. Make your changes and ensure all tests pass.
4. Submit a pull request with a detailed description of your changes.

## License

mcp-clj is licensed under the MIT License. See the [LICENSE](LICENSE) file for more details.
