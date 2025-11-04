# Test Investigation: test-server-to-client-communication Flaky Failure

## Test Details
- **File**: `components/in-memory-transport/test/mcp_clj/in_memory_transport/core_test.clj`
- **Lines**: 127-146
- **Test Name**: `test-server-to-client-communication` / "client can receive notifications from server"

## Failure Description (from CI)
The test failed intermittently on CI (PR #37, Java 25, Linux):
- Line 141: `(some? received-notification)` - received nil
- Expected: notification with method "server/notification" and params `{:data "notification data"}`
- Actual: `nil` (poll returned nothing)

## Investigation Results

### Local Reproduction Attempts
1. **Test in isolation (20 runs)**: All passed ✓
2. **Test with 1ms timeout (20 runs)**: All passed ✓
3. **Full component test suite (10 runs)**: All passed (7 tests, 42 assertions, 0 failures) ✓

### Key Findings

**Timing is NOT the issue:**
- The test uses `LinkedBlockingQueue.offer()` followed by `LinkedBlockingQueue.poll(timeout)`
- `offer()` is synchronous - the item is immediately available in the queue
- Even with 1ms timeout, the test passes reliably locally
- This eliminates poll timeout (200ms) as the root cause

**Implementation Analysis:**
```clojure
;; Test sequence:
(shared/offer-to-client! shared-transport notification)  ; Synchronous put
(shared/poll-from-server! shared-transport timeout-ms)   ; Poll from same queue
```

Both operations target `server-to-client-queue`:
- `offer-to-client!` → `.offer(queue, item)` - immediate, synchronous
- `poll-from-server!` → `.poll(queue, timeout, MILLISECONDS)` - should retrieve immediately

### Hypotheses for CI Failure

Since timing is ruled out, the failure must be due to:

1. **Test interference**: Another test running in parallel might be:
   - Sharing the same transport instance
   - Draining the queue before this test polls
   - This is unlikely given the test creates its own `shared-transport`

2. **JVM/GC pressure on CI**: Under heavy load, the JVM might:
   - Delay the offer operation's visibility
   - Experience memory visibility issues (though `LinkedBlockingQueue` is thread-safe)

3. **Race condition in test setup**: The `client/create-transport` might:
   - Start background threads that consume from queues
   - Have initialization timing issues

4. **Resource cleanup issue**: Previous test might have:
   - Left threads running that consume from queues
   - Not properly closed transport instances

### Recommendations for Next Steps

1. **Check for background threads**: Investigate `client/create-transport` to see if it starts any threads that might consume from the queue

2. **Add test isolation**: Ensure each test has completely isolated transport instances

3. **Add defensive polling**: Consider polling multiple times with short intervals rather than a single long poll

4. **Add queue state assertions**: Before polling, assert that the queue is not being consumed by anything else

5. **Run on CI with increased parallelism**: Try to reproduce the failure by stressing the CI environment

## Root Cause Identified

**The test has a race condition with the background message processor thread.**

When `client/create-transport` is called, it automatically starts `start-client-message-processor!` (client.clj:95-134), which runs in a background thread and continuously polls from the `server-to-client` queue with 100ms timeout:

```clojure
;; client.clj:106
(when-let [message (shared/poll-from-server! shared-transport 100)]
  ...)
```

The test does:
```clojure
(shared/offer-to-client! shared-transport notification)  ; Line 135
(shared/poll-from-server! shared-transport poll-timeout-ms)  ; Lines 138-140
```

**Both the background thread AND the test are polling from the same queue.**

When the notification is offered to the queue, two consumers race to poll it:
1. Background message processor thread (polling every 100ms)
2. Test's explicit poll

If the background thread wins the race, it consumes the notification and the test gets `nil` → test fails.

## Why Initial Analysis Was Wrong

The operations ARE synchronous, but that's irrelevant. The issue is **two consumers competing for the same message**, not timing of the offer/poll operations themselves.

## Proper Fix

The test should use the notification handler callback mechanism instead of manually polling:

```clojure
(let [received (atom nil)
      notification-handler (fn [msg] (reset! received msg))
      transport (client/create-transport {:shared shared-transport
                                          :notification-handler notification-handler})]
  ;; Offer notification
  (shared/offer-to-client! shared-transport notification)
  ;; Wait for handler to be called
  (Thread/sleep 200)
  (is (some? @received))
  ...)
```

This eliminates the race by having a single consumer (the background thread) with a registered callback.
