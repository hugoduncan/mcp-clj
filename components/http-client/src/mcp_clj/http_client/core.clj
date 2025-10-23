(ns mcp-clj.http-client.core
  "HTTP client implementation using JDK HttpClient"
  (:require
    [clojure.string :as str]
    [mcp-clj.json :as json])
  (:import
    (java.net
      URI)
    (java.net.http
      HttpClient
      HttpRequest
      HttpRequest$BodyPublishers
      HttpResponse
      HttpResponse$BodyHandler
      HttpResponse$BodyHandlers)
    (java.time
      Duration)))

;; HTTP Client Creation

(defn create-client
  "Create an HTTP client with optional configuration"
  (^HttpClient []
   (create-client {}))
  (^HttpClient [{:keys [connect-timeout follow-redirects]
                 :or {connect-timeout 30000
                      follow-redirects :normal}}]
   (let [builder (HttpClient/newBuilder)]
     (when connect-timeout
       (.connectTimeout builder (Duration/ofMillis connect-timeout)))
     (case follow-redirects
       :never (.followRedirects
                builder
                java.net.http.HttpClient$Redirect/NEVER)
       :always (.followRedirects
                 builder
                 java.net.http.HttpClient$Redirect/ALWAYS)
       :normal (.followRedirects
                 builder
                 java.net.http.HttpClient$Redirect/NORMAL))
     (.build builder))))

;; Request Building

(defn- build-uri
  "Build URI from URL string"
  [url]
  (URI/create url))

(defn- build-body
  "Build request body publisher"
  [body]
  (cond
    (nil? body) (HttpRequest$BodyPublishers/noBody)
    (string? body) (HttpRequest$BodyPublishers/ofString body)
    :else (HttpRequest$BodyPublishers/ofString (str body))))

(defn- build-request
  "Build HTTP request"
  ^HttpRequest [{:keys [method url headers body timeout]}]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri (build-uri url)))]

    ;; Add headers
    (doseq [[k v] headers]
      (.header builder (name k) (str v)))

    ;; Set method and body
    (case (keyword method)
      :get (.GET builder)
      :post (.POST builder (build-body body))
      :put (.PUT builder (build-body body))
      :delete (.DELETE builder)
      (.method builder (str/upper-case (name method)) (build-body body)))

    ;; Set timeout
    (when timeout
      (.timeout builder (Duration/ofMillis timeout)))

    (.build builder)))

;; Response Processing

(defn- parse-headers
  "Parse HTTP response headers into a map"
  [^HttpResponse http-response]
  (into {}
        (map (fn [[k v]] [k (first v)]))
        (.map (.headers http-response))))

(defn- process-response
  "Process HTTP response based on options"
  [^HttpResponse http-response {:keys [as] :or {as :string}}]
  (let [status (.statusCode http-response)
        headers (parse-headers http-response)
        body (.body http-response)]
    {:status status
     :headers headers
     :body (case as
             :json (when (and body (not (str/blank? body)))
                     (json/parse body))
             :stream body
             :string body
             body)}))

;; Public API

(defn request
  "Send HTTP request and return response"
  ([client request-opts]
   (request client request-opts {}))
  ([^HttpClient client request-opts response-opts]
   (let [http-request (build-request request-opts)
         ^HttpResponse$BodyHandler body-handler
         (case (:as response-opts :string)
           :stream (HttpResponse$BodyHandlers/ofInputStream)
           :string (HttpResponse$BodyHandlers/ofString)
           (HttpResponse$BodyHandlers/ofString))
         http-response (.send client http-request body-handler)]
     (process-response http-response response-opts))))

(defn http-get
  "Send HTTP GET request"
  ([url]
   (http-get url {}))
  ([url opts]
   (let [client (create-client)
         request-opts (merge {:method :get :url url} opts)
         response-opts (select-keys opts [:as :throw-exceptions])]
     (request client request-opts response-opts))))

(defn http-post
  "Send HTTP POST request"
  ([url opts]
   (let [client (create-client)
         request-opts (merge {:method :post :url url} opts)
         response-opts (select-keys opts [:as :throw-exceptions])]
     (request client request-opts response-opts))))

(defn http-put
  "Send HTTP PUT request"
  ([url opts]
   (let [client (create-client)
         request-opts (merge {:method :put :url url} opts)
         response-opts (select-keys opts [:as :throw-exceptions])]
     (request client request-opts response-opts))))

(defn http-delete
  "Send HTTP DELETE request"
  ([url]
   (http-delete url {}))
  ([url opts]
   (let [client (create-client)
         request-opts (merge {:method :delete :url url} opts)
         response-opts (select-keys opts [:as :throw-exceptions])]
     (request client request-opts response-opts))))
