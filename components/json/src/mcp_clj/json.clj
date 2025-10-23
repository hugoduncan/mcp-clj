(ns mcp-clj.json
  "JSON parsing and writing functionality.

  Encapsulates the use of cheshire for JSON operations."
  (:require
    [cheshire.core :as json]))

(defn parse
  "Parse JSON string to EDN with keyword keys.

  Parameters:
  - s: JSON string to parse

  Returns EDN data with string keys converted to keywords.

  Throws exception on invalid JSON."
  [s]
  (json/parse-string s true))

(defn write
  "Convert EDN data to JSON string.

  Parameters:
  - data: EDN data to convert

  Keyword keys are converted to strings via name.

  Returns JSON string.

  Throws exception on conversion error."
  [data]
  (json/generate-string data {:key-fn name}))
