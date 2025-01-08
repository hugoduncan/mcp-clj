(ns mcp-clj.mcp-server.tools
  "Tool definitions and validation for MCP server")

(defn safe-eval
  "Safely evaluate Clojure code, returning a result map"
  [code-str]
  (try
    (let [form (read-string code-str)]
      {:success true
       :result  (str (eval form))})
    (catch Throwable e
      {:success false
       :error   (.getMessage e)})))

(def clj-eval-impl
  "Implementation function for clj-eval tool"
  (fn [{:keys [code]}]
    (let [{:keys [success result error]} (safe-eval code)]
      (if success
        {:content [{:type "text"
                    :text result}]}
        {:content [{:type "text"
                    :text (str "Error: " error)}]
         :isError true}))))

(def clj-eval-tool
  "Built-in clojure evaluation tool"
  {:name           "clj-eval"
   :description    "Evaluates a Clojure expression and returns the result"
   :inputSchema    {:type       "object"
                    :properties {"code" {:type "string"}}
                    :required   ["code"]}
   :implementation clj-eval-impl})

(defn valid-tool?
  "Validate a tool definition"
  [{:keys [name description inputSchema implementation] :as tool}]
  (and (string? name)
       (not (empty? name))
       (string? description)
       (map? inputSchema)
       (ifn? implementation)))

(defn tool-definition
  [tool]
  (dissoc tool :implementation))

(def default-tools
  "Default set of built-in tools"
  {"clj-eval" clj-eval-tool})
