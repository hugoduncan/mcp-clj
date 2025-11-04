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

## Conclusion

The test failure is **NOT caused by insufficient poll timeout**. The operations are synchronous and work reliably even with 1ms timeout locally. The root cause is likely:
- Test interference (less likely given isolated transport creation)
- Resource contention on CI under load
- Background threads consuming from queues unexpectedly

Further investigation needed to identify the exact mechanism.
