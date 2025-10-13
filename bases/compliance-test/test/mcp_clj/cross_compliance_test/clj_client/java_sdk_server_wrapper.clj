(ns mcp-clj.cross-compliance-test.clj-client.java-sdk-server-wrapper
  "Wrapper for Java SDK server with resources capability for testing Clojure client.
  
  Provides utilities to create and manage Java SDK servers with resource subscription
  support for cross-compliance testing."
  (:require
    [mcp-clj.java-sdk.interop :as java-sdk]
    [mcp-clj.log :as log])
  (:import
    (java.io
      BufferedReader
      BufferedWriter
      InputStreamReader
      OutputStreamWriter
      PipedInputStream
      PipedOutputStream)
    (java.util.concurrent
      CountDownLatch
      TimeUnit)))

(defn create-test-resource-handler
  "Create a resource handler that returns test content"
  [resource-uri resource-text]
  (fn [_exchange _uri]
    {:contents [{:uri resource-uri
                 :mimeType "text/plain"
                 :text resource-text}]}))

(defn create-trigger-tool-handler
  "Create a tool handler that triggers resource update notifications"
  [server-record update-latch]
  (fn [params]
    (let [uri (:uri params)]
      (log/info :trigger-tool {:uri uri})
      (java-sdk/notify-resource-updated server-record uri)
      (when update-latch
        (.countDown update-latch))
      {:content [{:type "text"
                  :text (str "Triggered update for: " uri)}]
       :isError false})))

(defn create-java-sdk-server-with-resources
  "Create a Java SDK server with resources capability for testing.
  
  Options:
  - :resource-uri - URI for the test resource (default 'test://dynamic-resource')
  - :resource-name - Name for the test resource (default 'test-resource')
  - :resource-text - Initial text content (default 'Initial test content')
  - :update-latch - Optional CountDownLatch to signal when update is triggered
  
  Returns a map with:
  - :server - JavaSdkServer record
  - :in-stream - PipedInputStream for server stdin
  - :out-stream - PipedOutputStream for server stdout
  - :in-writer - BufferedWriter to write to server
  - :out-reader - BufferedReader to read from server"
  [{:keys [resource-uri resource-name resource-text update-latch]
    :or {resource-uri "test://dynamic-resource"
         resource-name "test-resource"
         resource-text "Initial test content"}}]

  (log/info :creating-java-sdk-server-with-resources
            {:uri resource-uri :name resource-name})

  ;; Create pipes for stdio communication
  (let [server-in (PipedInputStream.)
        client-to-server-out (PipedOutputStream. server-in)

        client-from-server-in (PipedInputStream.)
        server-out (PipedOutputStream. client-from-server-in)

        ;; Create readers/writers
        in-writer (BufferedWriter. (OutputStreamWriter. client-to-server-out "UTF-8"))
        out-reader (BufferedReader. (InputStreamReader. client-from-server-in "UTF-8"))

        ;; Create transport that uses our pipes
        transport (java-sdk/create-stdio-server-transport)

        ;; Create server with resources capability
        server (java-sdk/create-java-server
                 {:name "java-sdk-resources-server"
                  :version "1.0.0"
                  :async? true
                  :transport transport
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
        trigger-handler (create-trigger-tool-handler server update-latch)
        _ (java-sdk/register-tool
            server
            {:name "trigger-resource-update"
             :description "Trigger a resource update notification"
             :input-schema {:type "object"
                            :properties {:uri {:type "string"
                                               :description "URI of resource to update"}}
                            :required ["uri"]}
             :implementation trigger-handler})

        ;; Start server
        _ (java-sdk/start-server server)]

    {:server server
     :in-stream server-in
     :out-stream server-out
     :in-writer in-writer
     :out-reader out-reader
     :resource-uri resource-uri}))

(defn close-java-sdk-server
  "Close the Java SDK server and associated resources"
  [{:keys [server in-writer out-reader]}]
  (try
    (when in-writer
      (.close in-writer))
    (when out-reader
      (.close out-reader))
    (when server
      (java-sdk/stop-server server))
    (catch Exception e
      (log/warn :close-server-error {:error e}))))
