# ARCHITECTURE.md — AMPS HA Client Reconnection Framework

Companion to `DESIGN.md` (component contracts, state machine) and `THREADING_MODEL.md` (thread
ownership). This document fixes the package layout and the dependency-direction rules that keep the
system's single-responsibility boundaries from eroding as it grows.

Scope note: this document (like `DESIGN.md`, `CALL_FLOW.md`, `SEQUENCE_DIAGRAM.md`, `TEST_PLAN.md`,
`THREADING_MODEL.md`) describes the `amps-reconnect-framework` module specifically — the consumer side
of the system. For the producer that feeds it live queue traffic during HA testing, see `PUBLISHER.md`
(`amps-publisher-simple`) and `HA_AMPS_LAB_SETUP_GUIDE.md` for the lab environment both run against.

---

## 1. Layered View

```
                         Spring Boot
                              │
                              ▼
                  AmpsLifecycleManager   (SmartLifecycle: start/stop orchestration)
                              │
                              ▼
                   AmpsClientManager     (owns HAClient instances)
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
    HAClient1             HAClient2             HAClient3
        │                     │                     │
        └─────────────────────┬─────────────────────┘
                              ▼
                AmpsConnectionListener   (recovery orchestration)
                              │
                              ▼
                  AmpsMessageReader      (owns reader threads)
                              │
                              ▼
                   MessageProcessor      (business logic)
```

Each layer is a package (§2). A layer only calls the layer directly below it, plus shared `model` /
`context` / `util` types (§3). There is no layer that reaches two levels down or calls back up.

---

## 2. Package Structure & Responsibility

```
src/main/java/
  config/       AmpsConfiguration.java        — YAML → AmpsClientDefinition beans
  lifecycle/    AmpsLifecycleManager.java      — SmartLifecycle start()/stop()
  manager/      AmpsClientManager.java         — owns HAClient: connect/subscribe/resubscribe/disconnect
  listener/     AmpsConnectionListener.java    — AMPS callbacks → ConnectionState → recovery sequencing
  reader/       AmpsMessageReader.java         — owns reader threads, read loop, dispatch to processor
  processor/    MessageProcessor.java          — business logic, one message at a time
  context/      AmpsClientContext.java         — per-client mutable state, the shared source of truth
  model/        ClientType.java, ConnectionState.java   — pure enums
  util/         RetryPolicy.java, ExecutorFactory.java  — stateless/reusable helpers
  Application.java                             — Spring Boot entry point
```

Each package maps 1:1 to a row in `DESIGN.md` §2's responsibility table. If a change doesn't fit
cleanly into exactly one package's stated responsibility, that's a signal the change is being placed in
the wrong layer, not that the table needs stretching.

---

## 3. Dependency Direction

```
config ──► (nothing)
model ──► (nothing)
context ──► model
util ──► model
manager ──► context, model, util
processor ──► model
reader ──► context, processor, model, util
listener ──► manager, reader, context, model
lifecycle ──► manager, listener, reader, config
Application ──► lifecycle, config
```

### 3.1 Forbidden dependencies (explicitly, so reviews can check for them)

| Forbidden | Why |
|---|---|
| `reader` → `listener` | Reader must not know recovery is happening; it only starts/stops when told. Keeps thread ownership (§ Threading Model) from splitting. |
| `reader` → `manager` | Reader never touches `HAClient` directly — only through the `MessageStream` handed to it via `AmpsClientContext`. |
| `processor` → anything but `model` | Business logic must be testable with a plain `Message` in, no AMPS/thread/connection concepts in scope. |
| `manager` → `listener` or `reader` | Would invert the dependency direction — `AmpsClientManager` is a lower layer and must stay ignorant of recovery sequencing and threading. |
| anything → `lifecycle` | `AmpsLifecycleManager` is the top of the stack; nothing below it may call back up into it. |
| `context` → anything except `model` | Context is pure state; giving it dependencies on `manager`/`reader` would turn it into a god object instead of a shared data holder. |

`AmpsClientContext` is the one type that legitimately crosses every layer's boundary — that's
intentional (it's the shared source of truth in `DESIGN.md` §3.2), not a violation, because it lives in
its own package with no dependency back into any consumer.

---

## 4. Why the Listener sits between Manager and Reader

The original architecture diagram in `CLAUDE.md` draws `AmpsConnectionListener` between the
`HAClient`s and `AmpsMessageReader`. That placement is load-bearing: the Listener is the only component
allowed to call *both* `AmpsClientManager.resubscribe()` and `AmpsMessageReader.start()/stop()`, because
recovery is inherently a cross-cutting sequence (`DESIGN.md` §4) — it is not natural to either the
Manager (which shouldn't know about threads) or the Reader (which shouldn't know about subscriptions).
Making the Listener own *sequencing only*, with zero owned resources of its own, is what keeps SRP intact
here instead of collapsing Manager+Reader+recovery into one class.

---

## 5. Extension Points

- **More clients/queues**: add entries to `AmpsConfiguration` YAML and a corresponding `ClientType`
  value. No code above `manager` needs to change — `AmpsLifecycleManager` iterates
  `manager.allContexts()`, it never hardcodes a client count.
- **New message source needing different processing**: implement `MessageProcessor`, wire per
  `ClientType` via Spring `@Qualifier` or a `Map<ClientType, MessageProcessor>` bean — `reader` package
  is unaffected.
- **Different retry shape (e.g., jittered backoff)**: swap the `RetryPolicy` implementation; nothing
  outside `util` depends on its internals.
- **Failover to a secondary AMPS instance**: handled entirely inside `AmpsClientManager` (`HAClient`'s
  `ServerChooser`/failover config) — invisible to `listener`/`reader`, since from their perspective a
  failover is just another `Disconnected` → `Reconnecting` → `LoggedOn` cycle (see `FailoverTests` in
  `TEST_PLAN.md`).

---

## 6. Non-Goals

- This framework does not implement message persistence, replay, or bookmark store management beyond
  what's needed to resubscribe cleanly — that's a separate concern from connection/reader lifecycle.
- It does not implement AMPS server-side configuration (queue creation, ACLs) — those are assumed to
  already exist.
- It does not attempt to abstract away AMPS as a vendor (no pluggable "MessageBroker" interface) — the
  goal is a correct, production-grade AMPS integration, not a broker-agnostic framework.
