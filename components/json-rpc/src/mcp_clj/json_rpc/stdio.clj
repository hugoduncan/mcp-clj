(ns mcp-clj.json-rpc.stdio
  "Unified JSON I/O functions for stdio communication"
  (:require
   [clojure.data.json :as json])
  (:import
   [java.io BufferedReader
    BufferedWriter]))

(defn read-json
  "Read JSON message from a reader.
   Return
     [json-data nil] on success,
     [:error exception] on parse error,
     nil on EOF"
  [^BufferedReader reader]
  (try
    (when-let [line (.readLine reader)]
      (let [json-data (json/read-str line :key-fn keyword)]
        [json-data nil]))
    (catch java.io.EOFException _
      nil)
    (catch Exception e
      [:error e])))

(defn write-json!
  "Write JSON message to a writer"
  [^BufferedWriter writer message]
  (let [json-str (json/write-str message)]
    (.write writer json-str)
    (.newLine writer)
    (.flush writer)))
