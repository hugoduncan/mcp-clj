(ns mcp-clj.tools.ls-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.tools.ls :as ls]))

(defn create-test-directory-structure
  "Create a temporary directory structure for testing"
  []
  (let [user-dir (System/getProperty "user.dir")
        temp-dir-name (str "ls-tool-test-" (System/currentTimeMillis))
        temp-dir (io/file user-dir temp-dir-name)
        temp-path (.getPath temp-dir)]
    (.mkdir temp-dir)

    ;; Create test files and directories
    (doseq [path ["file1.txt"
                  "file2.clj"
                  ".hidden-file"
                  ".DS_Store"
                  "subdir1/file3.txt"
                  "subdir1/file4.clj"
                  "subdir1/.gitignore"
                  "subdir2/deep/file5.txt"
                  "subdir2/deep/deeper/file6.txt"]]
      (let [full-path (io/file temp-path path)]
        (.mkdirs (.getParentFile full-path))
        (spit full-path (str "content of " path))))

    ;; Create .gitignore file
    (spit (io/file temp-path ".gitignore") "*.log\ntemp-*\n")
    (spit (io/file temp-path "subdir1/.gitignore") "file4.clj\n")

    ;; Create files that should be ignored
    (spit (io/file temp-path "test.log") "log content")
    (spit (io/file temp-path "temp-file.txt") "temp content")

    temp-path))

(defn cleanup-test-directory
  "Remove test directory"
  [temp-path]
  (letfn [(delete-recursively
            [file]
            (when (.isDirectory file)
              (doseq [child (.listFiles file)]
                (delete-recursively child)))
            (.delete file))]
    (delete-recursively (io/file temp-path))))

(deftest ls-tool-basic-test
  (let [temp-dir (create-test-directory-structure)]
    (try
      (testing "basic directory listing"
        (let [{:keys [implementation]} ls/ls-tool
              result (implementation nil {:path temp-dir})
              response-text (-> result :content first :text)
              data (json/read-str response-text :key-fn keyword)]

          (is (false? (:isError result)))
          (is (vector? (:files data)))
          (is (boolean? (:truncated data)))
          (is (number? (:total-files data)))

          ;; Should include regular files and hidden files but exclude .DS_Store
          ;; and gitignored files
          (let [file-names (map #(.getName (io/file %)) (:files data))]
            (is (some #{"file1.txt" "file2.clj" ".hidden-file"} file-names))
            (is (not
                  (some #{"DS_Store" "test.log" "temp-file.txt"} file-names))))))

      (finally
        (cleanup-test-directory temp-dir)))))

(deftest ls-tool-depth-limit-test
  (let [temp-dir (create-test-directory-structure)]
    (try
      (testing "depth limit enforcement"
        (let [{:keys [implementation]} ls/ls-tool
              result (implementation nil {:path temp-dir :max-depth 3})
              response-text (-> result :content first :text)
              data (json/read-str
                     response-text
                     :key-fn keyword)]

          (is (false? (:isError result)))

          ;; Should not include files from subdir2/deep/deeper (depth 3)
          (let [file-paths (:files data)]
            (is (not (some #(str/includes? % "deeper") file-paths)))
            (is (some
                  #(str/includes? % "subdir2/deep/file5.txt")
                  file-paths)))))

      (finally
        (cleanup-test-directory temp-dir)))))

(deftest ls-tool-file-limit-test
  (let [temp-dir (create-test-directory-structure)]
    (try
      (testing "file count limit enforcement"
        (let [{:keys [implementation]} ls/ls-tool
              result (implementation nil {:path temp-dir :max-files 2})
              response-text (-> result :content first :text)
              data (json/read-str response-text :key-fn keyword)]

          (is (false? (:isError result)))
          (is (<= (count (:files data)) 2))
          (is (true? (:max-files-reached data)))
          (is (true? (:truncated data)))))

      (finally
        (cleanup-test-directory temp-dir)))))

(deftest ls-tool-single-file-test
  (testing "single file handling"
    (let [test-file "components/tools/test-resources/ls-test/single-test-file.txt"
          {:keys [implementation]} ls/ls-tool
          result (implementation nil {:path test-file})
          response-text (-> result :content first :text)
          data (json/read-str response-text :key-fn keyword)]

      (is (false? (:isError result)))
      (is (= 1 (count (:files data))))
      ;; The ls tool returns absolute paths, so we need to check for the ending
      (is (str/ends-with? (first (:files data)) test-file))
      (is (false? (:truncated data))))))

(deftest ls-tool-gitignore-test
  (let [temp-dir (create-test-directory-structure)]
    (try
      (testing "gitignore filtering"
        (let [{:keys [implementation]} ls/ls-tool
              result (implementation nil {:path temp-dir})
              response-text (-> result :content first :text)
              data (json/read-str response-text :key-fn keyword)
              file-names (map #(.getName (io/file %)) (:files data))]

          (is (false? (:isError result)))

          ;; Files in root .gitignore should be excluded
          (is (not (some #{"test.log" "temp-file.txt"} file-names)))

          ;; file4.clj should be excluded due to subdir1/.gitignore
          (is (not (some #{"file4.clj"} file-names)))))

      (finally
        (cleanup-test-directory temp-dir)))))

(deftest ls-tool-error-handling-test
  (testing "non-existent path"
    (let [{:keys [implementation]} ls/ls-tool
          result (implementation nil {:path "./this/path/does/not/exist"})]

      (is (true? (:isError result)))
      (is (str/includes?
            (-> result :content first :text)
            "does not exist"))))

  (testing "path traversal prevention"
    (let [{:keys [implementation]} ls/ls-tool
          result (implementation nil {:path "../../../etc"})]

      (is (true? (:isError result)))
      (is (str/includes?
            (-> result :content first :text)
            "outside allowed directories"))))

  (testing "current directory access allowed"
    (let [{:keys [implementation]} ls/ls-tool
          result (implementation nil {:path "."})]

      (is (false? (:isError result)))))

  (testing "user.dir access allowed"
    (let [{:keys [implementation]} ls/ls-tool
          user-dir (System/getProperty "user.dir")
          result (implementation nil {:path user-dir})]

      (is (false? (:isError result))))))

(deftest ls-tool-schema-validation-test
  (testing "tool schema structure"
    (let [tool ls/ls-tool]
      (is (= "ls" (:name tool)))
      (is (string? (:description tool)))
      (is (map? (:inputSchema tool)))
      (is (fn? (:implementation tool)))

      ;; Check required path parameter
      (is (contains? (set (-> tool :inputSchema :required)) "path"))

      ;; Check optional parameters have proper constraints
      (let [properties (-> tool :inputSchema :properties)]
        (is (= "string" (get-in properties ["path" :type])))
        (is (= "integer" (get-in properties ["max-depth" :type])))
        (is (= "integer" (get-in properties ["max-files" :type])))
        (is (= 1 (get-in properties ["max-depth" :minimum])))
        (is (= 1 (get-in properties ["max-files" :minimum])))))))
