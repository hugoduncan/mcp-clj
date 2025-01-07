(ns mcp-clj.mcp-server.tools
  "Tool management for MCP server"
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [mcp-clj.log :as log]))

(def clj-eval-tool
  {:name        "clj-eval"
   :description "Evaluates a Clojure expression and returns the result"
   :inputSchema {:type       "object"
                 :properties {"code" {:type "string"}}
                 :required   ["code"]}})

(def registered-tools
  (atom {"clj-eval" clj-eval-tool}))

(defn list-tools
  "List registered tools with pagination support"
  [{:keys [cursor]}]
  (let [tools (vec (vals @registered-tools))]
    {:tools tools}))

(defn- safe-eval
  [code-str]
  (let [form (edn/read-string code-str)]
    (try
      {:success true
       :result  (eval form)}
      (catch Exception e
        {:success false
         :error   (.getMessage e)}))))

(defn call-tool
  "Execute a tool with the given arguments"
  [{:keys [name arguments]}]
  (log/info :tools/call {:name name :arguments arguments})
  (if-let [tool (get @registered-tools name)]
    (case name
      "clj-eval"
      (let [{:keys [success result error]} (safe-eval (:code arguments))]
        (if success
          {:content [{:type "text"
                      :text (json/write-str result)}]}
          {:content [{:type "text"
                      :text (str "Error: " error)}]
           :isError true}))

      {:content [{:type "text"
                  :text (str "Unknown tool implementation: " name)}]
       :isError true})
    {:content [{:type "text"
                :text (str "Tool not found: " name)}]
     :isError true}))
