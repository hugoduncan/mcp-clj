# ADR 002: Migration to Cheshire JSON Library

## Status
Accepted (Implemented)

## Context

The project initially used `clojure.data.json` for JSON parsing and generation. As the project evolved, several factors motivated a migration to the `cheshire` library:

1. **Performance**: Cheshire is approximately 2x faster than clojure.data.json according to published benchmarks
2. **Babashka Compatibility**: Cheshire is built into Babashka (v6.1.0 in Babashka 1.12.209), while clojure.data.json is not
3. **Feature Set**: Cheshire provides additional capabilities including streaming APIs, pretty-printing, and SMILE format support
4. **Ecosystem Adoption**: Cheshire has broader adoption in the Clojure ecosystem

## Decision

Migrate from `clojure.data.json` to `cheshire` as the JSON library, with the following implementation strategy:

### 1. Centralized JSON Component

Create a dedicated `json` component (`components/json/`) that encapsulates all JSON operations behind a simple API:

```clojure
(require '[mcp-clj.json :as json])

(json/parse json-string)   ; Parse JSON to EDN
(json/write edn-data)       ; Convert EDN to JSON
```

This centralization provides:
- Single point of control for JSON behavior
- Easier future migrations if needed
- Consistent JSON handling across all transport layers (stdio, HTTP, SSE)

### 2. Normalization Layer

Cheshire has two behavioral differences from the expected JSON parsing behavior that required a compatibility layer:

**Issue 1: Integer vs Long**
- Cheshire parses JSON integers as `java.lang.Integer`
- Expected behavior: `java.lang.Long`
- Impact: Causes `ConcurrentHashMap` lookup failures when Integer keys don't match Long keys

**Issue 2: LazySeq vs Vector**
- Cheshire parses JSON arrays as `clojure.lang.LazySeq`
- Expected behavior: `clojure.lang.PersistentVector`
- Impact: Breaks code using `vector?` checks and indexed access patterns

**Solution: normalize-parsed-json**

Implemented a private `normalize-parsed-json` function that walks the parsed data structure and:
- Converts all `Integer` instances to `Long` for Java interop compatibility
- Converts all lazy sequences to vectors for consistent collection behavior
- Preserves maps and other collection types unchanged

This normalization is applied transparently in `json/parse`, maintaining API compatibility while ensuring consistent behavior across the codebase.

### 3. API Mapping

| clojure.data.json | Cheshire (via mcp-clj.json) | Notes |
|-------------------|------------------------------|-------|
| `(json/read-str s :key-fn keyword)` | `(json/parse s)` | Auto-converts keys to keywords |
| `(json/write-str data)` | `(json/write data)` | Auto-converts keyword keys to strings |
| `(json/write-str data {:key-fn name})` | `(json/write data)` | Same behavior, built into write |

## Implementation History

The migration was completed through a series of commits:

1. `47c7eb0` - Migrate json_protocol.clj to cheshire API
2. `1c3c2d0` - Migrate stdio JSON I/O from clojure.data.json to cheshire
3. `36d9efb` - Migrate HTTP modules from clojure.data.json to cheshire
4. `2c0ff84` - Add cheshire compatibility layer for JSON parsing (first normalization attempt)
5. `1395687` - Create json component to centralize JSON handling
6. `d6780b9` - Normalize JSON-RPC request IDs to Long for HashMap lookups
7. `1fa8b5c` - Complete JSON component API migration and add normalization (final solution)

The normalization layer was essential to fix batch request test failures caused by type inconsistencies.

## Consequences

### Positive

- **Performance**: ~2x faster JSON parsing and generation
- **Babashka Ready**: Built-in Babashka support removes a dependency barrier
- **Centralized Control**: `mcp-clj.json` component provides single point of control
- **Feature Rich**: Access to streaming APIs and other advanced features when needed
- **Battle Tested**: Cheshire is widely used and well-maintained in the ecosystem

### Negative

- **Normalization Overhead**: Every parsed JSON structure is walked to normalize types
- **Memory Usage**: Normalization converts lazy sequences to vectors, losing laziness benefits
- **Hidden Complexity**: The normalization layer is transparent but adds cognitive overhead

### Neutral

- **API Change**: Internal API changed but behavior remained identical due to normalization
- **Test Suite**: All existing tests pass without modification, validating the migration
- **Type Semantics**: Integer→Long and LazySeq→Vector conversions are now project-wide conventions

## Alternatives Considered

### 1. Keep clojure.data.json
**Rejected**: Missing Babashka support and slower performance outweighed migration effort

### 2. Use jsonista (faster than cheshire)
**Rejected**: Not built into Babashka. While faster, cheshire's performance is sufficient and Babashka compatibility is more important

### 3. Fix call sites instead of normalizing
**Rejected**: Would require changes throughout the codebase and create ongoing maintenance burden. Centralized normalization is more maintainable

### 4. Use cheshire without normalization
**Tried**: Initially attempted in commit `2c0ff84` but caused test failures. The type differences were too pervasive to fix piecemeal

## Related Documents

- Story: "Switch from clojure.data.json to cheshire" (Task #1)
- [ADR 001: JSON-RPC Server Design](001-json-rpc-server.md)
- [Cheshire Documentation](https://github.com/dakrone/cheshire)
- Component: `components/json/src/mcp_clj/json.clj`

## Notes

- The normalization layer is an implementation detail of the `json` component
- Future optimizations could explore selective normalization if performance becomes an issue
- Streaming APIs from cheshire are available but not currently used
- The migration maintains complete behavioral compatibility with the original clojure.data.json implementation
