# SEQUENCE_DIAGRAM.md — AMPS HA Client Reconnection Framework

Mermaid sequence diagrams for the four flows defined in `CALL_FLOW.md`. Participant names match class
names in the planned package structure (`ARCHITECTURE.md` §2) exactly, so these diagrams can be checked
directly against the implementation.

> **Threading note:** the diagrams below show `AmpsConnectionListener` calling `stop()`/`resubscribe()`/
> `start()` directly for readability. In the implementation those calls are enqueued onto a per-client
> `recoveryExecutor` rather than executed inline on the AMPS callback thread — see `THREADING_MODEL.md` §4.
> The ordering and locking guarantees shown are unaffected; only which thread executes them changes.

---

## 1. Startup Sequence

```mermaid
sequenceDiagram
    participant Spring as Spring Boot
    participant LM as AmpsLifecycleManager
    participant CM as AmpsClientManager
    participant HA as HAClient
    participant CL as AmpsConnectionListener
    participant CTX as AmpsClientContext
    participant RD as AmpsMessageReader
    participant MP as MessageProcessor

    Spring->>LM: start()
    LM->>CM: initialize(definitions)
    CM->>CTX: new AmpsClientContext(type, haClient)
    CM->>RD: ExecutorFactory.create(type)
    LM->>HA: addConnectionStateListener(CL)
    LM->>CM: connect(type)
    CM->>CTX: transition(CREATED, CONNECTING)
    CM->>HA: connect()
    HA-->>CL: Connected callback
    CL->>CTX: transition(CONNECTING, CONNECTED)
    HA-->>CL: LoggedOn callback
    CL->>CTX: transition(CONNECTED, LOGGED_ON)
    CL->>CM: subscribe(type)
    CM->>HA: executeAsync(subscribeCommand)
    HA-->>CM: ack [subscriptionId]
    CM->>CTX: subscriptionId(id), transition(LOGGED_ON, SUBSCRIBED)
    CL->>RD: start(context)
    RD->>CTX: readerRunning.compareAndSet(false, true)
    RD->>RD: executor.submit(readLoop)
    CTX->>CTX: transition(SUBSCRIBED, READING)
    loop read loop
        RD->>CTX: messageStream.next()
        RD->>MP: process(type, message)
    end
```

---

## 2. Disconnect / Recovery Sequence

```mermaid
sequenceDiagram
    participant HA as HAClient
    participant CL as AmpsConnectionListener
    participant CTX as AmpsClientContext
    participant RD as AmpsMessageReader
    participant CM as AmpsClientManager

    Note over RD: reader thread blocked in messageStream.next()
    HA-->>CL: Disconnected callback
    activate CL
    CL->>CTX: recoveryLock.lock()
    CL->>CTX: transition(READING, DISCONNECTED)
    CL->>RD: stop(context)
    RD->>CTX: messageStream.close()
    RD->>RD: readLoopShouldStop = true
    Note over RD: reader thread exits (connection exception or flag)
    RD->>RD: readerTask.get(STOP_TIMEOUT) — blocks until thread dead
    RD->>CTX: readerRunning.set(false)
    RD-->>CL: stop() returns (confirmed dead)
    CL->>CTX: transition(DISCONNECTED, WAITING)
    CL->>CTX: recoveryLock.unlock()
    deactivate CL

    Note over HA: AMPS retries reconnect internally (unbounded, backoff owned by SDK/config)
    HA-->>CL: Reconnecting callback (0..n times)
    CL->>CTX: transition(WAITING, RECONNECTING)

    HA-->>CL: Connected callback
    CL->>CTX: transition(RECONNECTING, CONNECTED)
    HA-->>CL: LoggedOn callback
    activate CL
    CL->>CTX: recoveryLock.lock()
    CL->>CTX: transition(CONNECTED, LOGGED_ON)
    CL->>CM: resubscribe(type)
    CM->>HA: executeAsync(subscribeCommand)
    HA-->>CM: ack [new subscriptionId]
    CM->>CTX: subscriptionId(newId)
    CL->>CTX: transition(LOGGED_ON, RESUBSCRIBED)
    CL->>RD: start(context)
    RD->>CTX: readerRunning.compareAndSet(false, true)
    RD->>RD: executor.submit(readLoop)  Note right of RD: same executor as startup, new task
    CL->>CTX: transition(RESUBSCRIBED, READING)
    CL->>CTX: recoveryLock.unlock()
    deactivate CL
    Note over RD: message processing resumes, no application restart
```

---

## 3. Failover Sequence

```mermaid
sequenceDiagram
    participant Primary as AMPS Primary
    participant Secondary as AMPS Secondary
    participant HA as HAClient
    participant CL as AmpsConnectionListener
    participant CTX as AmpsClientContext
    participant RD as AmpsMessageReader
    participant CM as AmpsClientManager

    Note over Primary: Primary AMPS shut down
    Primary--xHA: connection lost
    HA-->>CL: Disconnected callback
    Note over CL,RD: identical to Disconnect/Recovery Sequence steps 1-6
    CL->>RD: stop(context)  (blocking, confirmed dead)
    CL->>CTX: transition(*, WAITING)

    Note over HA: ServerChooser selects Secondary (HAClient config, not framework logic)
    HA->>Secondary: connect()
    Secondary-->>HA: Connected
    HA-->>CL: Connected callback
    HA-->>CL: LoggedOn callback
    CL->>CM: resubscribe(type)
    CM->>Secondary: executeAsync(subscribeCommand)
    Secondary-->>CM: ack [subscriptionId]
    CL->>RD: start(context)
    Note over CL,RD: identical continuation to Disconnect/Recovery Sequence — listener/reader code path is unaware a failover occurred
```

---

## 4. Shutdown Sequence

```mermaid
sequenceDiagram
    participant Spring as Spring Boot
    participant LM as AmpsLifecycleManager
    participant CTX as AmpsClientContext
    participant RD as AmpsMessageReader
    participant CM as AmpsClientManager
    participant HA as HAClient

    Spring->>LM: stop()
    loop for each AmpsClientContext
        LM->>RD: stop(context)
        RD->>CTX: messageStream.close(), readLoopShouldStop = true
        RD->>RD: readerTask.get(STOP_TIMEOUT)
        RD->>CTX: readerRunning.set(false)
        LM->>CM: disconnect(type)
        CM->>HA: close()/disconnect()
        LM->>CTX: executor.shutdown()
        alt not terminated within SHUTDOWN_TIMEOUT
            LM->>CTX: executor.shutdownNow()
        end
    end
    LM-->>Spring: isRunning() == false
```
