(ns mcp-clj.in-memory-transport.shared
  "Shared transport state for in-memory MCP client/server communication"
  (:import
   [java.util.concurrent LinkedBlockingQueue]
   [java.util.concurrent.atomic AtomicBoolean AtomicLong]))

(defrecord SharedTransport
           [client-to-server-queue ; LinkedBlockingQueue for client->server messages
            server-to-client-queue ; LinkedBlockingQueue for server->client messages
            alive? ; AtomicBoolean for transport status
            request-id-counter ; AtomicLong for generating request IDs
            pending-requests ; Atom containing map of request-id -> CompletableFuture
            server-handler]) ; Server message handler function

(defn create-shared-transport
  "Create shared transport state for connecting client and server in-memory"
  []
  (->SharedTransport
   (LinkedBlockingQueue.)
   (LinkedBlockingQueue.)
   (AtomicBoolean. true)
   (AtomicLong. 0)
   (atom {})
   (atom nil)))

;;; Type-hinted wrapper functions for queue operations

(defn offer-to-client!
  "Put message in server-to-client queue"
  [^SharedTransport shared-transport message]
  (.offer ^LinkedBlockingQueue (:server-to-client-queue shared-transport) message))

(defn offer-to-server!
  "Put message in client-to-server queue"
  [^SharedTransport shared-transport message]
  (.offer ^LinkedBlockingQueue (:client-to-server-queue shared-transport) message))

(defn poll-from-server!
  "Poll message from server-to-client queue with timeout"
  [^SharedTransport shared-transport timeout-ms]
  (.poll ^LinkedBlockingQueue (:server-to-client-queue shared-transport)
         timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS))

(defn poll-from-client!
  "Poll message from client-to-server queue with timeout"
  [^SharedTransport shared-transport timeout-ms]
  (.poll ^LinkedBlockingQueue (:client-to-server-queue shared-transport)
         timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS))

;;; Type-hinted wrapper functions for atomic operations

(defn transport-alive?
  "Check if transport is alive"
  [^SharedTransport shared-transport]
  (.get ^AtomicBoolean (:alive? shared-transport)))

(defn set-transport-alive!
  "Set transport alive status"
  [^SharedTransport shared-transport alive?]
  (.set ^AtomicBoolean (:alive? shared-transport) alive?))

(defn next-request-id!
  "Get next request ID atomically"
  [^SharedTransport shared-transport]
  (.incrementAndGet ^AtomicLong (:request-id-counter shared-transport)))

(defn get-request-id
  "Get current request ID value"
  [^SharedTransport shared-transport]
  (.get ^AtomicLong (:request-id-counter shared-transport)))

;;; Pending requests management

(defn add-pending-request!
  "Add a pending request future"
  [^SharedTransport shared-transport request-id future]
  (swap! (:pending-requests shared-transport) assoc request-id future))

(defn remove-pending-request!
  "Remove and return a pending request future"
  [^SharedTransport shared-transport request-id]
  (let [requests (:pending-requests shared-transport)
        future (get @requests request-id)]
    (swap! requests dissoc request-id)
    future))

(defn get-pending-request
  "Get a pending request future without removing it"
  [^SharedTransport shared-transport request-id]
  (get @(:pending-requests shared-transport) request-id))

;;; Server handler management

(defn set-server-handler!
  "Set the server message handler"
  [^SharedTransport shared-transport handler]
  (reset! (:server-handler shared-transport) handler))

(defn get-server-handler
  "Get the current server message handler"
  [^SharedTransport shared-transport]
  @(:server-handler shared-transport))
