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