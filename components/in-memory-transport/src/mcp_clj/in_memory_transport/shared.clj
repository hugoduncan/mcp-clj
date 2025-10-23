(ns mcp-clj.in-memory-transport.shared
  "Shared transport state for in-memory MCP client/server communication"
  (:require
    [mcp-clj.in-memory-transport.atomic :as atomic]
    [mcp-clj.in-memory-transport.queue :as queue])
  (:import
    (java.util.concurrent
      CompletableFuture)))

(defrecord SharedTransport
  [client-to-server-queue ; LinkedBlockingQueue for client->server messages
   server-to-client-queue ; LinkedBlockingQueue for server->client messages
   alive? ; AtomicBoolean for transport status
   request-id-counter ; AtomicLong for generating request IDs
   pending-requests ; Atom containing map of request-id -> CompletableFuture
   server-handler]) ; Server message handler function ; Server message handler function

(defn create-shared-transport
  "Create shared transport state for connecting client and server in-memory"
  []
  (->SharedTransport
    (queue/create-queue)
    (queue/create-queue)
    (atomic/create-atomic-boolean true)
    (atomic/create-atomic-long 0)
    (atom {})
    (atom nil)))

;; Type-hinted wrapper functions for queue operations

(defn offer-to-client!
  "Put message in server-to-client queue"
  [shared-transport message]
  (queue/offer! (:server-to-client-queue shared-transport) message))

(defn offer-to-server!
  "Put message in client-to-server queue"
  [shared-transport message]
  (queue/offer! (:client-to-server-queue shared-transport) message))

(defn poll-from-server!
  "Poll message from server-to-client queue with timeout"
  [shared-transport timeout-ms]
  (queue/poll! (:server-to-client-queue shared-transport) timeout-ms))

(defn poll-from-client!
  "Poll message from client-to-server queue with timeout"
  [shared-transport timeout-ms]
  (queue/poll! (:client-to-server-queue shared-transport) timeout-ms))

;; Type-hinted wrapper functions for atomic operations

(defn transport-alive?
  "Check if transport is alive"
  [shared-transport]
  (atomic/get-boolean (:alive? shared-transport)))

(defn set-transport-alive!
  "Set transport alive status"
  [shared-transport alive?]
  (atomic/set-boolean! (:alive? shared-transport) alive?))

(defn next-request-id!
  "Get next request ID atomically"
  [shared-transport]
  (atomic/increment-and-get-long! (:request-id-counter shared-transport)))

(defn get-request-id
  "Get current request ID value"
  [shared-transport]
  (atomic/get-long (:request-id-counter shared-transport)))

;; Pending requests management

(defn add-pending-request!
  "Add a pending request future.
   
   Coerces request-id to Long for consistent map key type."
  [shared-transport request-id future]
  (swap! (:pending-requests shared-transport) assoc (long request-id) future))

(defn remove-pending-request!
  "Remove and return a pending request future.
   
   Coerces request-id to Long to handle JSON parsing returning Integer."
  ^CompletableFuture [shared-transport request-id]
  (let [requests (:pending-requests shared-transport)
        id (long request-id)
        future (get @requests id)]
    (swap! requests dissoc id)
    future))

(defn get-pending-request
  "Get a pending request future without removing it.
   
   Coerces request-id to Long to handle JSON parsing returning Integer."
  [shared-transport request-id]
  (get @(:pending-requests shared-transport) (long request-id)))

;; Server handler management

(defn set-server-handler!
  "Set the server message handler"
  [shared-transport handler]
  (reset! (:server-handler shared-transport) handler))

(defn get-server-handler
  "Get the current server message handler"
  [shared-transport]
  @(:server-handler shared-transport))
