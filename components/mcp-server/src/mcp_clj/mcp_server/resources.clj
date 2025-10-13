(ns mcp-clj.mcp-server.resources
  "MCP resource endpoints"
  (:require
    [mcp-clj.log :as log]))

(defn- valid-string?
  [x]
  (and (string? x)
       (pos? (count x))))

(defn- valid-uri?
  [x]
  (try
    (java.net.URI. x)
    true
    (catch Exception _
      false)))

(defn- valid-audience-value?
  [x]
  (#{"user" "assistant"} x))

(defn- valid-audience?
  [x]
  (and (vector? x)
       (every? valid-audience-value? x)))

(defn- valid-priority?
  [x]
  (and (number? x)
       (<= 0 x 1)))

(defn- valid-annotations?
  [annotations]
  (and (map? annotations)
       (or (nil? (:priority annotations))
           (valid-priority? (:priority annotations)))
       (or (nil? (:audience annotations))
           (valid-audience? (:audience annotations)))))

(defn valid-resource?
  "Validate a resource definition.
   Returns true if valid, throws ex-info with explanation if not."
  [{:keys [name uri mime-type annotations] :as resource}]
  (when-not (valid-string? name)
    (throw (ex-info "name must be a non-empty string"
                    {:type :validation-error
                     :field :name
                     :value name
                     :resource resource})))
  (when-not (valid-uri? uri)
    (throw (ex-info "uri must be a valid URI string"
                    {:type :validation-error
                     :field :uri
                     :value uri
                     :resource resource})))
  (when (and mime-type (not (valid-string? mime-type)))
    (throw (ex-info "mime-type must be a non-empty string"
                    {:type :validation-error
                     :field :mime-type
                     :value mime-type
                     :resource resource})))
  (when (and annotations (not (valid-annotations? annotations)))
    (throw (ex-info "invalid annotations"
                    {:type :validation-error
                     :field :annotations
                     :value annotations
                     :resource resource})))
  true)

(defn resource-definition
  "Convert a ResourceDefinition to the wire format"
  [{:keys [name uri mime-type description annotations]}]
  (cond-> {:name name
           :uri uri}
    mime-type (assoc :mimeType mime-type)
    description (assoc :description description)
    annotations (assoc :annotations annotations)))

(def default-resources {})

(defn list-resources
  "List available resources.
   Returns a map with :resources containing resource definitions."
  [registry params]
  (log/info :resources/list {:params params})
  {:resources (mapv resource-definition (vals @registry))})

(defn- read-resource-impl
  "Default implementation for reading a resource"
  [context {:keys [implementation] :as _resource} uri]
  (if implementation
    (implementation context uri)
    {:contents [{:uri uri
                 :text "Resource not implemented"}]
     :isError true}))

(defn read-resource
  "Read a resource by URI.
   Returns contents of the resource."
  [context registry {:keys [uri] :as params}]
  (log/info :resources/read {:params params})
  (if-let [resource (some #(when (= uri (:uri %)) %) (vals @registry))]
    (read-resource-impl context resource uri)
    {:contents [{:uri uri
                 :text "Resource not found"}]
     :isError true}))
