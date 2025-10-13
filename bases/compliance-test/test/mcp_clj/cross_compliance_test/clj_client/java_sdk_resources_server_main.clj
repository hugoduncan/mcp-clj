(ns mcp-clj.cross-compliance-test.clj-client.java-sdk-resources-server-main
  "Main class for Java SDK server with resources capability for testing.
  
  This server is spawned as a subprocess by Clojure client tests via stdio transport."
  (:gen-class)
  (:require
    [mcp-clj.java-sdk.interop :as java-sdk]
    [mcp-clj.log :as log]))

(defn create-test-resource-handler
  "Create a resource handler that returns test content"
  [resource-uri resource-text]
  (fn [_exchange _uri]
    {:contents [{:uri resource-uri
                 :mimeType "text/plain"
                 :text resource-text}]}))

(defn create-trigger-tool-handler
  "Create a tool handler that triggers resource update notifications"
  [server-record]
  (fn [params]
    (let [uri (:uri params)]
      (log/info :trigger-tool {:uri uri})
      (java-sdk/notify-resource-updated server-record uri)
      {:content [{:type "text"
                  :text (str "Triggered update for: " uri)}]
       :isError false})))

(defn -main
  "Start Java SDK server with resources capability on stdio transport"
  [& _args]
  (try
    (log/info :java-sdk-resources-server {:msg "Starting with resources capability"})

    (let [resource-uri "test://dynamic-resource"
          resource-name "test-resource"
          resource-text "Initial test content"

          ;; Create server with stdio transport and resources capability
          server (java-sdk/create-java-server
                   {:name "java-sdk-resources-server"
                    :version "1.0.0"
                    :async? true
                    :capabilities {:tools true
                                   :resources {:subscribe true
                                               :listChanged true}}})

          ;; Register test resource
          resource-handler (create-test-resource-handler resource-uri resource-text)
          _ (java-sdk/register-resource
              server
              {:uri resource-uri
               :name resource-name
               :description "A test resource for subscription testing"
               :mime-type "text/plain"
               :implementation resource-handler})

          ;; Register tool to trigger updates
          trigger-handler (create-trigger-tool-handler server)
          _ (java-sdk/register-tool
              server
              {:name "trigger-resource-update"
               :description "Trigger a resource update notification"
               :input-schema {:type "object"
                              :properties {:uri {:type "string"
                                                 :description "URI of resource to update"}}
                              :required ["uri"]}
               :implementation trigger-handler})

          ;; Start server (stdio transport runs on System.in/System.out)
          _ (java-sdk/start-server server)]

      (log/info :java-sdk-resources-server {:msg "Started and ready"})

      ;; Keep server running
      @(promise))

    (catch Exception e
      (log/error :java-sdk-resources-server-error
                 {:error (.getMessage e)
                  :stack-trace (with-out-str (.printStackTrace e))})
      (.printStackTrace e)
      (System/exit 1))))
