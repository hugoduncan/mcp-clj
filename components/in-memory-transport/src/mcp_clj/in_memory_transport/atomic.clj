(ns mcp-clj.in-memory-transport.atomic
  "Type-hinted wrapper functions for Java atomic operations to eliminate reflection warnings"
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

(defn compare-and-set-boolean!
  "Atomically set the value to the given updated value if the current value equals the expected value"
  [^AtomicBoolean atomic expected updated]
  (.compareAndSet atomic expected updated))

;; AtomicLong operations

(defn create-atomic-long
  "Create a new AtomicLong with initial value"
  [initial-value]
  (AtomicLong. initial-value))

(defn get-long
  "Get the current value of an AtomicLong"
  [^AtomicLong atomic]
  (.get atomic))

(defn set-long!
  "Set the value of an AtomicLong"
  [^AtomicLong atomic value]
  (.set atomic value))

(defn increment-and-get-long!
  "Atomically increment by one and return the updated value"
  [^AtomicLong atomic]
  (.incrementAndGet atomic))

(defn decrement-and-get-long!
  "Atomically decrement by one and return the updated value"
  [^AtomicLong atomic]
  (.decrementAndGet atomic))

(defn add-and-get-long!
  "Atomically add the given value and return the updated value"
  [^AtomicLong atomic delta]
  (.addAndGet atomic delta))

(defn compare-and-set-long!
  "Atomically set the value to the given updated value if the current value equals the expected value"
  [^AtomicLong atomic expected updated]
  (.compareAndSet atomic expected updated))
