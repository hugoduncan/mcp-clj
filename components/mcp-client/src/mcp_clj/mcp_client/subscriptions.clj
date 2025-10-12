(ns mcp-clj.mcp-client.subscriptions
  "Subscription management for MCP client notifications.

  Manages tracking of subscriptions to server notifications and dispatches
  incoming notifications to registered callbacks."
  (:require
    [mcp-clj.log :as log]))

(defn create-registry
  "Create a new subscription registry.

  Returns a map with:
  - :resource-subscriptions - map of URI -> callback fn
  - :tools-subscriptions - set of callback fns
  - :prompts-subscriptions - set of callback fns
  - :resources-subscriptions - set of callback fns
  - :log-message-subscriptions - set of callback fns"
  []
  {:resource-subscriptions (atom {})
   :tools-subscriptions (atom #{})
   :prompts-subscriptions (atom #{})
   :resources-subscriptions (atom #{})
   :log-message-subscriptions (atom #{})})

(defn subscribe-resource!
  "Subscribe to resource updates for a specific URI.

  Only one callback per URI is supported. Returns the callback function."
  [registry uri callback-fn]
  (swap! (:resource-subscriptions registry) assoc uri callback-fn)
  callback-fn)

(defn unsubscribe-resource!
  "Unsubscribe from resource updates for a specific URI.

  Returns the removed callback function, or nil if not subscribed."
  [registry uri]
  (let [old-callbacks @(:resource-subscriptions registry)
        callback (get old-callbacks uri)]
    (swap! (:resource-subscriptions registry) dissoc uri)
    callback))

(defn get-resource-callback
  "Get the callback function for a specific resource URI.

  Returns nil if no subscription exists for this URI."
  [registry uri]
  (get @(:resource-subscriptions registry) uri))

(defn subscribe-tools-changed!
  "Subscribe to tools list changed notifications.

  Multiple callbacks can be registered. Returns the callback function."
  [registry callback-fn]
  (swap! (:tools-subscriptions registry) conj callback-fn)
  callback-fn)

(defn unsubscribe-tools-changed!
  "Unsubscribe from tools list changed notifications.

  Returns true if the callback was found and removed, false otherwise."
  [registry callback-fn]
  (let [old-subs @(:tools-subscriptions registry)]
    (swap! (:tools-subscriptions registry) disj callback-fn)
    (contains? old-subs callback-fn)))

(defn get-tools-callbacks
  "Get all callback functions for tools list changed notifications.

  Returns a set of callback functions."
  [registry]
  @(:tools-subscriptions registry))

(defn subscribe-prompts-changed!
  "Subscribe to prompts list changed notifications.

  Multiple callbacks can be registered. Returns the callback function."
  [registry callback-fn]
  (swap! (:prompts-subscriptions registry) conj callback-fn)
  callback-fn)

(defn unsubscribe-prompts-changed!
  "Unsubscribe from prompts list changed notifications.

  Returns true if the callback was found and removed, false otherwise."
  [registry callback-fn]
  (let [old-subs @(:prompts-subscriptions registry)]
    (swap! (:prompts-subscriptions registry) disj callback-fn)
    (contains? old-subs callback-fn)))

(defn get-prompts-callbacks
  "Get all callback functions for prompts list changed notifications.

  Returns a set of callback functions."
  [registry]
  @(:prompts-subscriptions registry))

(defn subscribe-resources-changed!
  "Subscribe to resources list changed notifications.

  Multiple callbacks can be registered. Returns the callback function."
  [registry callback-fn]
  (swap! (:resources-subscriptions registry) conj callback-fn)
  callback-fn)

(defn unsubscribe-resources-changed!
  "Unsubscribe from resources list changed notifications.

  Returns true if the callback was found and removed, false otherwise."
  [registry callback-fn]
  (let [old-subs @(:resources-subscriptions registry)]
    (swap! (:resources-subscriptions registry) disj callback-fn)
    (contains? old-subs callback-fn)))

(defn get-resources-callbacks
  "Get all callback functions for resources list changed notifications.

  Returns a set of callback functions."
  [registry]
  @(:resources-subscriptions registry))

(defn subscribe-log-messages!
  "Subscribe to log message notifications.

  Multiple callbacks can be registered. Returns the callback function."
  [registry callback-fn]
  (swap! (:log-message-subscriptions registry) conj callback-fn)
  callback-fn)

(defn unsubscribe-log-messages!
  "Unsubscribe from log message notifications.

  Returns true if the callback was found and removed, false otherwise."
  [registry callback-fn]
  (let [old-subs @(:log-message-subscriptions registry)]
    (swap! (:log-message-subscriptions registry) disj callback-fn)
    (contains? old-subs callback-fn)))

(defn get-log-message-callbacks
  "Get all callback functions for log message notifications.

  Returns a set of callback functions."
  [registry]
  @(:log-message-subscriptions registry))

(defn dispatch-notification!
  "Dispatch an incoming notification to registered callbacks.

  Notification should be a map containing :method and :params keys.
  Handles the following notification types:
  - notifications/resources/updated -> calls resource callback for URI
  - notifications/tools/list_changed -> calls all tools callbacks
  - notifications/prompts/list_changed -> calls all prompts callbacks
  - notifications/resources/list_changed -> calls all resources callbacks
  - notifications/message -> calls all log message callbacks

  Returns the number of callbacks invoked."
  [registry notification]
  (let [method (:method notification)
        params (:params notification {})]
    (case method
      "notifications/resources/updated"
      (if-let [uri (:uri params)]
        (if-let [callback (get-resource-callback registry uri)]
          (do
            (callback params)
            1)
          0)
        0)

      "notifications/tools/list_changed"
      (let [callbacks (get-tools-callbacks registry)]
        (doseq [callback callbacks]
          (callback params))
        (count callbacks))

      "notifications/prompts/list_changed"
      (let [callbacks (get-prompts-callbacks registry)]
        (doseq [callback callbacks]
          (callback params))
        (count callbacks))

      "notifications/resources/list_changed"
      (let [callbacks (get-resources-callbacks registry)]
        (doseq [callback callbacks]
          (callback params))
        (count callbacks))

      "notifications/message"
      (let [callbacks (get-log-message-callbacks registry)
            params-with-kw (update params :level keyword)]
        (doseq [callback callbacks]
          (try
            (callback params-with-kw)
            (catch Exception e
              (log/error :client/log-callback-error
                         {:error e :params params}))))
        (count callbacks))

      ;; Unknown notification type
      0)))
