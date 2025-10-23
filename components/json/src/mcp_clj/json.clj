(ns mcp-clj.json
  "JSON parsing and writing functionality.

  Encapsulates the use of cheshire for JSON operations."
  (:require
    [cheshire.core :as json]
    [clojure.walk :as walk]))

(defn- normalize-parsed-json
  "Normalize cheshire parsed data for consistent behavior.
   
   Cheshire has two incompatibilities with the expected JSON parsing behavior:
   1. Parses JSON integers as java.lang.Integer (expected: Long)
   2. Parses JSON arrays as LazySeq (expected: PersistentVector)
   
   These differences cause issues with:
   - ConcurrentHashMap lookups (Integer vs Long keys)
   - Code expecting vectors (vector? checks, indexed access)"
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
