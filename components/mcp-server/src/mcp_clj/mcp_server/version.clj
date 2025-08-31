(ns mcp-clj.mcp-server.version
  "MCP protocol version negotiation utilities")

(def ^:private supported-versions
  "Supported MCP protocol versions in descending order (newest first)"
  ["2025-06-18" "2024-11-05"])

(defn get-latest-version
  "Get the latest supported protocol version"
  []
  (first supported-versions))

(defn supported?
  "Check if a protocol version is supported"
  [version]
  (boolean (some #{version} supported-versions)))

(defn negotiate-version
  "Negotiate protocol version according to MCP specification.

  Per MCP spec:
  - If server supports client's version, respond with same version
  - Otherwise, respond with server's latest supported version

  Args:
    client-requested-version - The protocol version requested by client

  Returns:
    Map with keys:
    - :negotiated-version - The version to use for the session
    - :client-was-supported? - Whether the client's version was supported
    - :supported-versions - List of all supported versions"
  [client-requested-version]
  (let [client-supported? (supported? client-requested-version)]
    {:negotiated-version (if client-supported?
                           client-requested-version
                           (get-latest-version))
     :client-was-supported? client-supported?
     :supported-versions supported-versions}))

;;; Version-specific behavior dispatch

(defmulti handle-version-specific-behavior
  "Handle version-specific protocol behavior

  Dispatch on the protocol version for features that differ between versions.

  Args:
    protocol-version - The negotiated protocol version string
    feature-type - Keyword identifying the feature (e.g. :capabilities, :message-format)
    context - Context map with feature-specific data

  Returns:
    Feature-specific result based on the protocol version"
  (fn [protocol-version feature-type context]
    [protocol-version feature-type]))

(defmethod handle-version-specific-behavior :default
  [protocol-version feature-type context]
  (throw (ex-info (str "Unsupported feature for protocol version: " protocol-version)
                  {:protocol-version protocol-version
                   :feature-type feature-type
                   :context context})))

;; Example version-specific implementations (can be extended as needed)
(defmethod handle-version-specific-behavior ["2025-06-18" :capabilities]
  [_ _ context]
  ;; 2025-06-18 capabilities format
  (:capabilities context))

(defmethod handle-version-specific-behavior ["2024-11-05" :capabilities]
  [_ _ context]
  ;; 2024-11-05 capabilities format (same as 2025-06-18 for now)
  (:capabilities context))
