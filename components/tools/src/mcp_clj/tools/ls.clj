(ns mcp-clj.tools.ls
  "File listing tool for MCP servers"
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-clj.json :as json]
    [mcp-clj.log :as log])
  (:import
    (java.io
      File)))

(def ^:private allowed-roots
  "Allowed directory roots for security"
  #{"." (System/getProperty "user.dir")})

(defn- normalize-path
  "Convert path to canonical form and check if it's within allowed roots"
  [path-str]
  (try
    (let [file (io/file path-str)
          path (.getCanonicalPath file)]
      (try
        (let [allowed? (some (fn [root]
                               (let [root-canonical (.getCanonicalPath (io/file root))]
                                 (.startsWith path root-canonical)))
                             allowed-roots)]
          (if allowed?
            path
            (throw (ex-info "Path outside allowed directories" {:path path-str}))))
        (catch java.io.IOException e
          (throw (ex-info "Invalid path" {:path path-str :cause (.getMessage e)})))))
    (catch java.io.IOException e
      (throw (ex-info "Invalid path" {:path path-str :cause (.getMessage e)})))))

(defn- read-gitignore
  "Read and parse .gitignore file, returning set of patterns"
  [dir]
  (let [gitignore-file (io/file dir ".gitignore")]
    (if (.exists gitignore-file)
      (try
        (->> (slurp gitignore-file)
             str/split-lines
             (remove #(or (str/blank? %) (str/starts-with? % "#")))
             (map str/trim)
             set)
        (catch Exception _ #{}))
      #{})))

(defn- matches-gitignore?
  "Check if a file path matches any gitignore patterns"
  [file-path gitignore-patterns]
  (let [filename (.getName (io/file file-path))]
    (some (fn [pattern]
            (cond
              (str/ends-with? pattern "/") false ; directory patterns, skip for files
              (str/includes? pattern "*") (let [regex-pattern (str/replace pattern "*" ".*")]
                                            (re-matches (re-pattern regex-pattern) filename))
              :else (= pattern filename)))
          gitignore-patterns)))

(defn- should-exclude-file?
  "Check if a file should be excluded based on our filtering rules"
  [^File file gitignore-patterns]
  (let [filename (.getName file)]
    (or (= filename ".DS_Store")
        (matches-gitignore? (.getPath file) gitignore-patterns))))

(defn- collect-files
  "Recursively collect files with limits"
  [root-dir max-depth max-files]
  (let [result (atom {:files []
                      :total-files 0
                      :max-depth-reached false
                      :max-files-reached false})]
    (letfn [(walk-dir
              [dir current-depth gitignore-patterns]
              (when (and (< current-depth max-depth)
                         (< (count (:files @result)) max-files))
                (try
                  (let [dir-gitignore (into gitignore-patterns (read-gitignore dir))
                        files (.listFiles (io/file dir))]
                    (when files
                      (doseq [^File file files]
                        (when (< (count (:files @result)) max-files)
                          (cond
                            (.isFile file)
                            (do
                              (swap! result update :total-files inc)
                              (when (and (not (should-exclude-file? file dir-gitignore))
                                         (< (count (:files @result)) max-files))
                                (swap! result update :files conj (.getCanonicalPath file))))

                            (.isDirectory file)
                            (when (and (not (should-exclude-file? file dir-gitignore))
                                       (< (inc current-depth) max-depth))
                              (walk-dir (.getPath file) (inc current-depth) dir-gitignore))))))

                    ;; Check if we can't traverse deeper due to max-depth limit
                    (let [remaining-files (.listFiles (io/file dir))]
                      (when (and remaining-files
                                 (some (fn [^File file]
                                         (and (.isDirectory file)
                                              (not (should-exclude-file? file dir-gitignore))
                                              (>= (inc current-depth) max-depth))) ; can't go deeper
                                       remaining-files))
                        (swap! result assoc :max-depth-reached true))))
                  (catch Exception _e
                    ;; Skip directories we can't read
                    nil))))]

      (walk-dir root-dir 0 #{}))

    (let [final-result @result]
      (assoc final-result
             :max-files-reached (>= (count (:files final-result)) max-files)
             :truncated (or (:max-depth-reached final-result)
                            (>= (count (:files final-result)) max-files))))))

(defn- ls-impl
  "Implementation function for ls tool"
  [_context {:keys [path max-depth max-files] :or {max-depth 10 max-files 100} :as args}]
  (log/debug :tool/ls {:args args})
  (try
    (let [normalized-path (normalize-path path)
          path-file (io/file normalized-path)]

      (cond
        (not (.exists path-file))
        {:content [{:type "text"
                    :text (str "Error: Path does not exist: " path)}]
         :isError true}

        (.isFile path-file)
        {:content [{:type "text"
                    :text (json/write {:files [normalized-path]
                                       :truncated false
                                       :total-files 1
                                       :max-depth-reached false
                                       :max-files-reached false})}]
         :isError false}

        (.isDirectory path-file)
        (let [result (collect-files normalized-path max-depth max-files)]
          {:content [{:type "text"
                      :text (json/write result)}]
           :isError false})

        :else
        {:content [{:type "text"
                    :text (str "Error: Invalid path type: " path)}]
         :isError true}))

    (catch Exception e
      {:content [{:type "text"
                  :text (str "Error: " (.getMessage e))}]
       :isError true})))

(def ls-tool
  "File listing tool with recursive traversal and security restrictions"
  {:name "ls"
   :description "List files recursively with depth and count limits. Respects .gitignore and excludes .DS_Store files."
   :inputSchema {:type "object"
                 :properties {"path" {:type "string"
                                      :description "Path to list (absolute or relative)"}
                              "max-depth" {:type "integer"
                                           :description "Maximum recursive depth (default: 10)"
                                           :minimum 1}
                              "max-files" {:type "integer"
                                           :description "Maximum number of files to return (default: 100)"
                                           :minimum 1}}
                 :required ["path"]}
   :implementation ls-impl})
