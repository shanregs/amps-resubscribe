# DESIGN.md — AMPS HA Client Reconnection Framework

This document defines the component contracts, state model, and concurrency/idempotency rules that
`ARCHITECTURE.md`, `THREADING_MODEL.md`, `CALL_FLOW.md`, and `SEQUENCE_DIAGRAM.md` build on. It is the
source of truth for *what each class is responsible for and how they agree on state* before any code is
written.

> API names referencing the AMPS Java Client (`HAClient`, `ConnectionStateListener`, `MessageStream`, …)
> reflect the general 5.x client shape. Verify exact method signatures against the AMPS Java Client
> 5.3.3.3 Javadoc during implementation — treat names here as the contract this framework needs, not a
> guarantee of the SDK's literal API.

---

## 1. Root Problem Restated

Two failures compound in the current implementation:

1. **The reader dies on disconnect** (`stream.next()` throws, the loop exits, nothing resubmits it).
2. **Reconnect is invisible to the rest of the app** — `HAClient` may reconnect at the transport level,
   but nothing re-subscribes the queue, re-creates the `MessageStream`, or restarts the reader.

The framework's job is to close that gap deterministically, using the state machine in §3 as the single
source of truth, so recovery is a sequence of state transitions rather than ad hoc exception handling.

---

## 2. Component Responsibilities

| Component | Package | Responsibility | Must NOT do |
|---|---|---|---|
| `AmpsConfiguration` | `config` | Bind `application.yml` to a list of client/queue definitions (`ClientType` → URI, queue name, retry settings). Produce Spring beans. | Touch `HAClient`, threads, or subscriptions. |
| `AmpsLifecycleManager` | `lifecycle` | Implement `SmartLifecycle`. Own the application-level start/stop sequence. Delegate everything else. | Directly manipulate `HAClient` or threads. |
| `AmpsClientManager` | `manager` | Own the `HAClient` instances: create, connect, subscribe, resubscribe, disconnect, dispose. The **only** class that touches `HAClient`. | Create threads. Read messages. Contain recovery sequencing logic. |
| `AmpsConnectionListener` | `listener` | Receive AMPS connection-state callbacks, translate them into `ConnectionState` transitions, and **orchestrate recovery** by calling `AmpsClientManager` (resubscribe) and `AmpsMessageReader` (stop/restart) in the correct order. | Own `HAClient` or reader threads directly. Do business processing. |
| `AmpsMessageReader` | `reader` | Own reader threads. Run the read loop against a `MessageStream`, hand messages to `MessageProcessor`, exit cleanly (never silently) on disconnect, expose `start(context)` / `stop(context)` that are idempotent and race-safe. | Manage `HAClient` connection state. Decide *when* to restart — that's the Listener's call. |
| `MessageProcessor` | `processor` | Business logic for a single message. Stateless with respect to connection lifecycle. | Know about `HAClient`, `MessageStream`, or threads. |
| `AmpsClientContext` | `context` | Mutable, thread-safe holder of per-client runtime state: `HAClient`, current `ConnectionState`, active `subscriptionId`, active `MessageStream`, reader running flag, recovery lock. The single source of truth every other component reads/writes through. | Contain behavior beyond guarded state transitions. |
| `ClientType`, `ConnectionState` | `model` | Enums. Pure data. | Anything stateful. |
| `RetryPolicy` | `util` | Compute backoff delays for resubscribe/reconnect retries. | Perform the retried action itself. |
| `ExecutorFactory` | `util` | Create named, single-thread daemon executors — one reader executor and one recovery executor per `ClientType` — reused across restarts. | Submit tasks or know what runs on them. |

---

## 3. Recovery State Machine

`ConnectionState` (per `AmpsClientContext`, one instance per `HAClient`):

```
CREATED → CONNECTING → CONNECTED → LOGGED_ON → SUBSCRIBED → READING
                                                                 │
                                                    disconnect event
                                                                 ▼
                                                           DISCONNECTED
                                                                 │
                                                                 ▼
                                                             WAITING
                                                                 │
                                                     AMPS begins retry
                                                                 ▼
                                                          RECONNECTING
                                                                 │
                                                    reconnect succeeds
                                                                 ▼
                                              CONNECTED → LOGGED_ON → RESUBSCRIBED → READING
```

### 3.1 Transition table

| From | To | Trigger | Side effect (who does it) |
|---|---|---|---|
| `CREATED` | `CONNECTING` | `AmpsClientManager.connect()` invoked by Lifecycle at startup | none |
| `CONNECTING` | `CONNECTED` | AMPS `Connected` callback | Listener updates context state |
| `CONNECTED` | `LOGGED_ON` | AMPS `LoggedOn` callback | Listener updates context state |
| `LOGGED_ON` | `SUBSCRIBED` | `AmpsClientManager.subscribe()` succeeds (first time only) | ClientManager stores `subscriptionId` |
| `SUBSCRIBED` | `READING` | `AmpsMessageReader.start()` succeeds | Reader marks `readerRunning = true` |
| `READING` | `DISCONNECTED` | AMPS `Disconnected` callback **or** reader catches a connection exception | Listener stops the reader (§4), sets state |
| `DISCONNECTED` | `WAITING` | Reader confirmed stopped (thread joined) | Listener |
| `WAITING` | `RECONNECTING` | AMPS begins its own reconnect attempts (transport-level, outside our control) | Listener observes callback, updates state only |
| `RECONNECTING` | `CONNECTED` → `LOGGED_ON` | AMPS reconnect + logon succeed | Listener |
| `LOGGED_ON` | `RESUBSCRIBED` | `AmpsClientManager.resubscribe()` — **idempotent**, see §5 | ClientManager |
| `RESUBSCRIBED` | `READING` | `AmpsMessageReader.start()` on a **new** `MessageStream` | Reader |

Any state may transition to a terminal `SHUTTING_DOWN` on `AmpsLifecycleManager.stop()`, which unconditionally stops the reader and disconnects the client regardless of current state.

### 3.2 Who owns state transitions

`AmpsClientContext.state` is `volatile` and every transition goes through a single guarded method,
`AmpsClientContext.transition(ConnectionState expectedFrom, ConnectionState to)`, backed by the context's
`recoveryLock`. A transition that doesn't match `expectedFrom` is a no-op (logged at DEBUG) rather than an
error — this is what makes duplicate AMPS callbacks (see §5) harmless.

---

## 4. Recovery Orchestration (the piece that was missing)

`AmpsConnectionListener` is the **only** component that sequences a recovery. It does not own resources;
it calls into `AmpsClientManager` and `AmpsMessageReader`, which do:

```
onDisconnected(clientType):
    context.transition(READING, DISCONNECTED)
    reader.stop(context)              # blocks until thread confirmed dead (see THREADING_MODEL.md)
    context.transition(DISCONNECTED, WAITING)

onReconnecting(clientType):
    context.transition(WAITING, RECONNECTING)   # informational only

onLoggedOn(clientType):
    if context.state == RECONNECTING:
        manager.resubscribe(clientType)          # idempotent
        context.transition(LOGGED_ON, RESUBSCRIBED)
        reader.start(context)                    # new MessageStream, new thread
        context.transition(RESUBSCRIBED, READING)
    else:
        # first-time logon path, handled by startup call flow, not recovery
        ...
```

**Correction from the original draft:** AMPS's own documentation prohibits submitting commands from a
`ConnectionStateListener` callback (deadlock risk — the callback runs on the same receive thread that
would deliver the command's ack). So the blocking calls above (`reader.stop()`, `manager.resubscribe()`)
do **not** run inline on the callback thread; the callback only updates state and enqueues the sequence
onto a dedicated per-client `recoveryExecutor`. See `THREADING_MODEL.md` §4 for the exact split and why a
single-thread-per-client executor still gives the same serialization guarantee (no interleaving recovery
attempts) without violating the SDK's constraint.

---

## 5. Idempotency & Duplicate-Prevention Rules

These are the concrete mechanisms behind the "no duplicate subscriptions" / "no thread leaks" success
criteria:

1. **Subscribe is called at most once per logical session.** `AmpsClientManager.subscribe()` checks
   `context.subscriptionId != null` and no-ops if already set. `resubscribe()` is the only path allowed
   to replace it, and it always unsubscribes (or lets AMPS invalidate) the old id before storing a new
   one.
2. **State transitions are compare-and-set, not blind writes.** A late/duplicate `Disconnected` callback
   firing while already `WAITING` is a no-op (§3.2), so recovery can't be triggered twice concurrently
   for the same client.
3. **Reader start/stop go through `AtomicBoolean readerRunning` in `AmpsClientContext`**, checked with
   CAS. `AmpsMessageReader.start()` refuses to submit a new task if `readerRunning` is already `true`.
   `stop()` is the only path that flips it back to `false`, and it does so only after the thread has
   actually terminated (join with timeout, not just "signal sent") — see `THREADING_MODEL.md`.
4. **Two executors per `ClientType` (reader + recovery), created once, reused forever.** `ExecutorFactory`
   hands out single-thread executors per client at startup; restarts/recoveries submit a new task to the
   same executor rather than creating a new thread pool. This is what makes "no thread leaks across 100
   reconnect cycles" achievable — the thread count is bounded by `2 × ClientType count` for the life of the
   process.
5. **`recoveryLock` (per context) serializes the disconnect→resubscribe→restart sequence** for a given
   client, so overlapping AMPS callbacks (e.g., a spurious `Disconnected` arriving mid-recovery) queue
   behind the lock instead of racing.

---

## 6. Error Handling Strategy

| Error class | Example | Handling |
|---|---|---|
| **Transport/connection** | `stream.next()` throws because the socket dropped | Caught by `AmpsMessageReader`, treated as a normal disconnect signal — loop exits cleanly, reader reports "stopped" to context, does **not** attempt to reconnect itself. |
| **Resubscribe failure** (logged on but subscribe command rejected/times out) | AMPS returns a NAK | `AmpsClientManager.resubscribe()` retries using `RetryPolicy` (exponential backoff, capped delay, unlimited attempts — see rationale below) before giving up the callback thread; failure is logged as `CLIENT_n Resubscribe Failed, retrying` and does not crash the listener. |
| **Business/processing exception** | `MessageProcessor.process()` throws | Caught inside `AmpsMessageReader`'s per-message try/catch, logged, loop continues. A processing bug must never kill the reader thread. |
| **Unexpected/fatal** | Programming error, `NullPointerException` in framework code | Logged at ERROR with full context (`ClientType`, state, subscriptionId), reader stops, state forced to `DISCONNECTED` so the normal recovery path can still pick it up on the next AMPS reconnect. Nothing should require a process restart. |

Retries are **unlimited with capped exponential backoff** (not "retry N times then give up") because this
is a long-running service expected to survive extended AMPS/network outages unattended — a give-up policy
would silently stop processing exactly one queue with no restart trigger, which is problem #3 in
`CLAUDE.md` all over again.

---

## 7. Configuration Model

```yaml
amps:
  clients:
    - type: CLIENT_1
      uri: tcp://amps-primary:9027/amps/json
      queue: orders.queue
      retry:
        initial-delay-ms: 500
        max-delay-ms: 30000
        multiplier: 2.0
    - type: CLIENT_2
      uri: tcp://amps-primary:9027/amps/json
      queue: fills.queue
      retry: { initial-delay-ms: 500, max-delay-ms: 30000, multiplier: 2.0 }
    - type: CLIENT_3
      uri: tcp://amps-primary:9027/amps/json
      queue: risk.queue
      retry: { initial-delay-ms: 500, max-delay-ms: 30000, multiplier: 2.0 }
```

`AmpsConfiguration` binds this to `List<AmpsClientDefinition>`; `AmpsClientManager.initialize()` creates
one `AmpsClientContext` per entry. Nothing downstream hardcodes "3 clients" — the count is config-driven,
`ClientType` just needs enough values to cover the configured entries.

---

## 8. Core Interfaces (pseudocode)

```java
interface AmpsClientManager {
    void initialize(List<AmpsClientDefinition> definitions);
    void connect(ClientType type);
    void subscribe(ClientType type);
    void resubscribe(ClientType type);          // idempotent, see §5
    void disconnect(ClientType type);
    AmpsClientContext contextFor(ClientType type);
    Collection<AmpsClientContext> allContexts();
}

interface AmpsMessageReader {
    void start(AmpsClientContext context);      // no-op if already running (§5)
    void stop(AmpsClientContext context);        // blocks until thread confirmed dead
    boolean isRunning(ClientType type);
}

interface MessageProcessor {
    void process(ClientType source, Message message);
}

final class AmpsClientContext {
    ClientType clientType();
    HAClient haClient();
    ConnectionState state();
    boolean transition(ConnectionState expectedFrom, ConnectionState to);
    String subscriptionId();
    void subscriptionId(String id);
    MessageStream messageStream();
    void messageStream(MessageStream stream);
    AtomicBoolean readerRunning();
    Lock recoveryLock();
}

interface RetryPolicy {
    Duration nextDelay(int attempt);
    void reset();
}
```

---

## 9. Logging Contract

Every transition in §3.1 and every action in §5 logs at INFO with a fixed prefix so log-based test
assertions (`ThreadLeakTests`, `DuplicateSubscriptionTests`) and production dashboards can grep reliably:

```
CLIENT_1 Connected
CLIENT_1 LoggedOn
CLIENT_1 Subscribed [subscriptionId=...]
CLIENT_1 Reader Started
CLIENT_1 Disconnected
CLIENT_1 Reader Stopped
CLIENT_1 Reconnecting
CLIENT_1 Resubscribed [subscriptionId=...]
CLIENT_1 Reader Restarted
```

Retries and no-ops log at DEBUG (e.g., "CLIENT_1 Disconnected callback ignored, already WAITING") to keep
INFO logs meaningful during flapping connections.
