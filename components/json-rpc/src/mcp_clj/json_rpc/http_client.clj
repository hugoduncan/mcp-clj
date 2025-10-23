(ns mcp-clj.json-rpc.http-client
  "JSON-RPC client for HTTP transport with SSE support"
  (:require
    [clojure.string :as str]
    [mcp-clj.http-client.core :as http-client]
    [mcp-clj.json :as json]
    [mcp-clj.json-rpc.executor :as executor]
    [mcp-clj.json-rpc.protocols :as protocols]
    [mcp-clj.log :as log])
  (:import
    (java.io
      BufferedReader
      InputStreamReader)
    (java.util.concurrent
      CompletableFuture
      ConcurrentHashMap
      TimeUnit)))

;; Forward declarations
(declare http-send-request! http-send-notification! close-http-json-rpc-client!)

;; HTTP JSON-RPC Client

(defrecord HTTPJSONRPCClient
  [base-url ; Base URL for the MCP server
   session-id ; atom holding Session ID for this client
   pending-requests ; ConcurrentHashMap of request-id -> CompletableFuture
   request-id-counter ; atom for generating unique request IDs
   executor ; executor for async operations
   running ; atom for controlling SSE reader
   sse-connection ; atom holding SSE connection details
   notification-handler] ; function to handle notifications

  protocols/JSONRPCClient

  (send-request!
    [this method params timeout-ms]
    (http-send-request! this method params timeout-ms))


  (send-notification!
    [this method params]
    (http-send-notification! this method params))


  (close!
    [this]
    (close-http-json-rpc-client! this))


  (alive?
    [this]
    @(:running this)))

;; Request/Response Handling

(defn generate-request-id
  "Generate unique request ID"
  [client]
  (swap! (:request-id-counter client) inc))

(defn- make-headers
  "Create headers for HTTP request"
  [client]
  (cond-> {"Content-Type" "application/json"
           "Accept" "application/json"}
    @(:session-id client)
    (assoc "X-Session-ID" @(:session-id client))))

(defn- handle-response
  "Handle JSON-RPC response by completing the corresponding future"
  [client {:keys [id result error] :as response}]
  (if id
    ;; Normalize ID to Long to handle Integer/Long mismatch from JSON parsing
    (let [normalized-id (long id)]
      (if-let [future (.remove ^ConcurrentHashMap (:pending-requests client) normalized-id)]
        (if error
          (.completeExceptionally ^CompletableFuture future
                                  (ex-info "JSON-RPC error" error))
          (.complete ^CompletableFuture future result))
        (log/warn :rpc/orphan-response {:response response})))
    ;; No ID means it's a notification
    (when-let [handler (:notification-handler client)]
      (handler response))))

(defn- process-json-rpc-response
  "Process a JSON-RPC response or batch of responses"
  [client response-data]
  (log/debug :client/pprocess-json-rpc {:response-data response-data})
  (if (vector? response-data)
    ;; Batch response
    (doseq [response response-data]
      (handle-response client response))
    ;; Single response
    (handle-response client response-data)))

;; SSE Handling

(defn- parse-sse-event
  "Parse SSE event data"
  [event-lines]
  (let [event-map (reduce (fn [acc line]
                            (if-let [[_ field value] (re-matches #"^([^:]+):\s*(.*)$" line)]
                              (case field
                                "data" (update acc :data (fnil conj []) value)
                                "event" (assoc acc :event value)
                                "id" (assoc acc :id value)
                                "retry" (assoc acc :retry (Long/parseLong value))
                                acc)
                              acc))
                          {}
                          event-lines)]
    (when (seq (:data event-map))
      (assoc event-map :data (str/join "\n" (:data event-map))))))

(defn- sse-reader-loop
  "Read SSE events from the server"
  [client ^BufferedReader reader]
  (try
    (loop [event-lines []]
      (when @(:running client)
        (if-let [line (.readLine reader)]
          (if (str/blank? line)
            ;; Empty line signals end of event
            (when-let [event (parse-sse-event event-lines)]
              (try
                (let [data (json/parse (:data event))]
                  (log/debug :sse/event {:event event :data data})
                  (handle-response client data))
                (catch Exception e
                  (log/error :sse/parse-error {:error e :event event})))
              (recur []))
            ;; Accumulate lines for this event
            (recur (conj event-lines line)))
          ;; Stream closed
          (do
            (log/info :sse/stream-closed)
            (reset! (:sse-connection client) nil)))))
    (catch Exception e
      (log/error :sse/reader-error {:error e})
      (reset! (:sse-connection client) nil))))

(defn- start-sse-connection!
  "Start SSE connection for receiving server notifications"
  [client]
  (when @(:running client)
    (try
      (let [url (str (:base-url client) "/")
            headers (make-headers client)
            response (http-client/http-get url
                                           {:headers headers
                                            :as :stream
                                            :throw-exceptions false})]
        (if (= 200 (:status response))
          (let [reader (BufferedReader. (InputStreamReader. (:body response)))
                future (executor/submit! (:executor client)
                                         #(sse-reader-loop client reader))]
            (reset! (:sse-connection client)
                    {:reader reader
                     :future future
                     :response response})
            (log/info :sse/connected {:url url :session-id @(:session-id client)}))
          (log/error :sse/connection-failed {:status (:status response)
                                             :body (slurp (:body response))})))
      (catch Exception e
        (log/error :sse/connection-error {:error e})))))

;; Public API

(defn create-http-json-rpc-client
  "Create an HTTP JSON-RPC client"
  [{:keys [url session-id notification-handler num-threads]
    :or {num-threads 2}}]
  (let [client (->HTTPJSONRPCClient
                 url
                 (atom session-id)
                 (ConcurrentHashMap.)
                 (atom 0)
                 (executor/create-executor num-threads)
                 (atom true)
                 (atom nil)
                 notification-handler)]
    ;; Don't start SSE immediately - let it be established after first request with session
    client))

(defn http-send-request!
  "Send JSON-RPC request and return CompletableFuture with response"
  [client method params timeout-ms]
  (let [request-id (generate-request-id client)
        request {:jsonrpc "2.0"
                 :id request-id
                 :method method
                 :params params}
        future (CompletableFuture.)]

    ;; Store the future for this request
    (.put ^ConcurrentHashMap (:pending-requests client) request-id future)

    ;; Send HTTP POST request
    (executor/submit!
      (:executor client)
      (fn []
        (try
          (log/debug :client/send "Send request" {:method method})
          (let [url (str (:base-url client) "/")
                headers (make-headers client)
                response (http-client/http-post url
                                                {:headers headers
                                                 :body (json/write request)
                                                 :content-type :json
                                                 :accept :json
                                                 :as :json
                                                 :throw-exceptions false})]
            (log/debug :client/send
                       {:msg "Receive response"
                        :response response})
            (if (= 200 (:status response))
              (do
                ;; Update session ID if provided
                (when-let [new-session-id (get-in
                                            response
                                            [:headers "x-session-id"])]
                  (when (not= new-session-id @(:session-id client))
                    (log/info :http/session-updated {:old @(:session-id client)
                                                     :new new-session-id})
                    ;; Update client's session ID
                    (reset! (:session-id client) new-session-id)
                    ;; Start SSE with new session
                    (when-not @(:sse-connection client)
                      (start-sse-connection! client))))

                ;; Process response
                (.complete
                  future
                  (process-json-rpc-response
                    client
                    (:body response))))
              ;; HTTP error
              (let [error-msg (str "HTTP error: " (:status response) " - "
                                   (or (:body response) ""))]
                (log/error
                  :http/request-failed
                  {:status (:status response)
                   :body (:body response)})
                (.completeExceptionally
                  future
                  (ex-info
                    error-msg
                    {:status (:status response)
                     :body (:body response)})))))
          (catch Exception e
            (log/error :http/request-error {:error e :method method})
            (.completeExceptionally future e)))))

    ;; Set timeout
    (try
      (.get future timeout-ms TimeUnit/MILLISECONDS)
      (catch Exception e
        ;; Remove from pending if timeout/error
        (.remove ^ConcurrentHashMap (:pending-requests client) request-id)
        (throw e)))
    future))

(defn http-send-notification!
  "Send JSON-RPC notification (no response expected)"
  [client method params]
  (executor/submit! (:executor client)
                    (fn []
                      (try
                        (let [url (str (:base-url client) "/")
                              headers (make-headers client)
                              notification {:jsonrpc "2.0"
                                            :method method
                                            :params params}
                              response (http-client/http-post
                                         url
                                         {:headers headers
                                          :body (json/write notification)
                                          :content-type :json
                                          :throw-exceptions false})]
                          (when (not= 200 (:status response))
                            (log/warn :http/notification-failed
                                      {:status (:status response)
                                       :body (:body response)})))
                        (catch Exception e
                          (log/error :http/notification-error
                                     {:error e :method method}))))))

(defn close-http-json-rpc-client!
  "Close HTTP JSON-RPC client"
  [client]
  (reset! (:running client) false)

  ;; Close SSE connection
  (when-let [conn @(:sse-connection client)]
    (try
      (.close ^BufferedReader (:reader conn))
      (catch Exception _))
    (when-let [fut (:future conn)]
      (future-cancel fut)))

  ;; Cancel pending requests
  (doseq [^CompletableFuture future (.values ^ConcurrentHashMap (:pending-requests client))]
    (.completeExceptionally future (ex-info "Client closed" {:reason :client-closed})))
  (.clear ^ConcurrentHashMap (:pending-requests client))

  ;; Shutdown executor
  (executor/shutdown-executor (:executor client)))

;; Public API Functions (for backward compatibility)

(defn send-request!
  "Send JSON-RPC request and return CompletableFuture with response"
  [client method params timeout-ms]
  (protocols/send-request! client method params timeout-ms))

(defn send-notification!
  "Send JSON-RPC notification (no response expected)"
  [client method params]
  (protocols/send-notification! client method params))

(defn update-session-id!
  "Update the session ID and restart SSE connection if needed"
  [client new-session-id]
  (when (not= new-session-id @(:session-id client))
    ;; Close existing SSE if any
    (when-let [conn @(:sse-connection client)]
      (try
        (.close ^BufferedReader (:reader conn))
        (catch Exception _)))

    ;; Update session ID
    (reset! (:session-id client) new-session-id)

    ;; Restart SSE with new session
    (when new-session-id
      (start-sse-connection! client))))
