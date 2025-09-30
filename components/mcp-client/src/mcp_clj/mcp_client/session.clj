(ns mcp-clj.mcp-client.session
  "MCP client session state management"
  (:require
    [mcp-clj.log :as log])
  (:import
    (java.util.concurrent
      CompletableFuture)))

;; Session States

(def session-states #{:disconnected :initializing :ready :error})

;; Session Record

(defrecord Session
  [state ; Current session state
   client-info ; Client information sent in initialize
   capabilities ; Client capabilities
   server-info ; Server info from initialize response
   server-capabilities ; Server capabilities from initialize response
   protocol-version ; Negotiated protocol version
   error-info]) ; Error information if state is :error

;; Session State Transitions

(def ^:private valid-transitions
  {:disconnected #{:initializing :error}
   :initializing #{:ready :error :disconnected}
   :ready #{:error :disconnected}
   :error #{:disconnected}})

(defn- valid-transition?
  "Check if state transition is valid"
  [from-state to-state]
  (contains? (get valid-transitions from-state #{}) to-state))

;; Session Management

(defn create-session
  "Create new session in disconnected state"
  [{:keys [client-info capabilities protocol-version]
    :or {client-info {:name "mcp-clj-client"
                      :title "MCP Clojure Client"
                      :version "0.1.0"}
         capabilities {}
         protocol-version "2025-06-18"}}]
  (->Session
    :disconnected
    client-info
    capabilities
    nil ; server-info
    nil ; server-capabilities
    protocol-version
    nil)) ; error-info

(defn transition-state!
  "Transition session to new state with optional data"
  [session new-state & {:keys [server-info server-capabilities error-info]}]
  (let [current-state (:state session)]
    (if (valid-transition? current-state new-state)
      (do
        (log/debug :session/state-transition
                   {:from current-state :to new-state})
        (cond-> (assoc session :state new-state)
          server-info (assoc :server-info server-info)
          server-capabilities (assoc :server-capabilities server-capabilities)
          error-info (assoc :error-info error-info)
          (= new-state :disconnected) (assoc :server-info nil
                                             :server-capabilities nil
                                             :error-info nil)))
      (do
        (log/error :session/invalid-transition
                   {:from current-state :to new-state})
        (throw (ex-info "Invalid session state transition"
                        {:from-state current-state
                         :to-state new-state
                         :valid-from (get valid-transitions current-state)}))))))

(defn session-ready?
  "Check if session is in ready state"
  [session]
  (= :ready (:state session)))

(defn session-error?
  "Check if session is in error state"
  [session]
  (= :error (:state session)))

(defn get-session-info
  "Get human-readable session information"
  [session]
  {:state (:state session)
   :protocol-version (:protocol-version session)
   :client-info (:client-info session)
   :client-capabilities (:capabilities session)
   :server-info (:server-info session)
   :server-capabilities (:server-capabilities session)
   :error-info (:error-info session)})

(defn server-supports?
  "Check if server supports a specific capability.

  Capability path is a vector of keys, e.g., [:tools] or [:resources :subscribe].
  Returns boolean indicating if the capability is present and truthy."
  [session capability-path]
  (boolean (get-in (:server-capabilities session) capability-path)))

(defn server-supports-tools?
  "Check if server supports tools capability"
  [session]
  (server-supports? session [:tools]))

(defn server-supports-prompts?
  "Check if server supports prompts capability"
  [session]
  (server-supports? session [:prompts]))

(defn server-supports-resources?
  "Check if server supports resources capability"
  [session]
  (server-supports? session [:resources]))

(defn server-supports-sampling?
  "Check if server supports sampling capability"
  [session]
  (server-supports? session [:sampling]))

(defn client-supports?
  "Check if client supports a specific capability.

  Capability path is a vector of keys, e.g., [:sampling] or [:notifications].
  Returns boolean indicating if the capability is present and truthy."
  [session capability-path]
  (boolean (get-in (:capabilities session) capability-path)))

(defn client-supports-sampling?
  "Check if client supports sampling capability"
  [session]
  (client-supports? session [:sampling]))

(defn client-supports-notifications?
  "Check if client supports notification handling"
  [session]
  (client-supports? session [:notifications]))

(defn capability-not-supported
  "Create a failed future for when a capability is not supported.

  Returns a CompletableFuture that completes exceptionally with an ex-info."
  [capability-name session extra-data]
  (log/warn (keyword "client" (str capability-name "-not-supported"))
            {:server-capabilities (:server-capabilities session)})
  (CompletableFuture/failedFuture
    (ex-info (str "Server does not support " capability-name " capability")
             (merge {:server-capabilities (:server-capabilities session)}
                    extra-data))))
