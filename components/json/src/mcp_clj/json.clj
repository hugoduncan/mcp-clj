(ns mcp-clj.json
  "JSON parsing and writing functionality.

  Encapsulates the use of cheshire for JSON operations.
  
  This component provides a centralized JSON API with automatic normalization
  to ensure consistent behavior across the codebase. See ADR 002 
  (doc/adr/002-cheshire-json-library.md) for migration rationale."
  (:require
    [cheshire.core :as json]
    [clojure.walk :as walk]))

(defn- normalize-parsed-json
  "Normalize cheshire parsed data for consistent behavior.
   
   Cheshire has two behavioral differences from expected JSON parsing:
   
   1. Integer vs Long: Cheshire parses JSON integers as java.lang.Integer,
      but the codebase expects java.lang.Long. This causes ConcurrentHashMap
      lookup failures when Integer keys don't match Long keys (e.g., JSON-RPC
      request IDs used as map keys).
   
   2. LazySeq vs Vector: Cheshire parses JSON arrays as LazySeq, but the
      codebase expects PersistentVector. This breaks code using vector? checks
      and indexed access patterns.
   
   This normalization layer walks the entire data structure to convert:
   - All Integer instances → Long (for Java interop compatibility)
   - All lazy sequences → Vector (for consistent collection behavior)
   - Maps and other collections are preserved unchanged
   
   Trade-offs:
   - Performance: Adds overhead of walking entire data structure
   - Memory: Converts lazy sequences to vectors, losing laziness benefits
   - Simplicity: Enables transparent migration without changing call sites
   
   See ADR 002 (doc/adr/002-cheshire-json-library.md) for full rationale."
  [data]
  (walk/postwalk
    (fn [x]
      (cond
        ;; Convert Integer to Long for Java interop compatibility
        (instance? Integer x)
        (long x)

        ;; Convert sequences (LazySeq) to vectors for consistent behavior
        ;; But preserve maps and other collection types
        (and (seq? x) (not (map? x)))
        (vec x)

        :else
        x))
    data))

(defn parse
  "Parse JSON string to EDN with keyword keys.

  Parameters:
  - s: JSON string to parse

  Returns EDN data with string keys converted to keywords.
  Integer values are normalized to Long for Java interop.
  Array values are normalized to vectors for consistent behavior.

  Throws exception on invalid JSON."
  [s]
  (-> s
      (json/parse-string true)
      normalize-parsed-json))

(defn write
  "Convert EDN data to JSON string.

  Parameters:
  - data: EDN data to convert

  Keyword keys are converted to strings via name.

  Returns JSON string.

  Throws exception on conversion error."
  [data]
  (json/generate-string data {:key-fn name}))
