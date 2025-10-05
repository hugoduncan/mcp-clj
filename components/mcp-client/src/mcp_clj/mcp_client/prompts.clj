(ns mcp-clj.mcp-client.prompts
  "Prompt calling implementation for MCP client"
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-client.session :as session]
    [mcp-clj.mcp-client.subscriptions :as subscriptions]
    [mcp-clj.mcp-client.transport :as transport])
  (:import
    (java.util.concurrent
      CompletableFuture)))

(defn- get-prompts-cache
  "Get or create prompts cache in client session"
  [client]
  (let [session-atom (:session client)]
    (or (:prompts-cache @session-atom)
        (let [cache (atom nil)]
          (swap! session-atom assoc :prompts-cache cache)
          cache))))

(defn- cache-prompts!
  "Cache discovered prompts in client session"
  [client prompts]
  (let [cache (get-prompts-cache client)]
    (reset! cache prompts)
    prompts))

(defn- get-cached-prompts
  "Get cached prompts from client session"
  [client]
  @(get-prompts-cache client))

(defn list-prompts-impl
  "Discover available prompts from the server.

  Returns a CompletableFuture that will contain a map with :prompts key
  containing vector of prompt definitions. Each prompt
  has :name, :description, and :arguments.

  Supports pagination with optional :cursor parameter."
  ^CompletableFuture [client & [{:keys [cursor]}]]
  (try
    (let [session @(:session client)]
      (if-not (session/server-supports-prompts? session)
        (session/capability-not-supported "prompts" session {})
        (let [transport (:transport client)
              params (cond-> {}
                       cursor (assoc :cursor cursor))
              response (transport/send-request!
                         transport
                         "prompts/list"
                         params
                         30000)]
          ;; Transform the response future to handle caching and return prompts
          (.thenApply response
                      (reify java.util.function.Function
                        (apply
                          [_ result]
                          (if-let [prompts (:prompts result)]
                            (do
                              (when-not cursor ; Only cache first page
                                (cache-prompts! client prompts))
                              (cond-> {:prompts prompts}
                                (:nextCursor result)
                                (assoc :nextCursor (:nextCursor result))))
                            {:prompts []})))))))
    (catch Exception e
      (log/error :client/list-prompts-error {:error (.getMessage e)})
      ;; Return a failed future for immediate exceptions
      (java.util.concurrent.CompletableFuture/failedFuture e))))

(defn get-prompt-impl
  "Get a specific prompt with optional arguments.

  Returns a CompletableFuture that will contain the prompt result on success.
  For errors, the future contains a map with :isError true.

  The prompt name is required, arguments are optional for templating."
  ^CompletableFuture [client prompt-name & [arguments]]
  (log/info :client/get-prompt-start {:prompt-name prompt-name})
  (try
    (let [session @(:session client)]
      (if-not (session/server-supports-prompts? session)
        (session/capability-not-supported "prompts" session {:prompt-name prompt-name})
        (let [transport (:transport client)
              params (cond-> {:name prompt-name}
                       arguments (assoc :arguments arguments))]
          (.thenApply
            (transport/send-request!
              transport
              "prompts/get"
              params
              30000)
            (reify java.util.function.Function
              (apply
                [_ result]
                (let [is-error (:isError result false)]
                  (if is-error
                    (do
                      (log/error :client/get-prompt-error
                                 {:prompt-name prompt-name
                                  :content (:content result)})
                      ;; Return error map instead of throwing
                      {:isError true
                       :prompt-name prompt-name
                       :content (:content result)})
                    (do
                      (log/info :client/get-prompt-success
                                {:prompt-name prompt-name})
                      ;; Return the prompt result
                      result)))))))))
    (catch Exception e
      ;; Return a failed future for immediate exceptions (like transport errors)
      (log/error :client/get-prompt-error {:prompt-name prompt-name
                                           :error (.getMessage e)
                                           :ex e})
      (CompletableFuture/failedFuture
        (ex-info
          (str "Prompt request failed: " prompt-name)
          {:prompt-name prompt-name
           :error (.getMessage e)}
          e)))))

(defn available-prompts?-impl
  "Check if any prompts are available from the server.

  Returns true if prompts are available, false otherwise.
  Uses cached prompts if available, otherwise queries the server."
  [client]
  (try
    (let [session @(:session client)]
      (if-not (session/server-supports-prompts? session)
        (do
          (log/debug :client/prompts-not-supported
                     {:server-capabilities (:server-capabilities session)})
          false)
        (if-let [cached-prompts (get-cached-prompts client)]
          (boolean (seq cached-prompts))
          (when-let [result-future (list-prompts-impl client)]
            (let [result @result-future]
              (boolean (seq (:prompts result))))))))
    (catch Exception e
      (log/debug :client/available-prompts-error {:error (.getMessage e)})
      false)))

(defn subscribe-prompts-changed-impl!
  "Subscribe to prompts list changed notifications.

  Returns a CompletableFuture that resolves immediately (no server request needed).
  The callback-fn will be called when the server sends prompts/list_changed notifications."
  ^CompletableFuture [client callback-fn]
  (log/info :client/subscribe-prompts-changed)
  (try
    (let [subscription-registry (:subscription-registry client)]
      (subscriptions/subscribe-prompts-changed! subscription-registry callback-fn)
      (CompletableFuture/completedFuture {:subscribed true}))
    (catch Exception e
      (log/error :client/subscribe-prompts-changed-error {:error (.getMessage e)})
      (CompletableFuture/failedFuture
        (ex-info
          "Prompts subscription failed"
          {:error (.getMessage e)}
          e)))))

(defn unsubscribe-prompts-changed-impl!
  "Unsubscribe from prompts list changed notifications.

  Returns a CompletableFuture that resolves immediately."
  ^CompletableFuture [client callback-fn]
  (log/info :client/unsubscribe-prompts-changed)
  (try
    (let [subscription-registry (:subscription-registry client)
          removed? (subscriptions/unsubscribe-prompts-changed! subscription-registry callback-fn)]
      (CompletableFuture/completedFuture {:unsubscribed removed?}))
    (catch Exception e
      (log/error :client/unsubscribe-prompts-changed-error {:error (.getMessage e)})
      (CompletableFuture/failedFuture
        (ex-info
          "Prompts unsubscription failed"
          {:error (.getMessage e)}
          e)))))

(defn setup-cache-invalidation!
  "Set up internal subscription to invalidate prompts cache on list changes.

  This is called automatically when the client is created."
  [client]
  (let [cache-invalidation-callback
        (fn [_notification-params]
          (log/debug :client/prompts-cache-invalidated)
          (let [cache (get-prompts-cache client)]
            (reset! cache nil)))]
    (subscribe-prompts-changed-impl! client cache-invalidation-callback)))
