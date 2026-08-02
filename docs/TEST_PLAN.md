# TEST_PLAN.md — AMPS HA Client Reconnection Framework

Expands `CLAUDE.md`'s Test Strategy into concrete test cases per class in the planned `src/test/java`
layout. Each test case names the exact assertion it makes against the contracts in `DESIGN.md` and
`THREADING_MODEL.md`, so "done" is unambiguous.

This plan covers `amps-reconnect-framework`'s consumer-side test suite. `amps-publisher-simple`'s own
tests (`RateLimiterTest`, `AmpsPublishConfigTest`, `OrderPayloadGeneratorTest`, `AmpsPublisherTest`) are
documented in `PUBLISHER.md` rather than expanded here, since that module has no recovery state machine
to test against — its correctness bar is "publishes at the configured rate/burst and pauses cleanly
while disconnected," not the resubscribe/reader-restart guarantees this document is about. For manual
end-to-end runs that need real queue traffic (`FailoverTests`, `IntegrationTests` below), run
`amps-publisher-simple` against the same lab queues per `HA_AMPS_LAB_SETUP_GUIDE.md`.

---

## 0. Test Infrastructure & Mocking Approach

- **`HAClient` is mocked (Mockito), not real, for all unit and recovery tests.** `AmpsClientManager`,
  `AmpsMessageReader`, and `AmpsConnectionListener` are all testable against a mocked `HAClient` +
  mocked `MessageStream` because none of them depend on AMPS wire behavior directly — they depend on the
  callback/interface contracts defined in `DESIGN.md` §8.
- **AMPS connection-state callbacks are simulated by invoking `AmpsConnectionListener` methods directly**
  (`listener.onDisconnected(type)`, `listener.onLoggedOn(type)`, etc.) rather than trying to trigger them
  through a mocked SDK — this is what makes `DisconnectRecoveryTests` and `MultiReconnectTests`
  deterministic and fast (no sleeps, no real network).
- **`MessageStream.next()` is driven by a test double** that can be scripted to: return N messages, then
  throw a `ConnectionException` (simulating a drop), then block (simulating a healthy idle stream) — used
  to exercise the reader loop's exit paths from `THREADING_MODEL.md` §3.
- **A fake/in-memory `AmpsClientManager`** (or a Mockito mock with `verify()`/`times()` assertions) is used
  by `DuplicateSubscriptionTests` to count `subscribe()`/`resubscribe()` invocations precisely.
- **`IntegrationTests` and `FailoverTests`** are the only classes expected to run against a real or
  embedded AMPS instance (e.g., a local `ampServer` test fixture or Testcontainers image, if available in
  the environment) — everything else is a pure unit/component test with no network dependency, so the
  suite stays fast and CI-friendly.
- **Thread assertions** (`ThreadLeakTests`) use `Thread.getAllStackTraces().keySet()` filtered by the
  `amps-reader-*` naming convention from `ExecutorFactory` (`THREADING_MODEL.md` §2), snapshotting the
  count before and after N reconnect cycles.

---

## 1. `StartupTests` (unit)

| Test | Assertion |
|---|---|
| `createsOneContextPerConfiguredClient` | `AmpsClientManager.initialize()` with N `AmpsClientDefinition`s produces exactly N `AmpsClientContext`s, one per `ClientType`. |
| `createsExecutorExactlyOncePerClient` | `ExecutorFactory.create()` is invoked exactly once per `ClientType` during startup (Mockito `verify(times(1))`), matching `THREADING_MODEL.md` §2. |
| `connectTransitionsCreatedToConnecting` | `AmpsClientManager.connect(type)` moves context state `CREATED → CONNECTING` and calls `haClient.connect()` exactly once. |
| `firstLoggedOnTriggersSubscribeNotResubscribe` | Simulated `Connected` + `LoggedOn` callbacks on a fresh (`subscriptionId == null`) context result in `AmpsClientManager.subscribe()` being called, not `resubscribe()`. |
| `subscribeSuccessStartsReader` | After a successful subscribe ack, `AmpsMessageReader.start()` is invoked exactly once and context reaches `READING`. |
| `startupIsIndependentPerClient` | Client A stuck in `CONNECTING` (no callback fired) does not block Client B from reaching `READING` — verifies no cross-client ordering dependency (`CALL_FLOW.md` §1 step 10). |

---

## 2. `DisconnectRecoveryTests` (unit/component)

| Test | Assertion |
|---|---|
| `disconnectStopsReaderBeforeStateReachesWaiting` | Simulating `onDisconnected()` results in `reader.stop()` being called, and `context.state == WAITING` is only observed **after** `reader.stop()` returns (ordering assertion via a spy/latch on the reader stub). |
| `disconnectDuringActiveReadUnblocksReader` | With a `MessageStream` test double parked in a blocking `next()`, `onDisconnected()` → `stream.close()` is called, and the reader task completes (no deadlock, bounded by test timeout well under `STOP_TIMEOUT`). |
| `reconnectTriggersResubscribeNotSubscribe` | After a full disconnect→`WAITING` cycle, simulated `LoggedOn` calls `AmpsClientManager.resubscribe()`, never `subscribe()` (distinguishing the recovery path from the first-time path in `StartupTests`). |
| `recoverySequenceOrder` | Verifies the exact call order from `CALL_FLOW.md` §2 via `InOrder` (Mockito): `stop()` → `resubscribe()` → `start()` — resubscribe must never happen before the old reader is confirmed stopped, and the new reader must never start before resubscribe returns. |
| `recoveryReachesReadingState` | End of the simulated sequence leaves `context.state == READING` with a non-null, updated `subscriptionId`. |
| `duplicateDisconnectCallbackIsNoOp` | Firing `onDisconnected()` twice in a row (simulating a spurious duplicate AMPS callback) results in `reader.stop()` being called only once — second call is a no-op per `DESIGN.md` §3.2. |

---

## 3. `ResubscribeTests` (unit)

| Test | Assertion |
|---|---|
| `resubscribeReplacesOldSubscriptionId` | `subscriptionId` after `resubscribe()` differs from the pre-recovery value and is non-null. |
| `resubscribeIsIdempotentUnderConcurrentCalls` | Two threads calling `AmpsClientManager.resubscribe(type)` concurrently (simulating a racing duplicate `LoggedOn` callback) result in exactly one subscribe command sent to `HAClient` — guarded by `context.recoveryLock` (`DESIGN.md` §5 rule 5). |
| `resubscribeRetriesOnTransientNak` | Mocked `HAClient` NAKs the first subscribe attempt and acks the second; `resubscribe()` succeeds, and `RetryPolicy.nextDelay()` was consulted exactly once. |
| `resubscribeRetryUsesBackoff` | Repeated NAKs produce increasing delays matching `RetryPolicy`'s configured multiplier, capped at `max-delay-ms` (`DESIGN.md` §7 config). |
| `resubscribeNeverCalledBeforeLoggedOn` | Attempting to call `resubscribe()` while context is still `WAITING`/`RECONNECTING` throws/rejects or is rejected by a precondition check — resubscribe is only valid from `LOGGED_ON`. |

---

## 4. `ReaderRestartTests` (unit)

| Test | Assertion |
|---|---|
| `startIsNoOpIfAlreadyRunning` | Calling `AmpsMessageReader.start(context)` twice without an intervening `stop()` results in exactly one task submitted to the executor (CAS guard, `THREADING_MODEL.md` §3). |
| `stopBlocksUntilThreadTerminated` | `stop()` does not return until the reader task's `Future` is done — assert via a test double that sleeps briefly in its loop before exiting; `stop()` must not return before that sleep completes. |
| `stopTimeoutForcesCancel` | If the reader task ignores the cooperative stop flag past `STOP_TIMEOUT`, `stop()` calls `Future.cancel(true)` and still returns (does not hang the test/caller indefinitely). |
| `restartUsesSameExecutorInstance` | Across a `start()` → `stop()` → `start()` cycle, `context.executor` is the identical object reference each time — no new executor created (`THREADING_MODEL.md` §2). |
| `restartAfterStopSubmitsNewTask` | After `stop()` completes, a subsequent `start()` succeeds (CAS resets to allow it) and a new task is submitted. |
| `businessExceptionDoesNotStopReader` | `MessageProcessor.process()` throwing on one message does not end the read loop — subsequent messages in the scripted stream are still processed (`THREADING_MODEL.md` §3 "read loop shape"). |
| `connectionExceptionEndsLoopWithoutSelfRestart` | When the scripted `MessageStream` throws `ConnectionException`, the loop exits and `readerRunning` becomes `false`, but no call is made to `AmpsClientManager` or `AmpsConnectionListener` from within the reader (verifies the reader never restarts itself — that's the Listener's job per `ARCHITECTURE.md` §4). |

---

## 5. `MultiReconnectTests` (component)

| Test | Assertion |
|---|---|
| `tenSequentialReconnectCyclesReachReadingEachTime` | Drive the disconnect→recover sequence 10 times in a row for one client; assert `context.state == READING` after every cycle. |
| `subscriptionIdChangesEveryCycle` | Each of the 10 cycles produces a distinct `subscriptionId` (mocked `HAClient` returns a unique id per subscribe call), confirming resubscribe genuinely re-executes rather than reusing stale state. |
| `readerThreadCountStaysAtOneAfterEachCycle` | After each cycle, exactly one live thread matches the `amps-reader-CLIENT_n` naming pattern for that client (feeds into `ThreadLeakTests` at higher volume). |
| `multipleClientsRecoverIndependently` | Disconnecting Client 1 does not affect Client 2/3's `ConnectionState` or reader status — each `AmpsClientContext`/`recoveryLock` is independent. |

---

## 6. `FailoverTests` (integration)

Requires a real or embedded AMPS setup with a primary + secondary instance (or a test double that models
`ServerChooser` failover behavior if a live two-instance AMPS environment isn't available).

| Test | Assertion |
|---|---|
| `primaryShutdownTriggersFailoverToSecondary` | Stopping the primary AMPS process results in `HAClient` connecting to the secondary within a bounded wait, and `AmpsConnectionListener` runs the identical recovery sequence as `DisconnectRecoveryTests` (no failover-specific branch is exercised — confirms `ARCHITECTURE.md` §5's "zero conditional logic" claim). |
| `resubscribeSucceedsAgainstSecondary` | Post-failover, `subscriptionId` is valid against the secondary and messages published to the secondary's queue are received by the running reader. |
| `failoverPreservesMessageProcessingWithoutRestart` | The application process is never restarted across the failover; `MessageProcessor.process()` continues to be invoked for messages published after failover completes. |
| `failbackToPrimaryAlsoRecovers` | If the primary comes back and `HAClient` fails back (per its configured `ServerChooser` policy), the same recovery sequence runs again cleanly — this is really a second `MultiReconnectTests`-style cycle, confirming failover and normal reconnect are the same code path end-to-end. |

---

## 7. `ThreadLeakTests` (stress)

| Test | Assertion |
|---|---|
| `hundredReconnectCyclesNoThreadGrowth` | Snapshot thread count (filtered to `amps-reader-*`) before 100 simulated disconnect/recover cycles and after; counts must be equal, and must equal the configured `ClientType` count throughout (assert at intervals, not just start/end, to catch transient double-threading). |
| `hundredReconnectCyclesNoExecutorGrowth` | `ExecutorFactory.create()` call count remains exactly N (configured client count) across the whole stress run — never re-invoked mid-run. |
| `stopAlwaysJoinsBeforeReturning` | Instrument the reader task to record its own thread id on start; after each `stop()`, assert the previously-recorded thread id is no longer alive before the corresponding `start()` records a new one — directly verifies the no-overlap guarantee in `THREADING_MODEL.md` §3. |
| `shutdownTerminatesAllExecutors` | After `AmpsLifecycleManager.stop()`, every context's executor reports `isTerminated() == true` within `SHUTDOWN_TIMEOUT`, and zero `amps-reader-*` threads remain alive. |

---

## 8. `DuplicateSubscriptionTests` (unit/component)

| Test | Assertion |
|---|---|
| `subscribeCalledExactlyOnceAtStartup` | Across a full simulated startup, `haClient.executeAsync(subscribeCommand)` is invoked exactly once per client (Mockito `verify(times(1))`). |
| `resubscribeReplacesRatherThanAdds` | After recovery, exactly one *active* subscription is tracked in `context` (single `subscriptionId` field, not a growing collection) — confirms the old subscription reference is discarded, not accumulated. |
| `concurrentLoggedOnCallbacksProduceOneResubscribe` | Two near-simultaneous `LoggedOn` callbacks (simulating flaky duplicate SDK callbacks) result in exactly one `resubscribe()` execution, serialized by `recoveryLock` — the second caller observes the already-updated state and no-ops (`DESIGN.md` §5 rules 2 & 5). |
| `hundredCyclesProduceHundredDistinctSubscriptionsNoOverlap` | Over a `MultiReconnectTests`-style 100-cycle run, at any point in time exactly one subscription is active per client — no cycle leaves two concurrently-valid subscriptions on the same queue. |

---

## 9. `IntegrationTests` (end-to-end)

Requires a real or embedded single-instance AMPS server.

| Test | Assertion |
|---|---|
| `fullStartupPublishConsume` | Start the framework against a real AMPS instance, publish a message to the configured queue externally, assert `MessageProcessor.process()` receives it. |
| `killAndRestoreAmpsProcessRecoversWithoutAppRestart` | Kill the AMPS server process (or block its port), wait for `Disconnected`, restore it, assert the app resumes consuming previously-queued and newly-published messages without any restart of the Spring application — this is the direct integration-level proof of `CLAUDE.md`'s primary success criterion. |
| `messageContinuityAcrossReconnect` | Publish messages before, during (queued server-side), and after a disconnect; assert none are lost and none are duplicated at the processor. |
| `gracefulShutdownDrainsCleanly` | `AmpsLifecycleManager.stop()` during active message flow results in a clean stop (per `CALL_FLOW.md` §4) with no exceptions logged and no orphaned AMPS-side subscription left active after disconnect. |

---

## 10. Coverage Mapping Back to `CLAUDE.md` Success Criteria

| `CLAUDE.md` success criterion | Covered by |
|---|---|
| Application starts successfully | `StartupTests` |
| Three HAClients connect successfully | `StartupTests.startupIsIndependentPerClient`, `IntegrationTests.fullStartupPublishConsume` |
| All queues subscribe successfully | `StartupTests.subscribeSuccessStartsReader`, `DuplicateSubscriptionTests.subscribeCalledExactlyOnceAtStartup` |
| Reader threads process messages | `ReaderRestartTests`, `IntegrationTests.fullStartupPublishConsume` |
| Disconnects are detected | `DisconnectRecoveryTests` |
| HAClients reconnect automatically | `DisconnectRecoveryTests`, `FailoverTests` |
| Queue subscriptions are restored | `ResubscribeTests`, `DisconnectRecoveryTests.reconnectTriggersResubscribeNotSubscribe` |
| New MessageStreams are created | `ReaderRestartTests.restartAfterStopSubmitsNewTask` |
| Reader threads restart automatically | `ReaderRestartTests`, `MultiReconnectTests` |
| Messages continue processing without app restart | `IntegrationTests.killAndRestoreAmpsProcessRecoversWithoutAppRestart` |
| Multiple reconnect cycles, no leaks/duplicates | `MultiReconnectTests`, `ThreadLeakTests`, `DuplicateSubscriptionTests` |
