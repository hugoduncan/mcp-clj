(ns mcp-clj.http
  (:require
   [clojure.data.json :as json]))

(defn response
  "Return a minimal status 200 response map with the given body."
  [body]
  {:status  200
   :headers {}
   :body    body})

(defn status
  "Returns an updated Ring response with the given status."
  [resp status]
  (assoc resp :status status))

(defn header
  "Update a response map with the given header."
  [resp name value]
  (assoc-in resp [:headers name] (str value)))

(defn content-type
  "Add a Content-Type header to the response headers."
  [resp content-type]
  (header resp "Content-Type" content-type))

(defn json-response
  "Create a JSON response with given status"
  [data status-code]
  (-> (response (json/write-str data))
      (status status-code)
      (content-type "application/json")))

(defn text-response
  "Create a JSON response with given status"
  [body status-code]
  (-> (response body)
      (status status-code)
      (content-type "text/plain")))

(def Ok 200)
(def Accepted 202)
(def BadRequest 400)
(def NotFound 404)
(def Unavailable 503)
(def InternalServerError 500)
