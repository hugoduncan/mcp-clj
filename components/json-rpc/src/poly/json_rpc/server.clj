(ns poly.json-rpc.server
  "JSON-RPC 2.0 server implementation with EDN/JSON conversion"
  (:require [aleph.http :as http]
            [clojure.data.json :as json]
            [poly.json-rpc.protocol :as protocol]))

;;; Server creation and lifecycle

(defn create-server
  "Create a new JSON-RPC server.
   
   Configuration options:
   - :port     Required. Port number to listen on
   - :handlers Required. Map of method names to handler functions
   
   Returns a map containing:
   - :server   The server instance
   - :stop     Function to stop the server
   
   Example:
   ```clojure
   (create-server
     {:port 8080
      :handlers {\"echo\" (fn [params] {:result params})}})
   ```"
  [{:keys [port handlers] :as config}]
  (when-not port
    (throw (ex-info "Port is required" {:config config})))
  (when-not (map? handlers)
    (throw (ex-info "Handlers must be a map" {:config config})))
  
  ;; TODO: Implement server creation
  )

;;; Request handling

(defn- handle-request
  "Handle a single JSON-RPC request.
   Converts JSON to EDN, dispatches to handler, converts response to JSON."
  [handlers request]
  ;; TODO: Implement request handling
  )

(defn- handle-batch
  "Handle a batch of JSON-RPC requests."
  [handlers requests]
  ;; TODO: Implement batch handling
  )

;;; Handler dispatch

(defn- dispatch-request
  "Dispatch a request to its handler and return the response."
  [handlers {:keys [method params] :as request}]
  ;; TODO: Implement handler dispatch
  )