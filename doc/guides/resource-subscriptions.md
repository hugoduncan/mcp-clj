# Resource Subscriptions Guide

This guide explains how to use resource subscriptions in mcp-clj to receive real-time notifications when resources change.

## Overview

Resource subscriptions allow clients to monitor specific resources and receive notifications when they are updated. This is more efficient than polling, as the server pushes updates only when changes occur.

### When to Use Subscriptions

Use resource subscriptions when:
- You need real-time updates about specific resources
- You want to avoid polling for changes
- The resource content changes independently of client requests
- You need to keep UI or caches in sync with server-side data

### Subscription Lifecycle

1. **Subscribe**: Client sends `resources/subscribe` request with resource URI
2. **Track**: Server tracks which clients are subscribed to which resources
3. **Notify**: When resource changes, server sends `notifications/resources/updated` to subscribed clients
4. **Unsubscribe**: Client sends `resources/unsubscribe` when no longer interested

## Client Usage

### Prerequisites

Ensure the server supports subscriptions by checking capabilities:

```clojure
(require '[mcp-clj.mcp-client.core :as client])

(def mcp-client (client/create-client {...}))

;; Wait for initialization
(let [init-result (client/wait-for-ready mcp-client)]
  (if (get-in init-result [:capabilities :resources :subscribe])
    (println "Server supports resource subscriptions")
    (println "Server does NOT support subscriptions")))
```

### Subscribing to a Resource

Subscribe by providing a URI and a callback function:

```clojure
(client/subscribe-resource!
  mcp-client
  "file:///project/config.json"
  (fn [notification]
    (println "Resource updated!")
    (println "  URI:" (:uri notification))
    (when-let [meta (:meta notification)]
      (println "  Metadata:" meta))))
```

The callback function receives a notification map with:
- `:uri` - The resource URI that was updated
- `:meta` - Optional metadata about the update (map)

### Unsubscribing

Remove a subscription when you no longer need updates:

```clojure
(client/unsubscribe-resource! mcp-client "file:///project/config.json")
```

**Note:** Subscriptions are automatically cleaned up when the client closes.

### Complete Example

```clojure
(require '[mcp-clj.mcp-client.core :as client])

(defn handle-config-update [notification]
  (println "Config file changed!")
  ;; Re-read the resource to get updated content
  @(client/read-resource mcp-client (:uri notification)))

(defn run-client []
  (with-open [mcp-client (client/create-client
                          {:transport {:type :stdio
                                       :command "my-server"}
                           :client-info {:name "config-monitor"
                                         :version "1.0.0"}})]
    ;; Wait for ready
    (client/wait-for-ready mcp-client 5000)

    ;; Subscribe to config file
    @(client/subscribe-resource!
       mcp-client
       "file:///project/config.json"
       handle-config-update)

    ;; Do work...
    (Thread/sleep 60000)

    ;; Cleanup happens automatically via with-open
    ))
```

## Server Usage

### Enabling Subscription Support

Declare subscription support in server capabilities:

```clojure
(require '[mcp-clj.mcp-server.core :as server])

(def mcp-server
  (server/create-server
    {:capabilities {:resources {:subscribe true
                                :listChanged true}}}))
```

**Important:** Setting `subscribe: true` enables:
- Automatic handling of `resources/subscribe` requests
- Automatic handling of `resources/unsubscribe` requests
- Session-based subscription tracking
- Automatic cleanup when sessions close

### Registering Resources

Register resources as usual:

```clojure
(server/add-resource! mcp-server
  {:uri "file:///project/config.json"
   :name "Project Configuration"
   :description "Main configuration file"
   :mime-type "application/json"
   :implementation (fn [_context _uri]
                     {:contents [{:uri "file:///project/config.json"
                                  :mimeType "application/json"
                                  :text (slurp "config.json")}]})})
```

### Sending Update Notifications

When a resource changes, notify subscribers:

```clojure
;; Simple notification
(server/notify-resource-updated! mcp-server "file:///project/config.json")

;; With metadata
(server/notify-resource-updated!
  mcp-server
  "file:///project/config.json"
  {:reason "manual-edit"
   :timestamp (System/currentTimeMillis)})
```

**Note:** Only clients subscribed to that specific URI will receive the notification.

### Complete Server Example

```clojure
(require '[mcp-clj.mcp-server.core :as server])

(def config-file "config.json")

(defn watch-config-file [mcp-server]
  "Watch config file and notify on changes"
  (let [watcher (java.nio.file.FileSystems/getDefault)
        path (java.nio.file.Paths/get config-file (into-array String []))]
    ;; Simplified file watching - use proper file watcher in production
    (future
      (loop [last-modified (.lastModified (java.io.File. config-file))]
        (Thread/sleep 1000)
        (let [current-modified (.lastModified (java.io.File. config-file))]
          (when (> current-modified last-modified)
            (server/notify-resource-updated!
              mcp-server
              "file:///project/config.json"
              {:timestamp current-modified}))
          (recur current-modified))))))

(defn run-server []
  (let [mcp-server (server/create-server
                     {:capabilities {:resources {:subscribe true}}})]

    ;; Register the config resource
    (server/add-resource! mcp-server
      {:uri "file:///project/config.json"
       :name "Config"
       :mime-type "application/json"
       :implementation (fn [_ctx _uri]
                         {:contents [{:uri "file:///project/config.json"
                                      :mimeType "application/json"
                                      :text (slurp config-file)}]})})

    ;; Start watching for changes
    (watch-config-file mcp-server)

    ;; Server runs until stopped
    @(promise)))
```

## Best Practices

### For Clients

1. **Check Capabilities**: Always verify the server supports subscriptions before attempting to subscribe
2. **Handle Errors**: Wrap subscription calls in try-catch to handle unsupported or invalid URIs
3. **Unsubscribe**: Explicitly unsubscribe when done to free server resources
4. **Callback Performance**: Keep notification callbacks fast; offload heavy work to background threads

```clojure
;; Good: Fast callback with async work
(client/subscribe-resource!
  client
  uri
  (fn [notification]
    (future  ; Offload to background thread
      (process-update notification))))

;; Bad: Slow blocking callback
(client/subscribe-resource!
  client
  uri
  (fn [notification]
    (expensive-database-query notification)))  ; Blocks notification thread!
```

### For Servers

1. **Validate URIs**: Only allow subscriptions to resources that actually exist
2. **Throttle Notifications**: Avoid sending too many notifications in quick succession
3. **Batch Updates**: If multiple resources change together, consider `resources/list_changed` instead
4. **Include Metadata**: Provide useful context in notification metadata

```clojure
;; Good: Informative metadata
(server/notify-resource-updated!
  server
  uri
  {:change-type "content-update"
   :affected-sections ["database" "cache"]
   :triggered-by "admin-user"})

;; Acceptable: No metadata
(server/notify-resource-updated! server uri)
```

## Troubleshooting

### "Method not found: resources/subscribe"

**Problem**: Server doesn't implement subscription methods.

**Solutions**:
- Verify server declares `subscribe: true` in capabilities
- Check server version supports subscriptions
- For Java SDK 0.11.2: Known limitation - capability is advertised but methods aren't implemented

### No Notifications Received

**Checklist**:
1. Is the client actually subscribed? Check return value from `subscribe-resource!`
2. Is the resource URI correct? Must match exactly
3. Is the server calling `notify-resource-updated!` when changes occur?
4. Check server logs for subscription tracking

### Memory Leaks with Subscriptions

**Problem**: Server memory grows over time.

**Solutions**:
- Ensure subscriptions are cleaned up when sessions close
- Set `subscribe: true` in server capabilities (enables automatic cleanup)
- Monitor active subscription count in production

## Reference

### Client API

- `(subscribe-resource! client uri callback-fn)` - Subscribe to resource updates
- `(unsubscribe-resource! client uri)` - Remove subscription
- `(read-resource client uri)` - Read current resource content

### Server API

- `(notify-resource-updated! server uri)` - Notify subscribers of update
- `(notify-resource-updated! server uri meta)` - Notify with metadata
- `(notify-resources-list-changed server)` - Notify that resource list changed

### Notification Format

Client callbacks receive a map:

```clojure
{:uri "file:///project/config.json"
 :meta {:timestamp 1633024800000
        :reason "file-modified"}}
```

## Related Documentation

- [MCP Specification - Resources](../../spec/mcp-protocol/docs/specification/2024-11-05/server/resources.mdx)
- [Java SDK Resource Subscriptions](../java-sdk-resource-subscriptions.md)
- [Server Logging Guide](./server-logging.md)
- [Client Logging Guide](./client-logging.md)
