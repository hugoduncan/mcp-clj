(ns mcp-clj.mcp-server.subscriptions
  "Subscription management for MCP resources.

  Manages tracking of which sessions are subscribed to which resources.
  Used for sending notifications when resources change.")

(defn subscribe!
  "Add a subscription for session-id to uri.
  Returns updated subscriptions map."
  [subscriptions session-id uri]
  (update subscriptions uri (fnil conj #{}) session-id))

(defn unsubscribe!
  "Remove a subscription for session-id from uri.
  Returns updated subscriptions map."
  [subscriptions session-id uri]
  (let [updated (update subscriptions uri disj session-id)]
    (if (empty? (get updated uri))
      (dissoc updated uri)
      updated)))

(defn unsubscribe-all!
  "Remove all subscriptions for session-id across all resources.
  Returns updated subscriptions map."
  [subscriptions session-id]
  (reduce-kv
    (fn [acc uri subscribers]
      (let [updated-subscribers (disj subscribers session-id)]
        (if (empty? updated-subscribers)
          acc
          (assoc acc uri updated-subscribers))))
    {}
    subscriptions))

(defn get-subscribers
  "Get set of session-ids subscribed to uri.
  Returns empty set if no subscribers."
  [subscriptions uri]
  (get subscriptions uri #{}))
