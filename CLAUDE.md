# AMPS HA Client Reconnection Framework

## Project Goal

Refactor the current AMPS integration into a production-grade, fault-tolerant framework that automatically recovers from HAClient disconnects, reconnects, and queue resubscriptions without requiring an application restart.

This project targets:

* Java 21
* Spring Boot 4.1.x
* AMPS Java Client 5.3.3.3
* Maven

---

# Actual Module Layout

The single-module package plan later in this document (`## Planned Project Structure`) describes the
*internal class layout* implemented inside `amps-reconnect-framework`. In practice the repo is a Maven
multi-module reactor with three modules, not one:

| Module | Purpose |
|---|---|
| `amps-reconnect-framework` | Full SOLID-architecture implementation of the recovery state machine — one class per responsibility, per `ARCHITECTURE.md`/`DESIGN.md`. |
| `amps-reconnect-simple` | The same reconnect/resubscribe/reader-restart guarantees, condensed into 4 core classes (`AmpsQueueClient`, `AmpsSubscribeConfig`, `MessageProcessor`, `Application`) for a minimal reference implementation. |
| `amps-publisher-simple` | Rate-limited synthetic order publisher — generates the live queue traffic both reconnect modules need in order to observe anything during HA failover testing (see `## Publisher Module` below). |

Both `amps-reconnect-*` modules are consumers; `amps-publisher-simple` is the producer that feeds
them. Running a reconnect module against an idle queue proves nothing about resubscribe/reader-restart
behavior — the publisher is what makes disconnect/reconnect cycles actually carry messages to verify
continuity against.

---

# HA Validation Environment

`docs/HA_AMPS_LAB_SETUP_GUIDE.md` is the source of truth for the two-node AMPS HA lab (LAB PRIMARY
`9107`/`9108`/`8185`, LAB SECONDARY `9117`/`9118`/`8195`, queues `/queue/trades`, `/queue/orders`,
`/queue/risk`, `/queue/test-trades`) used to validate this project's reconnect/resubscribe/reader-restart
behavior end-to-end, fully isolated from any production AMPS instance. Point every module's
`amps.uris` / `amps.publisher.uris` at that lab's primary/secondary URIs (not production's) when
running failover tests. `docs/HA_CLIENT_TEST_MATRIX_STEP_BY_STEP.md` documents an older, simpler
two-instance local setup with its own defaults — see that file for how its ports relate to the lab's.

---

# Current Implementation Problems

## Problem 1

Application startup, connection management, queue subscription, message reading and reconnect logic are tightly coupled.

Current flow

```
Application Start

↓

Create HAClient

↓

Subscribe Queue

↓

Read MessageStream

↓

Business Processing
```

There is no ownership separation.

---

## Problem 2

Message reader exits after disconnect.

```
while(stream.next())
```

When AMPS disconnects

```
stream.next()

↓

Exception

↓

Loop exits

↓

Reader dies
```

Nothing restarts it.

---

## Problem 3

Reconnect succeeds but queue processing never resumes.

Current runtime

```
Disconnected

↓

HAClient reconnects

↓

Application still idle

↓

No MessageStream

↓

No Reader

↓

No Messages
```

---

## Problem 4

HAClient lifecycle is not isolated.

Connection logic is mixed with

* Startup
* Queue logic
* Business logic

---

## Problem 5

No recovery state machine exists.

There is no deterministic recovery sequence.

---

# Project Objectives

The new implementation must provide

* automatic reconnect
* automatic resubscribe verification
* automatic reader restart
* no duplicate subscriptions
* no thread leaks
* thread-safe execution
* SOLID architecture
* testability
* production logging

---

# Proposed Architecture

```
                Spring Boot

                     │

                     ▼

        AmpsLifecycleManager

                     │

                     ▼

          AmpsClientManager

                     │

      ┌──────────────┼───────────────┐

      ▼              ▼               ▼

  HAClient1      HAClient2      HAClient3

      │              │               │

      └──────────────┬───────────────┘

                     ▼

       AmpsConnectionListener

                     │

                     ▼

          AmpsMessageReader

                     │

                     ▼

         Business Processor
```

---

# Design Principles

## Single Responsibility

Every class has exactly one responsibility.

Lifecycle

↓

Connection

↓

Subscription

↓

Reading

↓

Business Processing

---

## Dependency Direction

```
Lifecycle

↓

ClientManager

↓

Listener

↓

Reader

↓

Business
```

Dependencies never point upwards.

---

## Thread Ownership

Only one component owns reader threads.

```
AmpsMessageReader
```

No other class creates threads.

---

## Connection Ownership

Only one component owns HAClients.

```
AmpsClientManager
```

No other class directly manipulates HAClient.

---

# Planned Project Structure

```
amps-reconnect/

README.md

CLAUDE.md

DESIGN.md

ARCHITECTURE.md

THREADING_MODEL.md

CALL_FLOW.md

SEQUENCE_DIAGRAM.md

TEST_PLAN.md

pom.xml

src/

    main/

        java/

            config/

                AmpsConfiguration.java

            lifecycle/

                AmpsLifecycleManager.java

            manager/

                AmpsClientManager.java

            listener/

                AmpsConnectionListener.java

            reader/

                AmpsMessageReader.java

            processor/

                MessageProcessor.java

            context/

                AmpsClientContext.java

            model/

                ClientType.java

                ConnectionState.java

            util/

                RetryPolicy.java

                ExecutorFactory.java

            Application.java

    test/

        java/

            StartupTests.java

            DisconnectRecoveryTests.java

            ResubscribeTests.java

            ReaderRestartTests.java

            MultiReconnectTests.java

            FailoverTests.java

            ThreadLeakTests.java

            DuplicateSubscriptionTests.java

            IntegrationTests.java
```

---

# Recovery State Machine

```
CREATED

↓

CONNECTING

↓

CONNECTED

↓

LOGGED_ON

↓

SUBSCRIBED

↓

READING

↓

DISCONNECTED

↓

WAITING

↓

RECONNECTING

↓

CONNECTED

↓

LOGGED_ON

↓

RESUBSCRIBED

↓

READING
```

---

# Runtime Call Flow

Application Start

↓

SmartLifecycle.start()

↓

Create 3 HAClients

↓

Register Connection Listener

↓

Connect Clients

↓

Subscribe Queues

↓

Create MessageStreams

↓

Start Reader Threads

↓

Receive Messages

↓

Business Processing

---

Disconnect Flow

Connection Lost

↓

Connection Listener

↓

Reader Cleanup

↓

Wait

↓

Reconnect

↓

LoggedOn

↓

Resubscribed

↓

Create New MessageStream

↓

Restart Reader

↓

Continue Processing

---

# Test Strategy

## Unit Tests

* Client creation
* Context initialization
* Reader startup
* Listener callbacks
* Recovery methods
* Retry logic

---

## Integration Tests

* Startup
* Queue subscription
* Disconnect
* Reconnect
* Resubscribe
* Message continuity

---

## Stress Tests

100 reconnect cycles

Verify

* no duplicate readers
* no duplicate subscriptions
* no thread leaks
* no stale MessageStreams

---

## Failover Tests

Shutdown Primary AMPS

↓

Secondary becomes active

↓

Reconnect

↓

Resubscribe

↓

Continue Processing

---

# Logging

Every important lifecycle event will be logged.

Example

```
CLIENT_1 Connected

CLIENT_1 LoggedOn

CLIENT_1 Resubscribed

CLIENT_1 Reader Started

CLIENT_1 Reader Stopped

CLIENT_1 Reader Restarted

CLIENT_1 Queue Resubscribed
```

---

# Publisher Module

`amps-publisher-simple` is a standalone Spring Boot module that publishes synthetic order-event
messages onto configured AMPS queues at a steady, configurable rate, so the reconnect modules have
live traffic to observe — running `amps-reconnect-simple` against an idle queue proves nothing about
resubscribe/reader-restart behavior on its own.

## Configuration

```yaml
amps:
  publisher:
    client-name: order-publisher
    uris:
      - tcp://172.21.12.69:9107/amps/json   # LAB PRIMARY, per HA_AMPS_LAB_SETUP_GUIDE.md
      - tcp://172.21.12.69:9117/amps/json   # LAB SECONDARY
    targets:
      - queue: orders.queue
        rps: 20      # sustained publish rate for this queue, messages/second
        burst: 40    # token-bucket capacity: how many messages can be emitted back-to-back
                      # above the sustained rate (startup, or catching up after a reconnect pause)
```

`targets` is a list, so one publisher process can drive several queues simultaneously (e.g. `trades`,
`orders`, `risk` from the lab guide), each with its own independent `rps`/`burst`.

## Behavior

* Connects one `HAClient` at startup via the same `DefaultServerChooser` failover pattern the reconnect
  modules use, and reconnects automatically the same way — the publisher needs no resubscribe logic of
  its own since publishing carries no subscription state.
* Runs one publish loop (virtual thread) per configured target queue, throttled by a token-bucket rate
  limiter seeded with that target's `rps`/`burst`.
* Publishing pauses automatically while the client is disconnected (no wasted publish attempts, no
  token-bucket drain during an outage) and resumes the moment `HAClient` reports `LoggedOn` again.
* Runs continuously — start it once, point `amps-reconnect-simple` (or `-framework`) at the same lab
  queues, and messages are visible flowing end-to-end: publisher → AMPS queue → reconnect module's
  `MessageProcessor`, including across a disconnect/reconnect cycle on either side.

---

# Deliverables

The final project will contain

* Production-ready source code
* Complete JavaDocs
* Architecture documentation
* Sequence diagrams
* Threading diagrams
* Call flow diagrams
* Unit tests
* Integration tests
* Recovery tests
* Stress tests
* README
* Maven project
* Spring Boot 4.1 configuration

---

# Success Criteria

The framework is considered complete when:

* Application starts successfully.
* Three HAClients connect successfully.
* All queues subscribe successfully.
* Reader threads process messages.
* Disconnects are detected.
* HAClients reconnect automatically.
* Queue subscriptions are restored.
* New MessageStreams are created.
* Reader threads restart automatically.
* Messages continue processing without restarting the application.
* Multiple reconnect cycles execute without leaks or duplicate subscriptions.
* `amps-publisher-simple` publishes to a configured queue at its configured `rps`/`burst`, and a
  running reconnect module observes those messages continuously — including across a disconnect/
  reconnect cycle on the consuming side.
