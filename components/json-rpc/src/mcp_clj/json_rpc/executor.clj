(ns mcp-clj.json-rpc.executor
  "Executor service utilities for JSON-RPC servers"
  (:require
    [mcp-clj.log :as log])
  (:import
    (java.util.concurrent
      Callable
      ExecutorService
      Executors
      ScheduledExecutorService
      ThreadPoolExecutor
      TimeUnit)))

(defn- wrap-log-throwables
  "Wrap a function to log any exceptions"
  [f]
  (fn []
    (try
      (f)
      (catch Exception e
        (log/error :executor/unexpected e)))))

(defn create-executor
  "Create bounded executor service"
  [num-threads]
  (Executors/newScheduledThreadPool num-threads))

(defn shutdown-executor
  "Shutdown executor service gracefully"
  [^ThreadPoolExecutor executor]
  (.shutdown executor)
  (try
    (when-not (.awaitTermination executor 5 TimeUnit/SECONDS)
      (.shutdownNow executor))
    (catch InterruptedException _
      (.shutdownNow executor))))

(defn submit!
  "Submit a task to the executor.
  Return a future."
  ([executor f]
   (.submit ^ExecutorService executor ^Callable (wrap-log-throwables f)))
  ([executor f delay-millis]
   (.schedule
     ^ScheduledExecutorService executor
     ^Callable (wrap-log-throwables f)
     (long delay-millis)
     TimeUnit/MILLISECONDS)))

(defn submit-with-timeout!
  "Execute a function on the executor with a timeout.
  Return a future that resolves to the result."
  [executor f timeout-ms]
  ;; futures will cancel each other
  (let [cf           (promise)
        task         (submit!
                       executor
                       #(do (let [result (f)]
                              (when (realized? cf)
                                (future-cancel @cf))
                              result)))
        timeout-task (submit!
                       executor
                       #(future-cancel task)
                       timeout-ms)]
    (deliver cf timeout-task)
    task))
