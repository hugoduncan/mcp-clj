(ns mcp-clj.mcp-client.resources
  "Resource access implementation for MCP client"
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-client.session :as session]
    [mcp-clj.mcp-client.subscriptions :as subscriptions]
    [mcp-clj.mcp-client.transport :as transport])
  (:import
    (java.util.concurrent
      CompletableFuture)))

(defn- get-resources-cache
  "Get or create resources cache in client session"
  [client]
  (let [session-atom (:session client)]
    (or (:resources-cache @session-atom)
        (let [cache (atom nil)]
          (swap! session-atom assoc :resources-cache cache)
          cache))))

(defn- cache-resources!
  "Cache discovered resources in client session"
  [client resources]
  (let [cache (get-resources-cache client)]
    (reset! cache resources)
    resources))

(defn- get-cached-resources
  "Get cached resources from client session"
  [client]
  @(get-resources-cache client))

(defn list-resources-impl
  "Discover available resources from the server.

  Returns a CompletableFuture that will contain a map with :resources key
  containing vector of resource definitions. Each resource
  has :uri, :name, and optional :title, :description, :mimeType, :size, :annotations.

  Supports pagination with optional :cursor parameter."
  ^CompletableFuture [client & [{:keys [cursor]}]]
  (try
    (let [session @(:session client)]
      (if-not (session/server-supports-resources? session)
        (session/capability-not-supported "resources" session {})
        (let [transport (:transport client)
              params (cond-> {}
                       cursor (assoc :cursor cursor))
              response (transport/send-request!
                         transport
                         "resources/list"
                         params
                         30000)]
          ;; Transform the response future to handle caching and return resources
          (.thenApply response
                      (reify java.util.function.Function
                        (apply
                          [_ result]
                          (if-let [resources (:resources result)]
                            (do
                              (when-not cursor ; Only cache first page
                                (cache-resources! client resources))
                              (cond-> {:resources resources}
                                (:nextCursor result)
                                (assoc :nextCursor (:nextCursor result))))
                            {:resources []})))))))
    (catch Exception e
      (log/error :client/list-resources-error {:error (.getMessage e)})
      ;; Return a failed future for immediate exceptions
      (java.util.concurrent.CompletableFuture/failedFuture e))))

(defn read-resource-impl
  "Read a specific resource by URI.

  Returns a CompletableFuture that will contain the resource content on success.
  For errors, the future contains a map with :isError true.

  Content can be text (with :text field) or binary (with :blob field containing base64)."
  ^CompletableFuture [client resource-uri]
  (log/info :client/read-resource-start {:resource-uri resource-uri})
  (try
    (let [session @(:session client)]
      (if-not (session/server-supports-resources? session)
        (session/capability-not-supported "resources" session {:resource-uri resource-uri})
        (let [transport (:transport client)
              params {:uri resource-uri}]
          (.thenApply
            (transport/send-request!
              transport
              "resources/read"
              params
              30000)
            (reify java.util.function.Function
              (apply
                [_ result]
                (let [is-error (:isError result false)]
                  (if is-error
                    (do
                      (log/error :client/read-resource-error
                                 {:resource-uri resource-uri
                                  :contents (:contents result)})
                      ;; Return error result with resource-uri added
                      (assoc result :resource-uri resource-uri))
                    (do
                      (log/info :client/read-resource-success
                                {:resource-uri resource-uri})
                      ;; Return the resource content
                      result)))))))))
    (catch Exception e
      ;; Return a failed future for immediate exceptions (like transport errors)
      (log/error :client/read-resource-error {:resource-uri resource-uri
                                              :error (.getMessage e)
                                              :ex e})
      (CompletableFuture/failedFuture
        (ex-info
          (str "Resource read failed: " resource-uri)
          {:resource-uri resource-uri
           :error (.getMessage e)}
          e)))))

(defn available-resources?-impl
  "Check if any resources are available from the server.

  Returns true if resources are available, false otherwise.
  Uses cached resources if available, otherwise queries the server."
  [client]
  (try
    (let [session @(:session client)]
      (if-not (session/server-supports-resources? session)
        (do
          (log/debug :client/resources-not-supported
                     {:server-capabilities (:server-capabilities session)})
          false)
        (if-let [cached-resources (get-cached-resources client)]
          (boolean (seq cached-resources))
          (when-let [result-future (list-resources-impl client)]
            (let [result @result-future]
              (boolean (seq (:resources result))))))))
    (catch Exception e
      (log/debug :client/available-resources-error {:error (.getMessage e)})
      false)))

(defn subscribe-resource-impl!
  "Subscribe to resource updates for a specific URI.

  Returns a CompletableFuture that resolves when the subscription is established.
  The callback-fn will be called with notification params when the resource changes."
  ^CompletableFuture [client uri callback-fn]
  (log/info :client/subscribe-resource-start {:uri uri})
  (try
    (let [session @(:session client)]
      (if-not (session/server-supports-resources? session)
        (session/capability-not-supported "resources" session {:uri uri})
        (let [transport (:transport client)
              subscription-registry (:subscription-registry client)
              params {:uri uri}]
          ;; Register callback in subscription registry first
          (subscriptions/subscribe-resource! subscription-registry uri callback-fn)

          ;; Send subscribe request to server
          (.thenApply
            (transport/send-request!
              transport
              "resources/subscribe"
              params
              30000)
            (reify java.util.function.Function
              (apply
                [_ result]
                (log/info :client/subscribe-resource-success {:uri uri})
                result))))))
    (catch Exception e
      (log/error :client/subscribe-resource-error {:uri uri
                                                   :error (.getMessage e)
                                                   :ex e})
      (CompletableFuture/failedFuture
        (ex-info
          (str "Resource subscription failed: " uri)
          {:uri uri
           :error (.getMessage e)}
          e)))))

(defn unsubscribe-resource-impl!
  "Unsubscribe from resource updates for a specific URI.

  Returns a CompletableFuture that resolves when the unsubscription is complete."
  ^CompletableFuture [client uri]
  (log/info :client/unsubscribe-resource-start {:uri uri})
  (try
    (let [session @(:session client)]
      (if-not (session/server-supports-resources? session)
        (session/capability-not-supported "resources" session {:uri uri})
        (let [transport (:transport client)
              subscription-registry (:subscription-registry client)
              params {:uri uri}]
          ;; Send unsubscribe request to server
          (.thenApply
            (transport/send-request!
              transport
              "resources/unsubscribe"
              params
              30000)
            (reify java.util.function.Function
              (apply
                [_ result]
                ;; Remove callback from subscription registry after successful unsubscribe
                (subscriptions/unsubscribe-resource! subscription-registry uri)
                (log/info :client/unsubscribe-resource-success {:uri uri})
                result))))))
    (catch Exception e
      (log/error :client/unsubscribe-resource-error {:uri uri
                                                     :error (.getMessage e)
                                                     :ex e})
      (CompletableFuture/failedFuture
        (ex-info
          (str "Resource unsubscription failed: " uri)
          {:uri uri
           :error (.getMessage e)}
          e)))))

(defn subscribe-resources-changed-impl!
  "Subscribe to resources list changed notifications.

  Returns a CompletableFuture that resolves immediately (no server request needed).
  The callback-fn will be called when the server sends resources/list_changed notifications."
  ^CompletableFuture [client callback-fn]
  (log/info :client/subscribe-resources-changed-start)
  (try
    (let [subscription-registry (:subscription-registry client)]
      (subscriptions/subscribe-resources-changed! subscription-registry callback-fn)
      (log/info :client/subscribe-resources-changed-success)
      (CompletableFuture/completedFuture {}))
    (catch Exception e
      (log/error :client/subscribe-resources-changed-error {:error (.getMessage e)})
      (CompletableFuture/failedFuture e))))

(defn unsubscribe-resources-changed-impl!
  "Unsubscribe from resources list changed notifications.

  Returns a CompletableFuture that resolves immediately."
  ^CompletableFuture [client callback-fn]
  (log/info :client/unsubscribe-resources-changed-start)
  (try
    (let [subscription-registry (:subscription-registry client)]
      (subscriptions/unsubscribe-resources-changed! subscription-registry callback-fn)
      (log/info :client/unsubscribe-resources-changed-success)
      (CompletableFuture/completedFuture {}))
    (catch Exception e
      (log/error :client/unsubscribe-resources-changed-error {:error (.getMessage e)})
      (CompletableFuture/failedFuture e))))
