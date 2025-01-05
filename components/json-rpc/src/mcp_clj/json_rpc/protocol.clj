(ns mcp-clj.json-rpc.protocol
  "JSON-RPC 2.0 protocol constants and utilities"
  (:require [clojure.data.json :as json]))

;;; Protocol version
(def ^:const version "2.0")

;;; Standard error codes
(def error-codes
  {:parse-error           -32700
   :invalid-request       -32600
   :method-not-found     -32601
   :invalid-params       -32602
   :internal-error       -32603
   :server-error-start   -32000
   :server-error-end     -32099})

;;; Response construction

(defn error-response
  "Create a JSON-RPC error response"
  ([code message]
   (error-response code message nil))
  ([code message data]
   {:jsonrpc version
    :error {:code code
            :message message
            :data data}
    :id nil}))

(defn result-response
  "Create a JSON-RPC result response"
  [id result]
  {:jsonrpc version
   :result result
   :id id})

;;; Request validation

(defn validate-request
  "Validate a JSON-RPC request.
   Returns nil if valid, error response if invalid."
  [{:keys [jsonrpc method] :as request}]
  (cond
    (not= jsonrpc version)
    (error-response (:invalid-request error-codes)
                   "Invalid JSON-RPC version")
    
    (not (string? method))
    (error-response (:invalid-request error-codes)
                   "Method must be a string")
    
    :else nil))

;;; JSON conversion

(def write-json-options
  "Options for writing JSON"
  {:key-fn name})  ; Convert keywords to strings

(def read-json-options
  "Options for reading JSON"
  {:key-fn keyword})  ; Convert strings to keywords

(defn parse-json
  "Parse JSON string to EDN, with error handling"
  [s]
  (try
    [(json/read-str s read-json-options) nil]
    (catch Exception e
      [nil (error-response (:parse-error error-codes)
                          "Invalid JSON")])))

(defn write-json
  "Convert EDN to JSON string, with error handling"
  [data]
  (try
    [(json/write-str data write-json-options) nil]
    (catch Exception e
      [nil (error-response (:internal-error error-codes)
                          "JSON conversion error")])))