(ns mcp-clj.mcp-server.version
  "MCP protocol version negotiation utilities"
  (:require
    [mcp-clj.versions :as versions]))

(defn negotiate-version
  "Negotiate protocol version according to MCP specification.

  Per MCP spec:
  - Use the highest version supported by both client and server
  - If no common version, server should reject the connection

  Args:
    client-requested-version - The protocol version requested by client

  Returns:
    Map with keys:
    - :negotiated-version - The version to use for the session
    - :client-was-supported? - Whether the client's version was supported
    - :supported-versions - List of all supported versions"
  [client-requested-version]
  (let [client-supported?  (versions/supported? client-requested-version)
        negotiated-version (if client-supported?
                             client-requested-version
                             ;; Find the highest version supported by both. For
                             ;; now, we assume client supports standard versions
                             ;; In a real implementation, we'd need client's
                             ;; supported versions
                             (versions/get-latest-version))]
    {:negotiated-version    negotiated-version
     :client-was-supported? client-supported?
     :supported-versions    versions/supported-versions}))

;; Version comparison utilities

(defn version-gte?
  "Check if version1 is greater than or equal to version2"
  [version1 version2]
  (>= (compare version1 version2) 0))

(defn supports-audio?
  "Check if version supports audio content"
  [protocol-version]
  (version-gte? protocol-version "2025-03-26"))

(defn supports-structured-content?
  "Check if version supports structured content"
  [protocol-version]
  (version-gte? protocol-version "2025-06-18"))

(defn requires-protocol-header?
  "Check if version requires MCP-Protocol-Version header"
  [protocol-version]
  (version-gte? protocol-version "2025-06-18"))

(defn supports-nested-capabilities?
  "Check if version supports nested capability structure"
  [protocol-version]
  (version-gte? protocol-version "2024-11-05"))

;; Version-specific behavior dispatch

(defmulti handle-version-specific-behavior
  "Handle version-specific protocol behavior

  Dispatch on the protocol version for features that differ between versions.

  Args:
    protocol-version - The negotiated protocol version string
    feature-type - Keyword identifying the feature (e.g. :capabilities, :server-info, :content-types)
    context - Context map with feature-specific data

  Returns:
    Feature-specific result based on the protocol version"
  (fn [protocol-version feature-type _context]
    [protocol-version feature-type]))

(defmethod handle-version-specific-behavior :default
  [protocol-version feature-type context]
  (throw
    (ex-info (str "Unsupported feature for protocol version: " protocol-version)
             {:protocol-version protocol-version
              :feature-type     feature-type
              :context          context})))

;; Capabilities handling

(defmethod handle-version-specific-behavior ["2025-06-18" :capabilities]
  [_ _ {:keys [capabilities]}]
  ;; 2025-06-18 supports nested sub-capabilities
  capabilities)

(defmethod handle-version-specific-behavior ["2025-03-26" :capabilities]
  [_ _ {:keys [capabilities]}]
  ;; 2025-03-26 supports nested sub-capabilities
  capabilities)

(defmethod handle-version-specific-behavior ["2024-11-05" :capabilities]
  [_ _ {:keys [capabilities]}]
  ;; 2024-11-05 supports nested sub-capabilities
  capabilities)

;; Server info handling

(defmethod handle-version-specific-behavior ["2025-06-18" :server-info]
  [_ _ {:keys [server-info]}]
  ;; 2025-06-18 supports title field
  server-info)

(defmethod handle-version-specific-behavior ["2025-03-26" :server-info]
  [_ _ {:keys [server-info]}]
  ;; 2025-03-26 does not support title field
  (dissoc server-info :title))

(defmethod handle-version-specific-behavior ["2024-11-05" :server-info]
  [_ _ {:keys [server-info]}]
  ;; 2024-11-05 does not support title field
  (dissoc server-info :title))

;; Content type validation

(defmethod handle-version-specific-behavior ["2025-06-18" :content-types]
  [_ _ {:keys [content-types]}]
  ;; 2025-06-18 supports all content types including structured content
  (filter #(contains? #{"text" "image" "resource" "audio"} (:type %)) content-types))

(defmethod handle-version-specific-behavior ["2025-03-26" :content-types]
  [_ _ {:keys [content-types]}]
  ;; 2025-03-26 supports text, image, resource, and audio
  (filter #(contains? #{"text" "image" "resource" "audio"} (:type %)) content-types))

(defmethod handle-version-specific-behavior ["2024-11-05" :content-types]
  [_ _ {:keys [content-types]}]
  ;; 2024-11-05 supports only text, image, and resource
  (filter #(contains? #{"text" "image" "resource"} (:type %)) content-types))

;; Tool response formatting

(defmethod handle-version-specific-behavior ["2025-06-18" :tool-response]
  [_ _ {:keys [content isError structured-content]}]
  ;; 2025-06-18 can include structured content
  (cond-> {:content content :isError isError}
    structured-content (assoc :structuredContent structured-content)))

(defmethod handle-version-specific-behavior ["2025-03-26" :tool-response]
  [_ _ {:keys [content isError]}]
  ;; 2025-03-26 does not support structured content
  {:content content :isError isError})

(defmethod handle-version-specific-behavior ["2024-11-05" :tool-response]
  [_ _ {:keys [content isError]}]
  ;; 2024-11-05 does not support structured content
  {:content content :isError isError})

;; Header validation

(defmethod handle-version-specific-behavior ["2025-06-18" :headers]
  [_protocol-version _ {:keys [headers]}]
  ;; 2025-06-18 requires MCP-Protocol-Version header
  (let [mcp-version-header (get headers "mcp-protocol-version")]
    (if mcp-version-header
      {:valid? true :protocol-version mcp-version-header}
      {:valid? false
       :error "MCP-Protocol-Version header is required for version 2025-06-18"})))

(defmethod handle-version-specific-behavior ["2025-03-26" :headers]
  [_protocol-version _ {:keys [headers]}]
  ;; 2025-03-26 does not require MCP-Protocol-Version header
  (let [mcp-version-header (get headers "mcp-protocol-version")]
    {:valid? true :protocol-version mcp-version-header}))

(defmethod handle-version-specific-behavior ["2024-11-05" :headers]
  [_protocol-version _ {:keys [headers]}]
  ;; 2024-11-05 does not require MCP-Protocol-Version header
  (let [mcp-version-header (get headers "mcp-protocol-version")]
    {:valid? true :protocol-version mcp-version-header}))

;; Utility functions that depend on multimethod

(defn validate-content-types
  "Validate content types are supported for the given protocol version"
  [protocol-version content-items]
  (when (seq content-items)
    (handle-version-specific-behavior
      protocol-version
      :content-types
      {:content-types content-items})))

(defn validate-headers
  "Validate headers are correct for the given protocol version"
  [protocol-version headers]
  (handle-version-specific-behavior
    protocol-version
    :headers
    {:headers headers}))
