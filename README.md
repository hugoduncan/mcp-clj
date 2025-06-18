# mcp-clj

An implementation of the Model Context Protocol (MCP) in Clojure,
designed to expose Clojure REPL functionality over an SSE transport.

## Project Description

mcp-clj is a Clojure implementation of the Model Context Protocol (MCP)
defined by Anthropic. It provides both client and server components for
MCP communication, with a specific focus on exposing Clojure REPL
functionality. The project aims to maintain compatibility with
Anthropic's MCP specification while providing a simple and reliable
implementation.

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
