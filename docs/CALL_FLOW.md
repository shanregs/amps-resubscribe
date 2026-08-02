# CALL_FLOW.md — AMPS HA Client Reconnection Framework

Numbered call flows mapping directly onto the interfaces in `DESIGN.md` §8 and the thread/lock rules in
`THREADING_MODEL.md`. Each step names the method actually invoked so this document can be checked
line-by-line against the implementation. See `SEQUENCE_DIAGRAM.md` for the same flows as Mermaid diagrams.

> **Threading note:** steps below describe the *logical* sequence. Per `THREADING_MODEL.md` §4, the
> `reader.stop()` / `manager.resubscribe()` / `reader.start()` portion of each recovery step actually runs
> on a per-client `recoveryExecutor`, not inline on the AMPS callback thread that raises the triggering
> event — AMPS forbids submitting commands from its connection-state callback. The Listener's callback
> method itself only updates state and enqueues the sequence.

---

## 1. Startup Call Flow

Triggered once, by Spring container start.

1. `Application` boots → Spring creates `AmpsConfiguration` beans from YAML (`DESIGN.md` §7).
2. Spring calls `AmpsLifecycleManager.start()` (implements `SmartLifecycle`).
3. `AmpsLifecycleManager` calls `AmpsClientManager.initialize(definitions)`.
   - For each `AmpsClientDefinition`: create `HAClient`, create `AmpsClientContext(type, haClient)`,
     state = `CREATED`.
   - Also: `ExecutorFactory.create(type)` → stored on the context (`THREADING_MODEL.md` §2). Created
     exactly once here, never again for this process.
4. `AmpsLifecycleManager` registers `AmpsConnectionListener` on each `HAClient`
   (`haClient.addConnectionStateListener(listener)`), passing the shared `AmpsClientContext`.
5. For each context, `AmpsLifecycleManager` calls `AmpsClientManager.connect(type)`.
   - `context.transition(CREATED, CONNECTING)`.
   - `haClient.connect()` — AMPS begins its own connect sequence.
6. AMPS invokes `AmpsConnectionListener` callbacks asynchronously as the connection progresses:
   - `Connected` → `context.transition(CONNECTING, CONNECTED)`, log `CLIENT_n Connected`.
   - `LoggedOn` → `context.transition(CONNECTED, LOGGED_ON)`, log `CLIENT_n LoggedOn`.
7. On `LoggedOn` (first-time path, `context.subscriptionId == null`), the Listener calls
   `AmpsClientManager.subscribe(type)`.
   - `haClient.executeAsync(subscribeCommand)` against the configured queue.
   - On ack: `context.subscriptionId(id)`, `context.transition(LOGGED_ON, SUBSCRIBED)`, log
     `CLIENT_n Subscribed [subscriptionId=...]`.
8. Listener calls `AmpsMessageReader.start(context)`.
   - CAS `readerRunning: false → true`, submit `readLoop(context)` to `context.executor`.
   - `context.transition(SUBSCRIBED, READING)`, log `CLIENT_n Reader Started`.
9. Reader thread loops: `context.messageStream.next()` → `MessageProcessor.process(type, message)`.
10. Steps 3–9 repeat independently for every configured `ClientType` — there is no cross-client
    ordering dependency; `AmpsLifecycleManager` starts all contexts and each proceeds on its own AMPS
    callback timeline.

---

## 2. Disconnect / Recovery Call Flow

Triggered per-client, any time after step 9 above, by either an AMPS `Disconnected` callback or the
reader thread observing a connection exception mid-read.

1. Trigger: AMPS invokes `AmpsConnectionListener.onDisconnected(type)` **or** `readLoop` catches a
   `ConnectionException` and the loop exits on its own (`THREADING_MODEL.md` §3) — either way, the
   Listener's `onDisconnected` handler is the entry point (the reader-initiated exit also surfaces here
   because the reader's exit is what the next AMPS callback reacts to; the Listener does not poll).
2. `AmpsConnectionListener.onDisconnected(type)` acquires `context.recoveryLock`.
3. `context.transition(READING, DISCONNECTED)`, log `CLIENT_n Disconnected`.
4. Listener calls `AmpsMessageReader.stop(context)` — **blocks** until the reader thread has actually
   terminated (`THREADING_MODEL.md` §3): closes `context.messageStream`, sets the cooperative stop flag,
   awaits `readerTask.get(STOP_TIMEOUT)`.
   - `readerRunning.set(false)`, log `CLIENT_n Reader Stopped`.
5. `context.transition(DISCONNECTED, WAITING)`.
6. Listener releases `recoveryLock` and returns — AMPS is now responsible for the reconnect attempt loop
   (transport-level retry, outside this framework's control; configured on `HAClient`/`ServerChooser` in
   `AmpsClientManager`).
7. AMPS invokes `AmpsConnectionListener.onReconnecting(type)` (or equivalent) zero or more times while
   retrying. Listener updates `context.transition(WAITING, RECONNECTING)`, log `CLIENT_n Reconnecting`
   (subsequent duplicate callbacks are no-ops per `DESIGN.md` §3.2).
8. AMPS succeeds: `Connected` → `LoggedOn` callbacks fire again.
   - `Connected`: `context.transition(RECONNECTING, CONNECTED)`.
   - `LoggedOn`: `context.transition(CONNECTED, LOGGED_ON)`.
9. `AmpsConnectionListener.onLoggedOn(type)` acquires `context.recoveryLock`, inspects
   `context.state == LOGGED_ON` reached **via** `RECONNECTING` (not the first-time startup path) and
   runs the recovery branch:
   a. `AmpsClientManager.resubscribe(type)` — idempotent (`DESIGN.md` §5 rule 1): issues a fresh
      subscribe command, replaces `context.subscriptionId`, retries via `RetryPolicy` on transient NAK
      (`DESIGN.md` §6).
   b. `context.transition(LOGGED_ON, RESUBSCRIBED)`, log `CLIENT_n Resubscribed [subscriptionId=...]`.
   c. `AmpsMessageReader.start(context)` — submits a **new** task to the **same** executor
      (`THREADING_MODEL.md` §2), against the new `MessageStream` returned by `resubscribe()`.
   d. `context.transition(RESUBSCRIBED, READING)`, log `CLIENT_n Reader Restarted`.
10. Listener releases `recoveryLock`. Message processing resumes with no application restart —
    fulfilling the success criteria in `CLAUDE.md`.

---

## 3. Failover Call Flow

Primary AMPS instance goes down; `HAClient`'s configured `ServerChooser`/failover URI list fails over to
secondary. From this framework's perspective this is **not a distinct code path** — it is the disconnect/
recovery flow in §2, where step 6–8 happen to land on a different physical AMPS server:

1. Steps 1–6 from §2 execute identically (disconnect detected, reader stopped, state → `WAITING`).
2. AMPS's internal failover logic (configured once, in `AmpsClientManager.connect()`'s `HAClient` setup)
   selects the secondary server and reconnects to it — this is SDK behavior, not framework logic.
3. Steps 7–10 from §2 execute identically: `Reconnecting` → `Connected` → `LoggedOn` →
   `AmpsConnectionListener` runs the same resubscribe + reader-restart sequence, unaware and uncaring that
   the underlying server changed.

This confirms the architecture claim in `ARCHITECTURE.md` §5: failover requires zero conditional logic in
`listener` or `reader`, because `AmpsClientManager` is the only layer that knows about server topology, and
even it only knows about it as `HAClient` configuration, not runtime branching.

---

## 4. Shutdown Call Flow

Triggered once, by Spring container stop (`AmpsLifecycleManager.stop()`).

1. Spring calls `AmpsLifecycleManager.stop()`.
2. For each `AmpsClientContext` in `AmpsClientManager.allContexts()` (parallel across clients is
   acceptable here — there's no cross-client ordering requirement):
   a. `AmpsMessageReader.stop(context)` — same bounded-wait semantics as §2 step 4, regardless of
      current `ConnectionState`.
   b. `AmpsClientManager.disconnect(type)` — `haClient.close()`/`disconnect()`.
   c. `context.executor.shutdown()`; if not terminated within `SHUTDOWN_TIMEOUT`,
      `context.executor.shutdownNow()` (`THREADING_MODEL.md` §6). This is the only call site for
      executor shutdown in the entire system.
3. `AmpsLifecycleManager` reports `isRunning() == false` back to Spring once every context has completed
   step 2.
