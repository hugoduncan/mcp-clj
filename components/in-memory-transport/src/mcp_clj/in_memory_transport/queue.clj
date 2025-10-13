(ns mcp-clj.in-memory-transport.queue
  "Type-hinted wrapper functions for Java queue operations to eliminate reflection warnings"
  (:import
    (java.util.concurrent
      LinkedBlockingQueue
      TimeUnit)))

(defn create-queue
  "Create a new LinkedBlockingQueue"
  []
  (LinkedBlockingQueue.))

(defn offer!
  "Put an item in the queue. Returns true if successful."
  [^LinkedBlockingQueue queue item]
  (.offer queue item))

(defn poll!
  "Poll an item from the queue with timeout in milliseconds.
  Returns the item or nil if timeout elapsed."
  [^LinkedBlockingQueue queue timeout-ms]
  (.poll queue timeout-ms TimeUnit/MILLISECONDS))
