# THREADING_MODEL.md — AMPS HA Client Reconnection Framework

Defines every thread in the system, who creates it, who is allowed to touch it, and the exact protocol
that prevents duplicate reader threads and thread leaks across reconnect cycles. This is the document
`ThreadLeakTests` and `DuplicateSubscriptionTests` (see `TEST_PLAN.md`) verify against.

---

## 1. Thread Inventory

| Thread | Created by | Count | Lifetime |
|---|---|---|---|
| Spring container / `SmartLifecycle` thread | Spring Boot | 1 | Application start → stop |
| AMPS internal transport/heartbeat threads | AMPS SDK, inside each `HAClient` | N per `HAClient` (SDK-managed) | Managed entirely by the SDK; framework never touches these directly |
| AMPS connection-callback thread | AMPS SDK (invokes `AmpsConnectionListener` callbacks) | Typically 1 per `HAClient`, SDK-managed | SDK-managed |
| Reader thread | `AmpsMessageReader`, via `ExecutorFactory` | Exactly 1 **active** per `ClientType` at any time | Executor lives for the process lifetime; the *task* running on it is resubmitted per reconnect cycle |
| Recovery thread | `AmpsConnectionListener`, via `ExecutorFactory` | Exactly 1 **executor** per `ClientType` (idle unless a recovery is in flight) | Executor lives for the process lifetime; see §4 — required so recovery work never runs on the AMPS SDK callback thread |

**Rule (from `CLAUDE.md`, restated precisely): `AmpsMessageReader` is the only class that submits work to
a reader executor. `AmpsClientManager` is the only class that calls any `HAClient` method. No other class
creates a `Thread`, `ExecutorService`, or `HAClient` directly. `AmpsConnectionListener` submits work only
to its per-client `recoveryExecutor`, never runs recovery logic inline on the callback thread (§4).**

---

## 2. Executor Lifecycle (the leak-prevention mechanism)

`ExecutorFactory.create(ClientType type, role)` is called **twice per client** — once for `"reader"`, once
for `"recovery"` — during `AmpsLifecycleManager.start()`, producing single-thread executors with named
daemon `ThreadFactory`s (`amps-reader-CLIENT_1`, `amps-recovery-CLIENT_1`, etc.). Both executors are stored
on `AmpsClientContext` and reused for the entire process lifetime — neither is recreated on reconnect.

```
Startup:
    executor = ExecutorFactory.create(CLIENT_1)     # created once
    context.executor = executor

Each reconnect cycle:
    reader.stop(context)      # cancels/awaits the running task, executor itself is untouched
    reader.start(context)     # submits a NEW task to the SAME executor

Shutdown:
    executor.shutdown()       # only place the executor itself is ever terminated
```

This is why 100 reconnect cycles cannot leak threads: the thread count is bounded by
`ExecutorFactory.create()` call count, which equals the number of configured `ClientType`s, fixed at
startup — not by the number of reconnects.

---

## 3. Reader Start/Stop Protocol (duplicate-thread prevention)

`AmpsClientContext` carries `AtomicBoolean readerRunning` and a `volatile Future<?> readerTask`.

### `AmpsMessageReader.start(context)`

```
if !readerRunning.compareAndSet(false, true):
    log.debug("CLIENT_n Reader Start ignored, already running")
    return

context.readerTask = context.executor.submit(() -> readLoop(context))
log.info("CLIENT_n Reader Started")
```

The `compareAndSet` is what makes `start()` safe to call from a single-threaded recovery sequence without
a separate "is it already running" check-then-act race — only one caller can win the CAS.

### `AmpsMessageReader.stop(context)`

```
if !readerRunning.get():
    return          # already stopped, no-op

context.readLoopShouldStop = true     # cooperative flag, checked between reads
context.messageStream.close()          # unblocks a thread parked in stream.next()

try:
    context.readerTask.get(STOP_TIMEOUT, MILLISECONDS)   # BLOCK until thread actually exits
catch TimeoutException:
    log.warn("CLIENT_n Reader did not stop within {}ms, forcing", STOP_TIMEOUT)
    context.readerTask.cancel(true)

readerRunning.set(false)
log.info("CLIENT_n Reader Stopped")
```

**`stop()` does not return until the previous reader thread has actually terminated** (join semantics via
`Future.get()`, not "signal sent and hope"). This is the mechanism that prevents two reader threads racing
against the same queue after a fast reconnect: `AmpsConnectionListener` always calls `stop()` and waits for
it to complete *before* `AmpsClientManager.resubscribe()` and `reader.start()` run (see `DESIGN.md` §4 and
`SEQUENCE_DIAGRAM.md`). There is never a window where the old and new reader threads are both alive.

### Read loop shape

```
readLoop(context):
    log.info("CLIENT_n Reader Started")
    while !context.readLoopShouldStop:
        try:
            message = context.messageStream.next()     # blocks
            if message == null: continue
            try:
                processor.process(context.clientType, message)
            catch Exception e:
                log.error("CLIENT_n Message processing failed", e)   # business error: loop continues
        catch ConnectionException | StreamClosedException e:
            log.info("CLIENT_n Reader Stopping (connection lost)")
            break                                        # transport error: loop exits cleanly
    # no self-restart here — restart is the Listener's decision, not the Reader's
```

The loop **never** calls anything on `AmpsClientManager` or `AmpsConnectionListener`. On exit — whether
via cooperative flag or caught connection exception — it just ends. `DESIGN.md` §5 rule 3 depends on this:
the reader is purely reactive, so `readerRunning` accurately reflects "is a thread actually reading"
rather than "did someone intend for a thread to be reading."

---

## 4. Callback Thread Discipline

**Confirmed against 60East's own AMPS Java Client documentation** (Monitoring Connection State /
error-handling guide): `ConnectionStateListener` callbacks are invoked on the client's receive thread, and
the documentation explicitly warns that an application **must not submit commands to AMPS from a
connection state listener** — doing so risks deadlocking commands that wait for a server acknowledgement,
because the same receive thread is what would deliver that acknowledgement.

`resubscribe()` submits a command (`Client.execute`/`executeAsync`) and blocks (directly or via retry) for
its ack. Calling it synchronously from `AmpsConnectionListener`'s callback is therefore unsafe — this
revises the earlier draft of this section, which treated synchronous execution as an acceptable trade-off.

**Revised design: every `AmpsClientContext` owns a second, dedicated single-thread executor —
`recoveryExecutor` — created by `ExecutorFactory` alongside the reader executor (§2), for the same
reasons: created once at startup, reused for the process lifetime, never recreated per cycle.**
`AmpsConnectionListener` does only cheap, non-blocking work on the AMPS callback thread — updating
`context.state` and enqueuing a recovery task — then returns immediately:

```
onDisconnected(type):
    context.transition(READING, DISCONNECTED)
    context.recoveryExecutor.submit(() -> runDisconnectRecovery(context))
    return                                            # callback thread freed immediately

onLoggedOn(type):
    context.transition(CONNECTED, LOGGED_ON)
    if context.state reached via RECONNECTING:
        context.recoveryExecutor.submit(() -> runReconnectRecovery(context))
    return

runDisconnectRecovery(context):                       # runs on recoveryExecutor, NOT the AMPS thread
    lock(context.recoveryLock):
        reader.stop(context)                           # blocking, safe here — off the SDK thread
        context.transition(DISCONNECTED, WAITING)

runReconnectRecovery(context):                         # runs on recoveryExecutor
    lock(context.recoveryLock):
        manager.resubscribe(type)                      # submits a command + blocks for ack — safe here
        context.transition(LOGGED_ON, RESUBSCRIBED)
        reader.start(context)
        context.transition(RESUBSCRIBED, READING)
```

Properties this preserves:

1. **No command is ever submitted from the AMPS callback thread** — `reader.stop()`'s `MessageStream.close()`
   and `manager.resubscribe()`'s command execution both happen on `recoveryExecutor`, satisfying the SDK's
   documented constraint.
2. **Ordering is still guaranteed** — `recoveryExecutor` is single-threaded per client, so
   `runDisconnectRecovery` and `runReconnectRecovery` for the same client can never interleave, and
   `context.recoveryLock` remains as a second guard against a stray concurrent call from elsewhere (e.g., a
   test, or a future manual "force resubscribe" admin action).
3. **No duplicate recovery executors** — created once per client in `ExecutorFactory` at startup (§2),
   shut down once at process shutdown (§6), same lifecycle as the reader executor. Two executors per
   client (reader + recovery), not two per reconnect cycle.
4. **The callback thread is never blocked**, so a slow resubscribe on Client 1 cannot delay AMPS's own
   delivery of Client 2's or Client 3's connection-state callbacks even if the SDK happens to share a
   callback-dispatch thread across `HAClient` instances.

---

## 5. Thread-Safety of `AmpsClientContext`

| Field | Type | Why |
|---|---|---|
| `state` | `volatile ConnectionState` | Read by any thread (metrics, tests), written only through `transition()` under `recoveryLock`. |
| `subscriptionId` | `volatile String` | Written by `manager` under `recoveryLock`, read by `listener`/tests. |
| `messageStream` | `volatile MessageStream` | Written by `manager`/`reader` under lock; read by the reader thread each loop iteration is a local capture at loop start, not a re-read per message. |
| `readerRunning` | `AtomicBoolean` | CAS-guarded start/stop, no external lock needed for this field specifically. |
| `readerTask` | `volatile Future<?>` | Set by `start()`, read by `stop()`. |
| `recoveryLock` | `ReentrantLock` | Serializes the entire disconnect→resubscribe→restart sequence per client (§4). |

No field is ever mutated without going through `AmpsClientContext`'s own methods — no consumer package
reaches in and sets a field directly (enforced by keeping fields private with guarded accessors, not by
convention alone).

---

## 6. Shutdown Protocol

`AmpsLifecycleManager.stop()` (Spring `SmartLifecycle`) runs, for each context, regardless of current
`ConnectionState`:

```
for context in manager.allContexts():
    reader.stop(context)                       # bounded wait, same as §3
    manager.disconnect(context.type)            # HAClient.close()/disconnect()
    shutdownExecutor(context.readerExecutor)
    shutdownExecutor(context.recoveryExecutor)  # in-flight recovery (if any) allowed to finish, then closed

shutdownExecutor(executor):
    executor.shutdown()
    if !executor.awaitTermination(SHUTDOWN_TIMEOUT, MILLISECONDS):
        executor.shutdownNow()
```

This is the **only** place either executor's `shutdown()` is called — confirming, together with §2, that
both executors per client are created exactly once at startup and destroyed exactly once at shutdown, with
reconnect cycles in between only ever resubmitting tasks.
