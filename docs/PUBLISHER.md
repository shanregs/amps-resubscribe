# PUBLISHER.md — amps-publisher-simple

Companion to `CLAUDE.md`'s "Publisher Module" section. This document covers the module's component
contracts and rate-limiting design in the same level of detail `DESIGN.md` gives the reconnect
framework — `amps-publisher-simple` is the producer; `amps-reconnect-framework` /
`amps-reconnect-simple` are the consumers that need it running to have anything to observe during HA
failover testing (`docs/HA_AMPS_LAB_SETUP_GUIDE.md`).

---

## 1. Why a separate module

Both reconnect modules prove resubscribe/reader-restart correctness *given messages are flowing*.
Neither can generate that traffic itself without conflating "am I publishing correctly" with "am I
consuming/recovering correctly." Keeping publishing in its own process, with its own configuration and
lifecycle, means:

* it can run unattended for as long as a soak/stress test needs, independent of how many times a
  reconnect module under test is restarted,
* its rate is deterministic and configurable, so `MultiReconnectTests`/`ThreadLeakTests`-style manual
  soak runs (`TEST_PLAN.md`) have a known message arrival rate to reason about, and
* it can fail over between LAB PRIMARY/SECONDARY independently of whichever server a given reconnect
  module instance happens to be attached to at that moment.

---

## 2. Component Responsibilities

| Component | Responsibility | Must NOT do |
|---|---|---|
| `AmpsPublishConfig` | Bind `amps.publisher.*` from `application.yml`: client name, failover `uris`, and a list of `Target` (queue + rps + burst). Defaults a blank/empty config to one target on `orders.queue`. | Touch `HAClient` or threads. |
| `AmpsPublisher` | `SmartLifecycle`. Own the single `HAClient`, one publish loop (virtual thread) per configured `Target`, and the connection-state gating that pauses/resumes those loops. | Manage subscriptions — publishing carries no subscription state, so there is deliberately no listener/reader/context split here the way the consumer side has one. |
| `RateLimiter` | Token-bucket: `ratePerSecond` tokens refill continuously up to `burstCapacity`, starting full. `acquire()` blocks the calling loop until a token is available. | Know about AMPS, queues, or messages — pure rate math. |
| `OrderPayloadGenerator` | Build one synthetic order-event JSON payload per call (`id`, `symbol`, `side`, `qty`, `queue`, `ts`), with a process-wide monotonically increasing `id`. | Know about `HAClient`, threads, or rate limiting. |

---

## 3. Rate Limiting Design

Each `Target` gets its own `RateLimiter` instance seeded with that target's `rps` (sustained rate) and
`burst` (bucket capacity):

```
tokens(t) = min(burst, tokens(t-1) + elapsedSeconds * rps)

acquire():
    loop:
        refill tokens from elapsed time
        if tokens >= 1: tokens -= 1; return
        sleep until enough time has elapsed to have >= 1 token, then retry
```

The bucket **starts full** (`tokens = burst`), so a freshly started publisher — or one that just
reconnected after a pause — can immediately emit up to `burst` messages back-to-back before settling
into the steady `rps` rate. This is deliberate: it lets the publisher "catch up" a little after a
reconnect-induced pause rather than staying artificially throttled, without ever exceeding `burst`
messages in a single instant.

**Why token-bucket over `ScheduledExecutorService.scheduleAtFixedRate`:** a fixed-rate scheduler gives
exactly `rps` with no burst headroom and no clean way to express "allow short bursts above the
sustained rate" — which is precisely the second configuration knob this module needs.

---

## 4. Connection Handling

`AmpsPublisher` connects one `HAClient` at startup using the same `DefaultServerChooser` failover
pattern `amps-reconnect-simple`'s `AmpsQueueClient` uses against the lab's primary/secondary URIs
(`docs/HA_AMPS_LAB_SETUP_GUIDE.md`). Unlike the consumer side, there is no resubscribe step on
reconnect — publishing carries no server-side subscription to restore — so the only recovery concern is
*not publishing into a dead connection*:

```
onConnectionStateChanged(LoggedOn):        loggedOn = true
onConnectionStateChanged(Disconnected|Shutdown): loggedOn = false

publishLoop(target, limiter):
    while running:
        if !loggedOn:
            sleep briefly, retry            # no token consumed while disconnected
        else:
            limiter.acquire()               # blocks for the next available token
            haClient.publish(target.queue(), payload)   # AMPSException caught + logged, loop continues
```

Not consuming a token while disconnected means the bucket keeps filling (up to `burst`) during an
outage, so the "catch-up burst" described in §3 also applies after a real failover, not just at
process startup.

---

## 5. Threading

One virtual thread per configured `Target`, named `amps-publisher-<queue>`, started in
`AmpsPublisher.start()` and joined (bounded wait) in `stop()` — the same ownership discipline
`THREADING_MODEL.md` describes for the reconnect framework's reader threads, scaled down to this
module's single-lifecycle-method simplicity. There is no separate recovery executor: connection-state
callbacks here only flip a `volatile`/`AtomicBoolean` flag, never submit a blocking AMPS command, so
the "don't submit commands from the connection-state callback thread" constraint (`THREADING_MODEL.md`
§4) doesn't apply — publishing itself already happens on the dedicated per-target thread, not the
callback thread.

---

## 6. Configuration Reference

```yaml
amps:
  publisher:
    client-name: order-publisher
    uris:
      - tcp://172.21.12.69:9107/amps/json   # LAB PRIMARY
      - tcp://172.21.12.69:9117/amps/json   # LAB SECONDARY
    targets:
      - queue: orders.queue
        rps: 20
        burst: 40
      - queue: risk.queue
        rps: 5
        burst: 10
```

`amps.publisher.targets[].queue` should match the `amps.queue` (or per-client `queue`) value the
reconnect module under test is configured to read from — see that module's own `application.yml`.

---

## 7. Non-Goals

* No delivery guarantees beyond what `HAClient.publish()` itself provides — this is a traffic
  generator for exercising reconnect behavior, not a durable publish pipeline.
* No per-message business payload realism — `OrderPayloadGenerator`'s fields exist to give consumers
  something to log/assert against (a distinct `id`, a `queue` tag), not to model a real trading system.
* No dynamic rate changes at runtime — `rps`/`burst` are read once at startup from `application.yml`;
  changing them requires a restart, consistent with this module's "simple" scope.
