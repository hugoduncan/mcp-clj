(ns mcp-clj.json-rpc.http-server
  "JSON-RPC 2.0 server with MCP Streamable HTTP transport (2025-03-26 spec)"
  (:require
    [clojure.string :as str]
    [mcp-clj.http :as http]
    [mcp-clj.http-server.adapter :as http-server]
    [mcp-clj.json :as json]
    [mcp-clj.json-rpc.executor :as executor]
    [mcp-clj.json-rpc.json-protocol :as json-protocol]
    [mcp-clj.json-rpc.protocols :as protocols]
    [mcp-clj.log :as log]
    [mcp-clj.sse :as sse]))

;; Session Management

(defrecord StreamableHttpSession
  [^String session-id
   sse-reply!-fn
   sse-close!-fn
   ^java.util.concurrent.atomic.AtomicLong event-counter])

(defn- generate-session-id
  []
  (str (java.util.UUID/randomUUID)))

(defn- validate-origin
  "Validate Origin header to prevent DNS rebinding attacks"
  [request allowed-origins]
  (if-let [origin (get (:headers request) "origin")]
    (or (empty? allowed-origins)
        (contains? (set allowed-origins) origin))
    true))

(defn- extract-session-id
  "Extract session ID from X-Session-ID header or query params"
  [request]
  (or (get (:headers request) "x-session-id")
      (get ((:query-params request)) "session_id")))

(defn- extract-last-event-id
  "Extract Last-Event-ID for connection resumption"
  [request]
  (get (:headers request) "last-event-id"))

;; Message Handling

(defn- handle-json-rpc
  "Process a JSON-RPC request or batch"
  [handlers rpc-data request session]
  (letfn [(process-single
            [rpc-call]
            (log/info :rpc/invoke {:method (:method rpc-call) :params (:params rpc-call)})
            (if-let [validation-error (json-protocol/validate-request rpc-call)]
              (json-protocol/json-rpc-error
                (:code (:error validation-error))
                (:message (:error validation-error))
                (:id rpc-call))
              (if-let [handler (get handlers (:method rpc-call))]
                (try
                  (when-let [response (handler request (:params rpc-call))]
                    (log/info :server/handler-response response)
                    (json-protocol/json-rpc-result response (:id rpc-call)))
                  (catch Exception e
                    (log/error :rpc/handler-error {:method (:method rpc-call) :error (.getMessage e)})
                    (json-protocol/json-rpc-error
                      :internal-error
                      (.getMessage e)
                      (:id rpc-call))))
                (json-protocol/json-rpc-error
                  :method-not-found
                  (str "Method not found: " (:method rpc-call))
                  (:id rpc-call)))))]
    (if (vector? rpc-data)
      ;; Batch request
      (if (empty? rpc-data)
        (json-protocol/json-rpc-error :invalid-request "Empty batch")
        (vec (keep process-single rpc-data)))
      ;; Single request
      (process-single rpc-data))))

(defn- handle-post
  "Handle HTTP POST requests (client messages)"
  [executor session-id->session handlers allowed-origins request]
  (try
    (if-not (validate-origin request allowed-origins)
      (do
        (log/warn :http/origin-validation-failed {:origin (get (:headers request) "origin")})
        (http/json-response
          (json-protocol/json-rpc-error :security-error "Invalid origin")
          http/BadRequest))
      (let [session-id (extract-session-id request)
            session (get @session-id->session session-id)
            body-str (slurp (:body request))
            rpc-data (json/parse body-str)]
        (log/info :rpc/http-post
                  {:session-id session-id
                   :has-session (some? session)
                   :rpc-data rpc-data})
        (if (nil? @handlers)
          (http/json-response
            (json-protocol/json-rpc-error
              :internal-error
              "Server not ready - handlers not initialized")
            http/Unavailable)
          (let [result (try
                         ;; Execute synchronously for HTTP transport
                         (handle-json-rpc @handlers rpc-data request session)
                         (catch Exception e
                           (log/error :rpc/handler-error {:error (.getMessage e)})
                           (json-protocol/json-rpc-error
                             :internal-error
                             (.getMessage e))))]
            (http/json-response result http/Ok)))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (log/warn :rpc/json-parse-error {:error (.getMessage e)})
      (http/json-response
        (json-protocol/json-rpc-error
          :parse-error
          (str "Invalid JSON: " (.getMessage e)))
        http/BadRequest))
    (catch clojure.lang.ExceptionInfo e
      (if (= :parse-error (:type (ex-data e)))
        (do
          (log/warn :rpc/json-parse-error {:error (.getMessage e)})
          (http/json-response
            (json-protocol/json-rpc-error
              :parse-error
              (.getMessage e))
            http/BadRequest))
        (do
          (log/error :rpc/post-error {:error (.getMessage e)})
          (http/json-response
            (json-protocol/json-rpc-error
              :internal-error
              (.getMessage e))
            http/InternalServerError))))
    (catch Exception e
      (log/error :rpc/post-error {:error (.getMessage e)})
      (http/json-response
        (json-protocol/json-rpc-error
          :internal-error
          (.getMessage e))
        http/InternalServerError))))

(defn- handle-sse-get
  "Handle SSE stream setup via GET"
  [session-id->session allowed-origins request]
  (try
    (if-not (validate-origin request allowed-origins)
      (do
        (log/warn :http/origin-validation-failed {:origin (get (:headers request) "origin")})
        (http/text-response "Forbidden" 403))
      (let [session-id (or (extract-session-id request) (generate-session-id))
            last-event-id (extract-last-event-id request)
            {:keys [reply! close! response]} (sse/handler request)
            event-counter (java.util.concurrent.atomic.AtomicLong. 0)
            session (->StreamableHttpSession
                      session-id
                      (fn [message]
                        (let [event-id (.incrementAndGet event-counter)]
                          (reply! (assoc (sse/message (json/write message))
                                         :id (str event-id)))))
                      (fn []
                        (log/info :http/sse-close {:session-id session-id})
                        (close!)
                        (swap! session-id->session dissoc session-id))
                      event-counter)]
        (swap! session-id->session assoc session-id session)
        (log/info :http/sse-connect {:session-id session-id :last-event-id last-event-id})

        ;; Set up session ID in response headers
        (-> response
            (update :headers assoc "X-Session-ID" session-id)
            (update :body
                    (fn [body-fn]
                      (fn [& args]
                        (apply body-fn args)
                        ;; Send any missed events if resuming
                        (when last-event-id
                          (log/info :http/sse-resume {:session-id session-id :last-event-id last-event-id}))
                        ;; Connection established
                        (log/info :http/sse-ready {:session-id session-id})))))))
    (catch Exception e
      (log/error :rpc/sse-error {:error (.getMessage e)})
      (http/text-response "Internal Server Error" http/InternalServerError))))

(defn- handle-request
  "Main request handler routing"
  [executor session-id->session handlers allowed-origins {:keys [request-method uri] :as request}]
  (log/info :http/request {:method request-method :uri uri})

  (case [request-method uri]
    ;; Single endpoint for POST requests
    [:post "/"]
    (handle-post executor session-id->session handlers allowed-origins request)

    ;; Optional SSE stream via GET
    [:get "/"]
    (if (str/includes? (get (:headers request) "accept" "") "text/event-stream")
      (handle-sse-get session-id->session allowed-origins request)
      (http/json-response
        {"transport" "streamable-http"
         "version" "2025-03-26"
         "capabilities" {"sse" true
                         "batch" true
                         "resumable" true}}
        http/Ok))

    ;; 404 for unknown endpoints
    (do
      (log/warn :http/not-found
                {:method request-method
                 :uri uri
                 :request request
                 :handlers handlers})
      (http/text-response "Not Found" http/NotFound))))

;; Server Creation

(defn create-server
  "Create JSON-RPC server with MCP Streamable HTTP transport"
  [{:keys [num-threads
           port
           allowed-origins
           on-connect
           on-disconnect]
    :or {num-threads (* 2 (.availableProcessors (Runtime/getRuntime)))
         port 0
         allowed-origins []
         on-connect (fn [& _])
         on-disconnect (fn [& _])}}]
  {:pre [(ifn? on-connect) (ifn? on-disconnect) (coll? allowed-origins)]}
  (let [executor (executor/create-executor num-threads)
        session-id->session (atom {})
        handlers (atom nil)
        handler (partial handle-request
                         executor
                         session-id->session
                         handlers
                         allowed-origins)
        {:keys [server port stop]} (http-server/run-server
                                     handler
                                     {:executor executor :port port})]

    (log/info :http/server-created
              {:port port :allowed-origins allowed-origins})

    {:server server
     :port port
     :handlers handlers
     :session-id->session session-id->session
     :stop (fn []
             (log/info :http/server-stopping)
             (stop)
             (executor/shutdown-executor executor))}))

;; Server Operations

(defn set-handlers!
  "Set the JSON-RPC method handlers"
  [server handlers]
  (when-not (or (map? handlers) (nil? handlers))
    (throw (ex-info "Handlers must be a map or nil"
                    {:handlers handlers})))
  (reset! (:handlers server) handlers))

(defn close-session!
  "Close a specific session"
  [server session-id]
  (when-let [session (get @(:session-id->session server) session-id)]
    (log/info :http/closing-session {:session-id session-id})
    ((:sse-close!-fn session))
    (swap! (:session-id->session server) dissoc session-id)))

(defn notify-all!
  "Send a notification to all active sessions with SSE streams"
  [server method params]
  (log/info :http/notify-all {:method method :params params})
  (let [sessions @(:session-id->session server)
        notification (json-protocol/json-rpc-notification method params)]
    (doseq [{:keys [session-id sse-reply!-fn]} (vals sessions)]
      (when sse-reply!-fn
        (log/debug :http/notifying-session {:session-id session-id})
        (sse-reply!-fn notification)))))

(defn notify!
  "Send a notification to a specific session"
  [server session-id method params]
  (log/info :http/notify {:session-id session-id :method method :params params})
  (when-let [session (get @(:session-id->session server) session-id)]
    (when-let [reply-fn (:sse-reply!-fn session)]
      (reply-fn (json-protocol/json-rpc-notification method params)))))

(defn get-sessions
  "Get all active session IDs"
  [server]
  (keys @(:session-id->session server)))

;; Protocol Implementation

(extend-type clojure.lang.PersistentArrayMap
  protocols/JsonRpcServer
  (set-handlers! [server handlers]
    (set-handlers! server handlers))

  (notify-all! [server method params]
    (notify-all! server method params))

  (stop! [server]
    ((:stop server))))
