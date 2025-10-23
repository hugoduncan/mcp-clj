(ns mcp-clj.json
  "JSON parsing and writing functionality.

  Encapsulates the use of cheshire for JSON operations.

  This component provides a centralized JSON API optimized for performance.
  See ADR 002 (doc/adr/002-cheshire-json-library.md) for migration rationale."
  (:require
    [cheshire.core :as json]))

(defn parse
  "Parse JSON string to EDN with keyword keys.

  Uses parse-string-strict with array-coerce-fn to directly return vectors,
  avoiding LazySeq intermediate representation for better performance.

  Parameters:
  - s: JSON string to parse

  Returns EDN data with string keys converted to keywords and arrays as vectors.

  Note: JSON integers are parsed as java.lang.Integer. Code that uses these
  values as HashMap keys (like JSON-RPC request IDs) must coerce to Long
  at the point of use: (long id)

  Throws exception on invalid JSON or empty input."
  [s]
  (when (or (nil? s) (and (string? s) (empty? s)))
    (throw (ex-info "Cannot parse empty or nil JSON string"
                    {:type :parse-error :input s})))
  (json/parse-string-strict s true (fn [_] [])))

(defn write
  "Convert EDN data to JSON string.

  Parameters:
  - data: EDN data to convert

  Keyword keys are converted to strings via name.

  Returns JSON string.

  Throws exception on conversion error."
  ^String [data]
  (json/generate-string data {:key-fn name}))
