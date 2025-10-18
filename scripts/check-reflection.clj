(set! *warn-on-reflection* true)
(require 'clojure.java.io)
(require 'clojure.string)

(defn file->namespace
  [^java.io.File file]
  (-> (.getPath file)
      (clojure.string/replace #"^(components|bases)/[^/]+/(src|test)/" "")
      (clojure.string/replace #"\.clj[cs]?$" "")
      (clojure.string/replace #"/" ".")
      (clojure.string/replace #"_" "-")
      symbol))

(defn find-source-files
  []
  (->> [(clojure.java.io/file "components")
        (clojure.java.io/file "bases")]
       (filter (fn [^java.io.File f] (.exists f)))
       (mapcat file-seq)
       (filter (fn [^java.io.File f]
                 (and (.isFile f)
                      (re-find #"\.clj[cs]?$" (.getName f))
                      (re-find #"/(src|test)/" (.getPath f)))))
       (map file->namespace)
       distinct))

(let [namespaces (find-source-files)]
  (println "Loading" (count namespaces) "namespaces...")
  (doseq [ns-sym namespaces]
    (try
      (require ns-sym)
      (catch Exception e
        (println "Warning: Could not load" ns-sym ":" (.getMessage e))))))
