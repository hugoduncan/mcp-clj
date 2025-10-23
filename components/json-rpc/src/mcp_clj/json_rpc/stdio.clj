(ns mcp-clj.json-rpc.stdio
  "Unified JSON I/O functions for stdio communication"
  (:require
    [cheshire.core :as json]
    [clojure.walk :as walk])
  (:import
    (java.io
      BufferedReader
      BufferedWriter)))

(defn normalize-parsed-json
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

(defn read-json
  "Read JSON message from a reader.
   Return
     [json-data nil] on success,
     [:error exception] on parse error,
     nil on EOF"
  [^BufferedReader reader]
  (try
    (when-let [line (.readLine reader)]
      (let [json-data (json/parse-string line true)
            ;; Normalize for consistent behavior
            normalized-data (normalize-parsed-json json-data)]
        [normalized-data nil]))
    (catch java.io.EOFException _
      nil)
    (catch Exception e
      [:error e])))

(defn write-json!
  "Write JSON message to a writer"
  [^BufferedWriter writer message]
  (let [json-str (json/generate-string message)]
    (.write writer json-str)
    (.newLine writer)
    (.flush writer)))
