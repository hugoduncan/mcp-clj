(ns mcp-clj.log
  (:require
    [clojure.string :as str]))

(def ^:private levels #{:error :warn :info :debug :trace})

(def ^:private level-names
  (reduce
    (fn [res k]
      (assoc res k (str/upper-case (name k))))
    {}
    levels))

(def ^:private aspects
  #{"sse" "http" "rpc" "client" "server" "stdio-server" "tool" "java-sdk"
    "sdk-server-main"})

(defonce ^:private config (atom {}))

(defn enable!
  "Enable logging for the specified level and aspect."
  [level aspect]
  {:pre [(contains? levels level)]}
  (swap! config assoc-in [level aspect] true))

(doseq [aspect aspects]
  (enable! :error aspect)
  (enable! :warn aspect))

(defn disable!
  "Disable logging for the specified level and aspect."
  [level aspect]
  {:pre [(contains? levels level)]}
  (swap! config assoc-in [level aspect] false))

(defn enabled?
  "Check if logging is enabled for the specified level and aspect."
  [level aspect]
  {:pre [(contains? levels level)]}
  (get-in @config [level aspect]))

(defn output
  [level aspect id data]
  (binding [*out* *err*]
    (locking *err*
      (println
        (str (level-names level) " [" aspect "/" (name id) "]"
             (when data (str " " (pr-str data))))))))

(defmacro log
  "Log a message if enabled for the specified level and aspect.
   id - Identifier for the log entry, the namespace is the aspect
   level - One of :error, :warn, :info, :debug, :trace
   aspect - Keyword identifying the aspect being logged
   data - Optional map of data to include"
  [level id & [data]]
  `(let [id#     ~id
         aspect# (namespace id#)]
     (when (enabled? ~level aspect#)
       (output ~level aspect# id# ~data))))

(defmacro error
  [id & [data]]
  `(log :error  ~id ~data))

(defmacro warn
  [id & [data]]
  `(log :warn   ~id ~data))

(defmacro info
  [id & [data]]
  `(log :info   ~id ~data))

(defmacro debug
  [id & [data]]
  `(log :debug  ~id ~data))

(defmacro trace
  [id & [data]]
  `(log :trace  ~id ~data))

;; (enable! :debug "rpc")
;; (enable! :debug "server")
;; (enable! :debug "client")
;; (enable! :debug "http")
;; (enable! :debug "java-sdk")
;; (enable! :debug "sdk-server-main")

(comment
  (enable! :info "http")
  (enable! :info "sse")
  (enable! :debug "rpc")
  (enable! :debug "server")
  (enable! :debug "client")
  (enable! :info "handle-error")
  (disable! :info "handle-error")

  (enable! :debug "fred")
  (debug :fred/bloggs {:hello "some data"})
  (info :sse/send! {:hello "some data"})
  (info :rpc/request {:hello "some data"})
  )
