(ns build
  "Build script for mcp-clj projects using tools.build.

  Provides functions for building JAR files with automatic version numbering
  based on git commit count. All projects share the same version number.

  Usage:
    clj -T:build jar :project-name '\"server\"' :lib 'org.hugoduncan/mcp-clj-server'
    clj -T:build clean :project-name '\"server\"'
    clj -T:build version

  The :build alias in each project's deps.edn configures the project-specific
  parameters."
  (:require
    [clojure.java.io :as io]
    [clojure.tools.build.api :as b]))

(def major-minor "0.1")

(def root-dir
  "Find the repository root by looking for deps.edn with :build alias."
  (let [cwd (System/getProperty "user.dir")]
    (loop [dir (io/file cwd)]
      (cond
        (nil? dir) cwd
        (.exists (io/file dir "dev" "build.clj")) (.getPath dir)
        :else (recur (.getParentFile dir))))))

(defn version
  "Calculate version string from git commit count.

  Returns a version string in format 'major.minor.commit-count'."
  [_opts]
  (let [commit-count (b/git-count-revs nil)
        v (format "%s.%s" major-minor commit-count)]
    (println "Version:" v)
    v))

(defn- project-target-dir
  "Get target directory for a project."
  [project-name]
  (str root-dir "/projects/" project-name "/target"))

(defn- project-class-dir
  "Get classes directory for a project."
  [project-name]
  (str (project-target-dir project-name) "/classes"))

(defn- jar-file-path
  "Get JAR file path for a project."
  [project-name version]
  (format "%s/mcp-clj-%s-%s.jar"
          (project-target-dir project-name)
          project-name
          version))

(defn clean
  "Remove target directory for a project.

  Options:
    :project-name - Name of the project (e.g., 'server', 'client')"
  [{:keys [project-name] :as opts}]
  (when-not project-name
    (throw (ex-info "Missing required :project-name" opts)))
  (let [target-dir (project-target-dir project-name)]
    (println "Cleaning" target-dir)
    (b/delete {:path target-dir})))

(defn jar
  "Build a JAR file for a project.

  Options:
    :project-name - Name of the project (e.g., 'server', 'client')
    :lib - Qualified library name (e.g., org.hugoduncan/mcp-clj-server)
    :src-dirs - Optional vector of source directories to include
    :resource-dirs - Optional vector of resource directories to include
    :pom-data - Optional vector of additional POM metadata (e.g., licenses)

  The JAR is built as a thin JAR (no dependencies bundled) with pom.xml
  metadata. Version is automatically determined from git commit count."
  [{:keys [project-name lib src-dirs resource-dirs pom-data] :as opts}]
  (when-not project-name
    (throw (ex-info "Missing required :project-name" opts)))
  (when-not lib
    (throw (ex-info "Missing required :lib" opts)))

  (let [v (version nil)
        class-dir (project-class-dir project-name)
        jar-file (jar-file-path project-name v)
        project-dir (str root-dir "/projects/" project-name)
        basis (b/create-basis {:project (str project-dir "/deps.edn")})]

    (println "\nBuilding" lib v)
    (println "Project:" project-name)
    (println "JAR file:" jar-file)

    ;; Clean first
    (clean opts)

    ;; Copy source files from basis paths and local dependencies
    (let [basis-paths (:paths basis)
          ;; Extract paths from local/root dependencies
          local-deps (filter #(contains? (val %) :local/root) (:libs basis))
          local-paths (mapcat #(:paths (val %)) local-deps)
          all-paths (concat basis-paths local-paths)
          all-src-dirs (or src-dirs all-paths)]
      (println "Copying sources from:" (count all-src-dirs) "paths")
      (b/copy-dir {:src-dirs all-src-dirs
                   :target-dir class-dir}))

    ;; Copy resource files if specified
    (when resource-dirs
      (println "Copying resources from:" resource-dirs)
      (b/copy-dir {:src-dirs resource-dirs
                   :target-dir class-dir}))

    ;; Write pom.xml
    (println "Writing pom.xml")
    (b/write-pom (cond-> {:class-dir class-dir
                          :lib lib
                          :version v
                          :basis basis
                          :src-dirs ["src"]}
                   pom-data (assoc :pom-data pom-data)))

    ;; Create JAR
    (println "Creating JAR")
    (b/jar {:class-dir class-dir
            :jar-file jar-file})

    (println "\nBuild complete:" jar-file)
    jar-file))

(defn deploy
  "Deploy a JAR to Clojars.

  Builds the JAR first if it doesn't exist, then deploys to Clojars using
  the credentials configured in ~/.m2/settings.xml.

  Options:
    :project-name - Name of the project (e.g., 'server', 'client')
    :lib - Qualified library name (e.g., org.hugoduncan/mcp-clj-server)

  Requires CLOJARS_USERNAME and CLOJARS_PASSWORD environment variables or
  Maven settings.xml configuration."
  [{:keys [project-name lib] :as opts}]
  (when-not project-name
    (throw (ex-info "Missing required :project-name" opts)))
  (when-not lib
    (throw (ex-info "Missing required :lib" opts)))

  ;; Load deps-deploy dynamically (only needed for deploy, not jar)
  (require '[deps-deploy.deps-deploy :as dd])

  (let [v (version nil)
        jar-file (jar-file-path project-name v)
        class-dir (project-class-dir project-name)
        project-dir (str root-dir "/projects/" project-name)
        basis (b/create-basis {:project (str project-dir "/deps.edn")})]

    ;; Build JAR if it doesn't exist
    (when-not (.exists (clojure.java.io/file jar-file))
      (println "JAR not found, building first...")
      (jar opts))

    (println "\nDeploying" lib v "to Clojars")
    (println "JAR file:" jar-file)

    ;; Deploy to Clojars using deps-deploy
    ((resolve 'deps-deploy.deps-deploy/deploy)
     {:installer :remote
      :artifact jar-file
      :pom-file (str class-dir "/META-INF/maven/"
                     (namespace lib) "/" (name lib) "/pom.xml")})

    (println "\nDeploy complete:" lib v)))
