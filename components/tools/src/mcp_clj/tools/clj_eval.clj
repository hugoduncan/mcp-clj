(ns mcp-clj.tools.clj-eval
  "Clojure evaluation tool for MCP servers")

(defn safe-eval
  "Safely evaluate Clojure code, returning a result map"
  [code-str]
  (try
    (let [form (read-string code-str)]
      {:success true
       :result (with-out-str
                 (binding [*err* *out*]
                   (try
                     (print (eval form))
                     (catch Throwable e
                       (println "EVAL FAILED:")
                       (println (ex-message e) (pr-str (ex-data e)))
                       (.printStackTrace e)))))})
    (catch Throwable e
      {:success false
       :error (str (.getMessage e) "\n"
                   "ex-data : " (pr-str (ex-data e)) "\n"
                   (with-out-str
                     (binding [*err* *out*]
                       (.printStackTrace ^Throwable (ex-info "err" {})))))})))

(def clj-eval-impl
  "Implementation function for clj-eval tool"
  (fn [_context {:keys [code]}]
    (let [{:keys [success result error]} (safe-eval code)]
      (if success
        (cond-> {:content [{:type "text" :text result}]
                 :isError false}
          (re-find #"EVAL FAILED:" result)
          (assoc :isError true))
        {:content [{:type "text"
                    :text (str "Error: " error)}]
         :isError true}))))

(def clj-eval-tool
  "Built-in clojure evaluation tool"
  {:name "clj-eval"
   :description "Evaluates a Clojure expression and returns the result"
   :inputSchema {:type "object"
                 :properties {"code" {:type "string"}}
                 :required ["code"]}
   :implementation clj-eval-impl})
