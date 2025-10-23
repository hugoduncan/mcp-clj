(ns mcp-clj.json-rpc.stdio
  "Unified JSON I/O functions for stdio communication"
  (:require
    [cheshire.core :as json])
  (:import
    (java.io
      BufferedReader
      BufferedWriter)))

(defn read-json
  "Read JSON message from a reader.
   Return
     [json-data nil] on success,
     [:error exception] on parse error,
     nil on EOF"
  [^BufferedReader reader]
  (try
    (when-let [line (.readLine reader)]
      (let [json-data (json/parse-string line true)]
        [json-data nil]))
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
