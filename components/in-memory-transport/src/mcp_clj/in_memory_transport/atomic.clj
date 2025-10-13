(ns mcp-clj.in-memory-transport.atomic
  "Type-hinted wrapper functions for Java atomic operations"
  (:import
    (java.util.concurrent.atomic
      AtomicBoolean
      AtomicLong)))

;; AtomicBoolean operations

(defn create-atomic-boolean
  "Create a new AtomicBoolean with initial value"
  [initial-value]
  (AtomicBoolean. initial-value))

(defn get-boolean
  "Get the current value of an AtomicBoolean"
  [^AtomicBoolean atomic]
  (.get atomic))

(defn set-boolean!
  "Set the value of an AtomicBoolean"
  [^AtomicBoolean atomic value]
  (.set atomic value))

;; AtomicLong operations

(defn create-atomic-long
  "Create a new AtomicLong with initial value"
  [initial-value]
  (AtomicLong. initial-value))

(defn get-long
  "Get the current value of an AtomicLong"
  [^AtomicLong atomic]
  (.get atomic))

(defn increment-and-get-long!
  "Atomically increment by one and return the updated value"
  [^AtomicLong atomic]
  (.incrementAndGet atomic))
