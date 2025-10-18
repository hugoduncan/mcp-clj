(ns mcp-clj.cross-compliance-test.sdk-client.resources-server
  "Test server for cross-compliance resource subscription tests.

  This server enables the resources capability with subscriptions for testing
  with Java SDK client."
  (:gen-class)
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]))

(defn test-resource-impl
  "Implementation for the test resource"
  [_context _uri]
  {:contents [{:uri "test://dynamic-resource"
               :mimeType "text/plain"
               :text "Dynamic test content"}]})

(def test-resource
  {:name "test-resource"
   :uri "test://dynamic-resource"
   :mime-type "text/plain"
   :description "A test resource that can be updated dynamically"
   :implementation test-resource-impl})

(defn trigger-resource-update
  "Tool implementation that triggers a resource update notification"
  [context params]
  (let [uri (:uri params)
        server (:server context)]
    (log/info :trigger-resource-update {:uri uri})
    (mcp-server/notify-resource-updated! server uri)
    [{:type "text"
      :text (str "Triggered resource update notification for: " uri)}]))

(defn -main
  "Start stdio MCP server with resources capability enabled"
  [& _args]
  (try
    (log/info :test-resources-server {:msg "Starting with resources capability"})

    ;; Define resources
    (let [resources
          {"test-resource" test-resource}

          ;; Define tools
          tools
          {"trigger-resource-update"
           {:name "trigger-resource-update"
            :description "Trigger a resource update notification for testing"
            :inputSchema
            {:type "object"
             :properties
             {:uri
              {:type "string"
               :description "URI of the resource to update"}}
             :required ["uri"]}
            :implementation trigger-resource-update}}

          ;; Create server with resources capability
          server (mcp-server/create-server
                   {:transport {:type :stdio}
                    :resources resources
                    :tools tools
                    :server-info {:name "test-resources-server"
                                  :version "1.0.0"}
                    :capabilities {:resources {:subscribe true
                                               :listChanged true}}})]

      (log/info :test-resources-server {:msg "Started"})

      (.addShutdownHook
        (Runtime/getRuntime)
        (Thread. #(do
                    (log/info :shutting-down-test-resources-server)
                    ((:stop server)))))

      ;; Keep the main thread alive
      @(promise))

    (catch Exception e
      (log/error :test-resources-server {:error (.getMessage e)
                                         :stack-trace (with-out-str (.printStackTrace e))})
      (.printStackTrace e)
      (System/exit 1))))
