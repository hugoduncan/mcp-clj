# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Architecture Overview

This is a Clojure implementation of the Model Context Protocol (MCP) designed as a polylith-style modular architecture with component-based organization:

- **Component Architecture**: Each major functionality is isolated in `components/` with its own `deps.edn`, src, and test directories
- **Projects Structure**: The `projects/server/` provides a deployable server artifact that composes the components
- **Namespace Convention**: All code uses the `mcp-clj` top-level namespace as specified in `design/namespaces.md`

### Core Components

- `json-rpc/` - JSON-RPC 2.0 server implementation with automatic EDN/JSON conversion
- `mcp-server/` - MCP protocol implementation with tools, prompts, and resources support
- `http-server/` - HTTP adapter for serving JSON-RPC over HTTP
- `http/` - HTTP client utilities
- `log/` - Logging infrastructure

### Key Design Patterns

- **Handler Functions**: JSON-RPC handlers work with native EDN data structures; the server handles JSON conversion automatically
- **Registry Pattern**: Tools, prompts, and resources are managed through registry components that support dynamic updates with change notifications
- **Session Management**: MCP server maintains client sessions with initialization state and capabilities
- **Protocol Compliance**: Implements MCP specification version "2024-11-05" with proper version negotiation

## Common Development Commands

### Testing

This project uses a two-tier testing strategy to optimize development speed:

**Unit Tests (Default - Fast)**
- Run by default with `clj -M:kaocha:dev:test`
- Exclude integration tests (marked with `^:integ` metadata)
- Test pure functions and isolated components
- Typically complete in seconds

**Integration Tests (Slower)**
- Run with `clj -M:kaocha:dev:test --focus :integration`
- Include tests marked with `^:integ` metadata
- Start actual servers, external processes, or cross-process communication
- Examples: HTTP server tests, MCP client-server integration, Java SDK interop

```bash
# Run only fast unit tests (default)
clj -M:kaocha:dev:test

# Run only integration tests (slower)
clj -M:kaocha:dev:test --focus :integration

# Run all tests (unit + integration)
clj -M:kaocha:dev:test --focus :unit :integration

# Run tests with coverage (uncomment cloverage plugin in tests.edn)
clj -M:kaocha:dev:test --plugin kaocha.plugin/cloverage
```

**Test Classification Guidelines:**
- Mark tests with `^:integ` if they:
  - Start MCP servers or HTTP servers
  - Launch external processes or subprocesses
  - Use `with-test-server`, `with-http-test-env`, or similar macros
  - Test cross-process communication
- Keep unit tests fast and focused on isolated functionality

```clojure
;; Run tests from REPL after code changes
(require 'my.test.namespace :reload)
(clojure.test/run-tests 'my.test.namespace)

;; Run specific test
(require 'mcp-clj.mcp-server.version-test :reload)
(clojure.test/run-tests 'mcp-clj.mcp-server.version-test)
```

### Development REPL
```bash
# Start development REPL with all components loaded
clj -M:dev

# In REPL, after adding new dependencies to deps.edn:
(sync-deps)  ; Load new dependencies without restart
```

### Server Usage
```clojure
;; Start SSE server
(require 'mcp-clj.mcp-server.core)
(def server (mcp-clj.mcp-server.core/create-server {:port 3001}))

;; Stop server
((:stop server))
```

## Project Structure Specifics

### Component Dependencies
- Each component in `components/` has local dependencies on other components via `:local/root` references
- The `projects/server/` aggregates all components for deployment
- Dependencies are managed through poly aliases (e.g., `poly/json-rpc`, `poly/mcp-server`)

### Test Organization
- Tests are co-located with components in `test/` directories
- Test configuration in `tests.edn` defines separate test suites:
  - `:unit` - Fast unit tests (excludes `^:integ` metadata, runs by default)
  - `:integration` - Slower integration tests (focuses on `^:integ` metadata)
- Uses Kaocha test runner with plugins for randomization, filtering, and profiling
- Integration tests marked with `^:integ` metadata for server startup or external processes

### MCP Protocol Integration
- Implements server-side MCP with support for tools, prompts, and resources
- Uses SSE (Server-Sent Events) transport for real-time communication
- Requires mcp-proxy for Claude Desktop integration (see `claude_descktop_config.json`)

### JSON-RPC Architecture
- Server creates handler maps that dispatch to EDN-returning functions
- Automatic bidirectional JSON/EDN conversion with support for Clojure data types
- Follows JSON-RPC 2.0 specification with proper error handling

## Important Files and Configurations

- `tests.edn` - Kaocha test configuration with component paths
- `dado.edn` - AI provider configurations (contains API keys - handle with care)
- `design/project-scope.md` - Project boundaries and success criteria
- `doc/adr/001-json-rpc-server.md` - JSON-RPC server design decisions
- `package.json` - Node.js dependencies for mcp-proxy integration

## Development Notes

- Always write or update tests before testing new or changed code
- Use 2-space indentation consistently
- Follow the Clojure style guide for naming and documentation
- All public functions should have docstrings with parameter descriptions
- Prefer complete, unabbreviated names using kebab-case
- Component isolation: changes to one component should not require changes to others unless interfaces change

- mcp protocol specification is at https://github.com/modelcontextprotocol/modelcontextprotocol/tree/main/docs/specification
