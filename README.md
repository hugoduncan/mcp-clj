# mcp-clj

A Clojure implementation of the Model Context Protocol (MCP) with minimal dependencies and self-contained Clojure REPL integration.

## Quick Start

```bash
# Add to deps.edn
{:deps {org.hugoduncan/mcp-clj
        {:git/url   "https://github.com/hugoduncan/mcp-clj"
         :git/sha   "latest-commit-sha"
         :deps/root "projects/server"}}}
```

```clojure
;; Start MCP server
(require 'mcp-clj.mcp-server.core)
(def server (mcp-clj.mcp-server.core/create-server {:transport {:type :stdio}}))

;; Server exposes tools like clj-eval and ls
;; Connect with Claude Desktop via stdio transport (see Claude Desktop Setup)
```

**Jump to:** [Installation](#installation) • [Client Usage](#client-usage) • [Claude Code Setup](#claude-code-setup) • [Claude Desktop Setup](#claude-desktop-setup) • [Development](#development)

## What & Why

mcp-clj provides both **MCP server** and **client** implementations in Clojure:

- **Self-contained REPL**: Evaluates Clojure directly in the server process (no nREPL dependency)
- **Low dependencies**: Only requires `org.clojure/data.json`
- **Multiple transports**: SSE (HTTP), stdio, and in-memory for testing
- **Built-in tools**: Clojure evaluation (`clj-eval`) and file listing (`ls`) with gitignore support
- **MCP client**: Connect to other MCP servers from Clojure

**When to use mcp-clj:**
- Expose Clojure REPL functionality to Claude Desktop or other MCP clients
- Build Clojure applications that consume MCP services
- Simple deployment without external REPL dependencies

**Trade-offs vs clojure-mcp:**
- ✅ Simpler setup, self-contained evaluation
- ❌ Cannot connect to existing remote REPLs
- ❌ Fewer built-in tools

## Installation

### Git Dependencies (Recommended)

```clojure
;; deps.edn
{:deps {org.hugoduncan/mcp-clj
        {:git/url   "https://github.com/hugoduncan/mcp-clj"
         :git/sha   "latest-commit-sha"  ; Replace with actual latest SHA
         :deps/root "projects/server"}}}
```

### CLI Usage

```bash
# Clone and run directly
git clone https://github.com/hugoduncan/mcp-clj
cd mcp-clj

# Start stdio server (recommended for Claude Desktop)
clj -M:stdio-server

# Start SSE server (HTTP) on port 3001 (default)
clj -M:sse-server

# Start SSE server on custom port
clj -M:sse-server --port 8080
```

## Server Usage

### Basic Server

```clojure
(require 'mcp-clj.mcp-server.core)

;; Stdio server (recommended for Claude Desktop)
(def server (mcp-clj.mcp-server.core/create-server
             {:transport {:type :stdio}}))

;; SSE server (for HTTP-based clients)
(def server (mcp-clj.mcp-server.core/create-server
             {:transport {:type :sse :port 3001}}))

;; Stop server
((:stop server))
```

### Custom Tools

```clojure
(def echo-tool
  {:name "echo"
   :description "Echo the input text"
   :inputSchema {:type "object"
                 :properties {"text" {:type "string"}}
                 :required ["text"]}
   :implementation (fn [{:keys [text]}]
                     {:content [{:type "text" :text text}]
                      :isError false})})

;; Server with custom tools
(def server (mcp-clj.mcp-server.core/create-server
             {:transport {:type :sse :port 3001}
              :tools {"echo" echo-tool}}))

;; Add tools dynamically
(mcp-clj.mcp-server.core/add-tool! server echo-tool)
```

### Built-in Tools

**clj-eval**: Evaluates Clojure expressions
```json
{"name": "clj-eval", "arguments": {"code": "(+ 1 2 3)"}}
// Returns: "6"
```

**ls**: Lists files with gitignore support
```json
{"name": "ls", "arguments": {"path": "src", "max-depth": 2, "max-files": 50}}
// Returns: {"files": [...], "truncated": false, "total-files": 12}
```

## Client Usage

Connect to other MCP servers from Clojure:

```clojure
(require 'mcp-clj.mcp-client.core)

;; Connect to stdio MCP server
(def client (mcp-clj.mcp-client.core/create-client
             {:transport {:type :stdio
                          :command "clojure"
                          :args ["-M:stdio-server"]}
              :client-info {:name "my-client" :version "1.0.0"}}))

;; Wait for connection
(mcp-clj.mcp-client.core/wait-for-ready client)

;; List available tools
(mcp-clj.mcp-client.core/list-tools client)
;; => {:tools [{:name "clj-eval" :description "..." :inputSchema {...}}]}

;; Call a tool
@(mcp-clj.mcp-client.core/call-tool client "clj-eval" {:code "(* 6 7)"})
;; => {:content [{:type "text" :text "42"}] :isError false}

;; Cleanup
(.close client)
```

## Claude Code Setup

Claude Code can connect to mcp-clj servers using the stdio transport.

### 1. Add mcp-clj to your project

In your `deps.edn`:

```clojure
{:aliases
 {:mcp {:extra-deps {org.hugoduncan/mcp-clj
        {:git/url   "https://github.com/hugoduncan/mcp-clj"
         :git/sha   "latest-commit-sha"
         :deps/root "projects/server"}}
 :main-opts ["-m" "mcp-clj.stdio-server.main"]}}}
```

### 2. Configure Claude Code

Add the MCP server using Claude Code's CLI:

```bash
claude mcp add mcp-clj clojure -M:mcp
```

### 3. Test the connection

```bash
# Verify the MCP server is configured and accessible
claude mcp list
```

You should see `mcp-clj` in the list of available servers. Claude Code
will now have access to `clj-eval` and `ls` tools within your project
context.

## Claude Desktop Setup

Claude Desktop can connect directly to mcp-clj using the stdio transport
without requiring additional proxy tools.

### 1. Add mcp-clj to your project

In your `deps.edn`:

```clojure
{:deps {org.hugoduncan/mcp-clj
        {:git/url   "https://github.com/hugoduncan/mcp-clj"
         :git/sha   "latest-commit-sha"
         :deps/root "projects/server"}}

 :aliases
 {:mcp {:main-opts ["-m" "mcp-clj.stdio-server.main"]}}}
```

### 2. Configure Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "mcp-clj": {
      "command": "/opt/homebrew/bin/bash",
      "args": [
        "-c",
        "cd /path/to/your/project && clojure -M:mcp"
      ],
      "env": {
        "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
      }
    }
  }
}
```

**Note**: Replace `/path/to/your/project` with your actual project directory and adjust the bash path if needed (`which bash` to find yours).

### 3. Restart Claude Desktop

Claude will now have access to `clj-eval` and `ls` tools running in your project's context.

## Development

### Setup

```bash
git clone https://github.com/hugoduncan/mcp-clj
cd mcp-clj

# Start REPL with all components
clj -M:dev
```

### Testing

```bash
# Fast unit tests (default)
clj -M:kaocha:dev:test

# Integration tests (starts servers)
clj -M:kaocha:dev:test --focus :integration

# All tests
clj -M:kaocha:dev:test --focus :unit :integration

# Specific namespace
clj -M:kaocha:dev:test --focus mcp-clj.mcp-server.core-test
```

### REPL Development

```clojure
;; After adding dependencies to deps.edn
(require 'clojure.repl.deps)
(clojure.repl.deps/sync-deps)

;; Reload namespace
(require 'my.namespace :reload)

;; Run tests
(require 'clojure.test)
(clojure.test/run-tests 'mcp-clj.mcp-server.core-test)
```

## Architecture

mcp-clj uses a **polylith-style architecture** with component-based organization:

- **Components** (`components/`) - Reusable functionality (mcp-server, mcp-client, json-rpc, tools, etc.)
- **Bases** (`bases/`) - Entry points (sse-server, stdio-server)
- **Projects** (`projects/server/`) - Deployable artifacts

### Key Components

- `mcp-server/` - MCP protocol server with tools, prompts, resources
- `mcp-client/` - MCP protocol client for connecting to servers
- `json-rpc/` - JSON-RPC 2.0 with automatic EDN/JSON conversion
- `tools/` - Built-in MCP tools (clj-eval, ls)
- `*-transport/` - Multiple transport layers (SSE, stdio, HTTP, in-memory)

All components use the `mcp-clj` namespace and follow JSON-RPC 2.0 with MCP protocol version `2024-11-05`.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make changes and ensure tests pass: `clj -M:kaocha:dev:test`
4. Submit a pull request

## License

MIT License. See [LICENSE](LICENSE) file for details.
