# AMPS Primary–Secondary HA Queue Behavior — Summary

## 1. Scenario

The architecture consists of:

- One AMPS **Primary** server.
- One AMPS **Secondary** server.
- A Java publisher producing messages.
- A Java HA-client application with **10 subscribers**.
- The same queue is replicated between Primary and Secondary.
- Subscribers use an AMPS HA client and can reconnect/fail over between AMPS instances.
- Queue delivery semantics are **at-least-once**.
- Some subscribers may be connected to Primary while others are connected to Secondary.

Example current state:

```text
                    Java Publisher
                         |
                         v
                 +---------------+
                 | AMPS PRIMARY  |
                 | S1 S2 S3 S4 S5|
                 +-------+-------+
                         |
                    Replication
                         |
                 +-------+-------+
                 | AMPS SECONDARY|
                 | S6 S7 S8 S9 S10
                 +---------------+
```

The important point is that Primary and Secondary are not two independent queues.

---

# 2. Core Mental Model

Think of the replicated queue as having:

- Message state
- Acknowledgement state
- Queue state
- Message ownership state
- Replication state

The same message may physically exist on both AMPS instances because of replication, but that does **not** mean both instances independently deliver that message.

Conceptually:

```text
M1
 |
 +---- Primary copy
 |
 +---- Secondary replicated copy
```

But:

```text
M1 has ONE active queue owner at a time
```

The owner is responsible for delivering the message.

Therefore:

> **Message replication does not mean duplicate delivery. Ownership determines which AMPS instance can deliver the queue message.**

---

# 3. Five Subscribers on Primary and Five on Secondary

Suppose the current state is:

```text
Primary:
    S1
    S2
    S3
    S4
    S5

Secondary:
    S6
    S7
    S8
    S9
    S10
```

Each subscriber has its own direct connection:

```text
S1-S5  <----> Primary

S6-S10 <----> Secondary
```

There is no need for Primary to forward messages through a client connection to Secondary subscribers.

Instead:

```text
Primary <-------- replication --------> Secondary

S1-S5  <--------> Primary
S6-S10 <---------> Secondary
```

Replication synchronizes the queue/message/ownership state, while each AMPS server manages its own local client connections.

---

# 4. What Happens When a Producer Publishes?

Assume the producer publishes:

```text
M1
```

to Primary.

Conceptually:

```text
Publisher
    |
    | M1
    v
Primary
```

Primary initially owns the message:

```text
M1 OWNER = Primary
```

The message is then replicated to Secondary:

```text
Primary
   |
   | replicate M1
   v
Secondary
```

Secondary now has a replicated copy/state of M1, but:

```text
M1 OWNER = Primary
```

Therefore Secondary does not automatically deliver M1 merely because it has the replicated copy.

---

# 5. Primary Delivers to Its Local Subscribers

If Primary has an eligible subscriber:

```text
M1 -> S1
```

Primary delivers M1 to one of its local queue subscribers.

For example:

```text
Primary:

S1 -> M1
S2 -> M2
S3 -> M3
S4 -> M4
S5 -> M5
```

Secondary may have S6-S10 available, but that alone does not mean Secondary immediately consumes Primary-owned messages.

The message ownership model prevents both servers from independently delivering the same message.

---

# 6. Ownership Is More Important Than Subscriber Count

AMPS does **not** simply perform this:

```text
10 subscribers
5 on Primary
5 on Secondary

Therefore:
50% messages -> Primary
50% messages -> Secondary
```

That is not the correct mental model.

Instead:

```text
Message
   |
   v
Current Owner
   |
   +--> local eligible subscriber
   |
   +--> ownership may be transferred
        when appropriate
```

The exact distribution depends on queue configuration, subscriber availability, queue state, leases, ownership-transfer behavior, etc.

Therefore, having 5 subscribers on each server does not guarantee a 50/50 message distribution.

---

# 7. Ownership Transfer

Suppose Primary owns M6 but its local subscribers cannot currently make progress, while Secondary has available queue subscribers.

Conceptually:

```text
M6 OWNER = Primary

Primary subscribers:
    S1-S5 busy/unavailable

Secondary subscribers:
    S6-S10 available
```

AMPS can use its distributed queue ownership mechanism to transfer ownership of an eligible message.

Conceptually:

```text
Before:

M6 OWNER = Primary

After ownership transfer:

M6 OWNER = Secondary
```

Secondary can then deliver:

```text
M6 -> S6
```

The exact timing and conditions depend on the AMPS queue configuration.

---

# 8. Important: Primary Does Not "Push Through" Secondary

A common misconception is:

```text
Publisher
   |
   v
Primary
   |
   +----> S1-S5
   |
   +----> Secondary
             |
             +----> S6-S10
```

This is not the correct way to think about it.

Instead:

```text
                   Replicated Queue State
              +----------------------------+
              |                            |
              v                            v
          PRIMARY                     SECONDARY
              |                            |
           S1-S5                        S6-S10
```

Each AMPS instance directly manages its connected clients.

Replication allows the instances to coordinate queue state and ownership.

---

# 9. At-Least-Once Delivery

Now consider:

```text
M3
 |
 v
Primary
 |
 v
S3
 |
 | message received
 |
 X ACK not sent
```

The Java application has received M3, but AMPS has not received the successful queue acknowledgement.

Therefore, from the queue's perspective, M3 has not been successfully completed.

This is the important distinction:

```text
Application received message
            !=
AMPS received successful ACK
```

At-least-once delivery intentionally favors:

```text
No message loss
+
Possible duplicate delivery
```

rather than:

```text
No duplicate
+
Possible message loss
```

---

# 10. Subscriber Connection Is Killed

Suppose S3 is connected to Primary:

```text
Primary:
    S3 -> M3
```

M3 is not acknowledged.

Now the S3 connection is killed manually from the AMPS administration interface.

The Java HA client detects the disconnect.

Its server-selection/reconnection logic can cause it to connect to another available AMPS instance.

For example:

```text
S3
 |
 X
 |
 v
HA Client
 |
 v
Secondary
```

Now S3 may be connected to Secondary.

---

# 11. What Happens to the Unacknowledged Message?

M3 was:

```text
Delivered to S3
+
ACK was not successfully recorded
```

Therefore M3 is still outstanding from the queue's perspective.

After the appropriate queue lease/ownership recovery behavior occurs, M3 can become eligible for delivery again.

It may then be delivered again to an eligible subscriber:

```text
Primary:

M3 -> S3
      |
      X no ACK
      |
connection lost

Secondary:

M3 -> S3 or another eligible subscriber
```

This is the expected type of behavior for at-least-once processing.

Do not assume the message must necessarily return to the same subscriber. Queue ownership and available consumers determine who can receive it.

---

# 12. Important ACK Timing Case

Consider:

```text
T1: Primary sends M3
T2: Java receives M3
T3: Java processes M3
T4: Java sends ACK
T5: Primary records ACK
T6: ACK state is replicated
```

If the subscriber fails between T3 and T5:

```text
Java:
    M3 processed

AMPS:
    ACK not recorded
```

AMPS may redeliver M3.

The application may therefore process M3 twice.

This is a normal consequence of at-least-once semantics.

Therefore, business processing should ideally be **idempotent**.

---

# 13. Replication and ACK Durability

Replication configuration is extremely important.

Consider:

```text
Primary:
    M3 = ACKED

Secondary:
    M3 = NOT ACKED
```

This temporary state can matter if Primary fails before the acknowledgement state is safely replicated.

For queue consumers that can fail over, synchronous replication is important to evaluate because the secondary needs the correct replicated queue/acknowledgement state.

Validate the AMPS replication configuration, particularly:

```text
SyncType
```

and ensure the queue's acknowledgement/ownership state is replicated appropriately.

---

# 14. Example with 10 Messages

Suppose the producer publishes:

```text
M1 M2 M3 M4 M5 M6 M7 M8 M9 M10
```

Initially:

```text
Message    Owner
-------    --------
M1         Primary
M2         Primary
M3         Primary
M4         Primary
M5         Primary
M6         Primary
M7         Primary
M8         Primary
M9         Primary
M10        Primary
```

Secondary has replicated copies/state:

```text
Secondary:
    M1-M10
```

but the messages remain owned according to AMPS queue ownership rules.

Primary may consume:

```text
M1 -> S1
M2 -> S2
M3 -> S3
M4 -> S4
M5 -> S5
```

If additional messages remain outstanding and ownership transfer becomes appropriate, some messages can become owned by Secondary:

```text
M6 -> Secondary
M7 -> Secondary
...
```

and then:

```text
M6 -> S6
M7 -> S7
...
```

The actual distribution is determined by AMPS queue behavior/configuration and is not guaranteed to be exactly five messages per server.

---

# 15. Publisher Connection to Secondary

AMPS replication should also be understood independently of the concept of a traditional passive standby.

Depending on configuration, either AMPS instance may accept publishes.

For example:

```text
Publisher A
    |
    v
Primary
    |
    | replicate
    v
Secondary
```

and potentially:

```text
Publisher B
    |
    v
Secondary
    |
    | replicate
    v
Primary
```

The important part is that queue ownership/state is maintained across the replicated topology.

---

# 16. Three Important States for Every Message

When troubleshooting this architecture, think about every message in terms of three questions:

### 1. Where is the message?

```text
Primary
Secondary
Both through replication
```

### 2. Who owns the message?

```text
Owner = Primary
```

or:

```text
Owner = Secondary
```

### 3. Has it been successfully acknowledged?

```text
ACK = YES
```

or:

```text
ACK = NO
```

These three dimensions explain most of the behavior you will observe.

---

# 17. Recommended Test Scenario

Use a unique message ID for every test message.

For example:

```text
Message ID: TEST-0001
```

Record:

```text
Message ID
Subscriber ID
AMPS server
Connection ID
Publish timestamp
Delivery timestamp
ACK timestamp
Processing completion timestamp
Reconnect timestamp
```

Then run these tests.

## Test A — Normal ACK

```text
Publisher
   |
   v
Primary
   |
   v
S1
   |
   v
Process
   |
   v
ACK
```

Expected:

```text
Message processed once
```

---

## Test B — Receive but do not ACK

```text
Publisher
   |
   v
Primary
   |
   v
S1
   |
   X
No ACK
```

Then disconnect S1.

Reconnect S1 to Secondary.

Observe whether the outstanding message becomes eligible again according to queue lease/ownership rules.

Expected possibility:

```text
Same message delivered again
```

This is consistent with at-least-once semantics.

---

## Test C — Process + ACK + disconnect

```text
Publisher
   |
   v
Primary
   |
   v
S1
   |
Process
   |
ACK
   |
Disconnect
```

Verify that the message is not unnecessarily redelivered after failover.

This test is particularly important for validating ACK replication.

---

## Test D — Five subscribers on each server

```text
Primary:
    S1 S2 S3 S4 S5

Secondary:
    S6 S7 S8 S9 S10
```

Publish a large number of messages.

Measure:

```text
Message ID
Owner
Delivery server
Subscriber
ACK status
```

Do not assume a 50/50 distribution.

---

## Test E — Primary failure

Start with:

```text
S1-S5 -> Primary
S6-S10 -> Secondary
```

Publish messages.

Then stop Primary.

Verify:

```text
S1-S5
   |
   X
   |
HAClient
   |
   v
Secondary
```

Then verify:

- Existing outstanding messages
- Unacknowledged messages
- Acknowledged messages
- New messages
- Ownership transfer
- Duplicate processing
- Message ordering
- Reconnect/resubscription behavior

---

# 18. Application-Level Recommendation

Because the queue provides at-least-once semantics, the Java application should be prepared for duplicate delivery.

A useful pattern is:

```text
Receive message
      |
      v
Extract unique business/message ID
      |
      v
Check whether already processed
      |
   +--+--+
   |     |
  YES    NO
   |     |
   |     v
   |   Process
   |     |
   |     v
   |   Commit
   |     |
   |     v
   |    ACK
   |
 Ignore/reconcile duplicate
```

The exact implementation depends on your business transaction model.

The key objective is:

> **Processing the same message twice should not corrupt business state.**

---

# 19. The Simplified Mental Model

Remember these five rules:

### Rule 1

**Replication creates synchronized state; it does not mean both servers independently deliver every message.**

### Rule 2

**A queue message has an owner.**

### Rule 3

**The owning AMPS instance normally delivers the message to its eligible local queue subscribers.**

### Rule 4

**Ownership can move between replicated AMPS instances when queue/HA conditions require it.**

### Rule 5

**If a message is delivered but its acknowledgement is not successfully recorded, at-least-once semantics means that message can be delivered again.**

---

# 20. Final Architecture View

```text
                           JAVA PRODUCER
                                |
                                v
                     +----------------------+
                     |    AMPS PRIMARY      |
                     |                      |
                     | Queue Q              |
                     |                      |
                     | S1 S2 S3 S4 S5       |
                     +----------+-----------+
                                |
                         Replication
                  message + queue state +
                  ACK + ownership state
                                |
                                v
                     +----------------------+
                     |   AMPS SECONDARY     |
                     |                      |
                     | Queue Q              |
                     |                      |
                     | S6 S7 S8 S9 S10      |
                     +----------------------+


Client connections:

S1-S5  <--------> Primary

S6-S10 <---------> Secondary


For each message:

                 +----------------+
                 | Queue Message  |
                 +-------+--------+
                         |
                         v
                   Current Owner
                    /         \
                   /           \
                  v             v
             Primary        Secondary
                |                |
                v                v
          Local consumers   Local consumers
```

The essential architecture is therefore:

```text
        MESSAGE
           |
           v
      OWNERSHIP
           |
           v
   ELIGIBLE LOCAL CLIENT
           |
           v
       DELIVERY
           |
           v
         ACK
           |
     +-----+-----+
     |           |
    ACK         NO ACK
     |           |
  Complete    Outstanding
                 |
                 v
        Ownership/lease recovery
                 |
                 v
          Possible redelivery
```

## Bottom Line

With **5 subscribers connected to Primary and 5 connected to Secondary**, both AMPS servers can participate in consuming the replicated queue.

The servers do not simply split messages 50/50 and they do not blindly deliver every replicated message to their local subscribers.

Instead:

```text
Publisher
    |
    v
AMPS instance receives message
    |
    v
Message gets queue ownership
    |
    v
Owner delivers to an eligible local subscriber
    |
    v
ACK
    |
    +---- ACK received --> message completed
    |
    +---- ACK missing --> message remains outstanding
                              |
                              v
                    lease/ownership recovery
                              |
                              v
                       possible redelivery
```

Therefore, your observed situation where some HA subscribers reconnect to Secondary is compatible with the distributed queue model. The critical items to inspect in your actual AMPS configuration are **queue type, lease configuration, ownership-transfer behavior, replication `SyncType`, acknowledgement replication, and the Java HA client's resubscription behavior**.
