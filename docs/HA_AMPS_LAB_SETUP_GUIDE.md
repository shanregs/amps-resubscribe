# HA AMPS Lab Setup Guide

**A Practical Guide to Standing Up a Two-Node AMPS High-Availability Lab, with Spring Boot HAClient Integration**

> This revision fully isolates the HA lab from your live, working setup. **Nothing under `/home/shan/amps-5.3.5` or `/home/shan/AMPS` is touched, copied into, or referenced by this guide.** The lab gets its own binaries, its own config, its own data directories, and its own ports, so it can be built, torn down, and rebuilt freely without any risk to the running production instance.
>
> **Port assumption, please confirm:** since production may still be listening on `9007`/`9008`/`8085`, the lab uses a distinct block — `9107`/`9108`/`8185` for LAB PRIMARY, `9117`/`9118`/`8195` for LAB SECONDARY — so both can run at the same time. If you'd rather the lab reuse `9007`/`9008`/`8085` (only safe if production is stopped whenever the lab is up), say so and I'll swap the numbers back.

---

## Chapter 1 – Lab Overview

### Objective

Stand up a fully self-contained, two-node AMPS HA pair — independent of your production `amps-queue-concurrency` instance — and validate that a Spring Boot service using AMPS's `HAClient` can fail over between them transparently: connect, publish, subscribe, survive a node going down, reconnect, resubscribe, and keep processing.

### Prerequisites

| Requirement | Value |
|---|---|
| OS | WSL2 (`shan-msi-alpha`) |
| **Production install (do not touch)** | `/home/shan/amps-5.3.5`, config `amps-config.xml`, data at `/home/shan/AMPS/{logs,sow,journal}`, ports `9007/9008/8085` |
| **LAB PRIMARY binary** | `/home/shan/amps-ha/amps-primary` |
| **LAB SECONDARY binary** | `/home/shan/amps-ha/amps-secondary` |
| **LAB PRIMARY data** | `/home/shan/app-env/data/amps-ha/amps-primary/{logs,sow,journal}` |
| **LAB SECONDARY data** | `/home/shan/app-env/data/amps-ha/amps-secondary/{logs,sow,journal}` |
| JDK | 21+ |
| Spring Boot | 3.5.x+ |
| Queues under test | `/queue/trades`, `/queue/orders`, `/queue/risk`, `/queue/test-trades` |

**Design decision, reversed from the previous revision:** earlier drafts had PRIMARY and SECONDARY share one `ampServer` binary to save disk. You've now asked for genuinely separate binary directories, so this revision goes back to **two full, independent copies** of the AMPS install — one under `amps-ha/amps-primary`, one under `amps-ha/amps-secondary`. Tradeoff worth knowing: this costs roughly 2× the disk of the shared-binary approach, and if you ever patch AMPS, you now patch two install directories instead of one — but you get full isolation between the two lab nodes, and (more importantly for you right now) zero shared surface with production, since neither lab binary is anywhere near `/home/shan/amps-5.3.5`.

### Current Architecture (Production — unaffected by this guide)

```
                 ┌───────────────────────────────────────┐
  Publishers ───▶│  amps-queue-concurrency (production)     │
                 │  /home/shan/amps-5.3.5, config amps-config.xml │◀─── Subscribers
  Publishers ───▶│  data: /home/shan/AMPS/{logs,sow,journal}  │
                 │  tcp://172.21.12.69:9007  — started via `startamps` │
                 └───────────────────────────────────────┘
```

### Final HA Lab Architecture

```
                     ┌────────────────────────────────────────┐
                     │  LAB PRIMARY                              │
                     │  bin:    /home/shan/amps-ha/amps-primary   │
                     │  config: amps-config-primary.xml             │
                     │  data:   app-env/data/amps-ha/amps-primary             │
                     │  tcp://172.21.12.69:9107                          │
                     │  ws://172.21.12.69:9108                             │
                     │  admin:8185                                           │
                     └───────────────▲──────────────────────────────────┘
                                     │
                     ordered failover list (HAClient)
                                     │
      ┌──────────────────────────────────────────────────┐
      │        Spring Boot Service (HAClient)              │
      │  lab-primary:9107 → lab-secondary:9117 → ...       │
      └──────────────────────────────────────────────────┘
                                     │
                     ordered failover list (HAClient)
                                     │
                     ┌───────────────▼──────────────────────────────────┐
                     │  LAB SECONDARY                                       │
                     │  bin:    /home/shan/amps-ha/amps-secondary             │
                     │  config: amps-config-secondary.xml                        │
                     │  data:   app-env/data/amps-ha/amps-secondary                        │
                     │  tcp://172.21.12.69:9117                                       │
                     │  ws://172.21.12.69:9118                                          │
                     │  admin:8195                                                        │
                     └────────────────────────────────────────────────────────────────┘
```

No AMPS-to-AMPS replication is configured by default — LAB PRIMARY and LAB SECONDARY have independent SOW/journal state. See the note at the end of Chapter 6 if you need that.

### Port Allocation

| Purpose | Production (untouched) | LAB PRIMARY | LAB SECONDARY |
|---|---|---|---|
| TCP transport (`tcp-json`) | 9007 | 9107 | 9117 |
| WebSocket transport (`websocket-any`) | 9008 | 9108 | 9118 |
| Admin UI (HTTP) | 8085 | 8185 | 8195 |

---

## Chapter 2 – Directory Layout

Two fully independent AMPS installs, two fully independent data trees, both entirely separate from production.

```
/home/shan/amps-5.3.5/                       # PRODUCTION — untouched by this guide
/home/shan/AMPS/                             # PRODUCTION data — untouched by this guide

/home/shan/amps-ha/                          # NEW — everything lab-related lives here
├── amps-primary/                              # full copy of the AMPS install
│   ├── bin/
│   │   └── ampServer
│   └── amps-config-primary.xml                 # lives at the install root, same convention as production
├── amps-secondary/                            # full copy of the AMPS install
│   ├── bin/
│   │   └── ampServer
│   └── amps-config-secondary.xml
└── scripts/                                   # lab-only start/stop scripts (Chapter 9)
    ├── start-primary.sh
    ├── start-secondary.sh
    ├── stop-primary.sh
    ├── stop-secondary.sh
    └── restart-all.sh

/home/shan/app-env/data/
├── amps/                                      # PRODUCTION data root (per your existing aliases) — untouched
└── amps-ha/                                    # NEW — everything lab data lives here
    ├── amps-primary/                             # LAB PRIMARY data
    │   ├── journal/
    │   ├── logs/
    │   └── sow/
    └── amps-secondary/                           # LAB SECONDARY data
        ├── journal/
        ├── logs/
        └── sow/
```

Create what's new:

```bash
mkdir -p /home/shan/amps-ha
mkdir -p /home/shan/app-env/data/amps-ha/amps-primary/{journal,logs,sow}
mkdir -p /home/shan/app-env/data/amps-ha/amps-secondary/{journal,logs,sow}
```

---

## Chapter 3 – Creating LAB PRIMARY

LAB PRIMARY is a **complete, independent copy** of your AMPS install — not a reference to production, not a shared binary. Production is only ever *read from* (to source the copy and the original config as a template), never written to.

### Step 1: Copy the install

```bash
cp -r /home/shan/amps-5.3.5 /home/shan/amps-ha/amps-primary
```

This copies the binary, libraries, and the original `amps-config.xml` into the new location. From here on, everything happens inside `/home/shan/amps-ha/amps-primary` — the source at `/home/shan/amps-5.3.5` is never modified.

### Step 2: Rename the config and fix every path/port

```bash
mv /home/shan/amps-ha/amps-primary/amps-config.xml \
   /home/shan/amps-ha/amps-primary/amps-config-primary.xml

# Data paths: production pointed at /home/shan/AMPS/... — the lab uses its own tree.
sed -i 's#/home/shan/AMPS/#/home/shan/app-env/data/amps-ha/amps-primary/#g' \
    /home/shan/amps-ha/amps-primary/amps-config-primary.xml

# Ports: production's 9007/9008/8085 -> lab primary's 9107/9108/8185
sed -i 's#0\.0\.0\.0:9007#0.0.0.0:9107#; s#0\.0\.0\.0:9008#0.0.0.0:9108#; s#0\.0\.0\.0:8085#0.0.0.0:8185#' \
    /home/shan/amps-ha/amps-primary/amps-config-primary.xml
```

Verify:

```bash
grep -n "/home/shan/AMPS" /home/shan/amps-ha/amps-primary/amps-config-primary.xml
# expect: no output

grep -n "app-env/data/amps-ha/amps-primary" /home/shan/amps-ha/amps-primary/amps-config-primary.xml
# expect: 5 lines (journal directory + 4 SOW topic files)

grep -n "InetAddr" /home/shan/amps-ha/amps-primary/amps-config-primary.xml
# expect: 9107, 9108, 8185 — no 9007/9008/8085 remaining
```

The full annotated file is in Chapter 5.

### Verify Ports

```bash
sudo lsof -i :9107
sudo lsof -i :9108
sudo lsof -i :8185
# all three should return nothing before first start
```

### Verify Queues

```bash
grep -A2 "<Queue>" /home/shan/amps-ha/amps-primary/amps-config-primary.xml
ls /home/shan/app-env/data/amps-ha/amps-primary/sow/
# expect (once started): trades.sow orders.sow risk.sow test-trades.sow
```

### Startup

```bash
/home/shan/amps-ha/scripts/start-primary.sh
```

Expected log line in `/home/shan/app-env/data/amps-ha/amps-primary/logs/amps.log`:

```
INFO  [main] Instance 'amps-queue-concurrency-ha-primary' started, listening on tcp://0.0.0.0:9107
```

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://172.21.12.69:8185
# expect: 200
```

---

## Chapter 4 – Creating LAB SECONDARY

Same pattern — copied fresh from the pristine production source, not from LAB PRIMARY, so both lab nodes are independently traceable back to the same known-good baseline.

### Step 1: Copy the install

```bash
cp -r /home/shan/amps-5.3.5 /home/shan/amps-ha/amps-secondary
```

### Step 2: Rename the config and fix every path/port

```bash
mv /home/shan/amps-ha/amps-secondary/amps-config.xml \
   /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml

sed -i 's#/home/shan/AMPS/#/home/shan/app-env/data/amps-ha/amps-secondary/#g' \
    /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml

sed -i 's#0\.0\.0\.0:9007#0.0.0.0:9117#; s#0\.0\.0\.0:9008#0.0.0.0:9118#; s#0\.0\.0\.0:8085#0.0.0.0:8195#' \
    /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml
```

Verify:

```bash
grep -n "/home/shan/AMPS" /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml
# expect: no output

grep -n "InetAddr" /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml
# expect: 9117, 9118, 8195
```

Also update `<Name>` to `amps-queue-concurrency-ha-secondary` — full annotated file in Chapter 6.

### Startup

```bash
/home/shan/amps-ha/scripts/start-secondary.sh
curl -s -o /dev/null -w "%{http_code}\n" http://172.21.12.69:8195
# expect: 200
```

You now have two fully independent AMPS instances — separate binaries, separate config, separate data, separate ports — with production at `/home/shan/amps-5.3.5` / `/home/shan/AMPS` completely undisturbed throughout.

---

## Chapter 5 – amps-config-primary.xml (LAB)

```xml
<AMPSConfig>
    <!--
      AMPS Server Configuration — amps-queue-concurrency  [ LAB PRIMARY ]
      ════════════════════════════════════════════════════════════════════
      Install : /home/shan/amps-ha/amps-primary   (independent copy — NOT production)
      Started : /home/shan/amps-ha/amps-primary/bin/ampServer --config /home/shan/amps-ha/amps-primary/amps-config-primary.xml

      This is a full, independent copy of the production amps-config.xml,
      with data paths repointed from /home/shan/AMPS to this lab's own
      data tree, and ports moved off production's 9007/9008/8085 so both
      can run simultaneously without conflict.

      Server  : 172.21.12.69
      Transports
        TCP  9107  client connections  →  tcp://172.21.12.69:9107/amps/json
        WS   9108  websocket / browser →  ws://172.21.12.69:9108
        HTTP 8185  admin UI / REST     →  http://172.21.12.69:8185/amps/admin

      Queues declared in this file
        /queue/trades       primary  — all subscriber profiles  (TRADE payloads)
        /queue/orders       secondary — order events           (ORDER payloads)
        /queue/risk         risk metrics                       (RISK  payloads)
        /queue/test-trades  test profile only (short lease, small journal)

      LeaseTimeout (5 000 ms) matches amps.queue.lease-timeout-ms in application.yaml.
    -->

    <!-- CHANGED: "-ha-primary" suffix distinguishes this from both
         production (amps-queue-concurrency) and LAB SECONDARY -->
    <Name>amps-queue-concurrency-ha-primary</Name>

    <!-- ── Logging ─────────────────────────────────────────────────────────── -->
    <Logging>
        <Target>
            <Protocol>stdout</Protocol>
            <Level>warning</Level>
            <IncludeErrors>00-0015</IncludeErrors>
        </Target>
        <Target>
            <Protocol>file</Protocol>
            <!-- CHANGED: was /home/shan/AMPS/logs/amps.log in production -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-primary/logs/amps.log</FileName>
            <Level>info</Level>
            <RotationThreshold>10MB</RotationThreshold>
        </Target>
    </Logging>

    <!-- ── Admin UI ───────────────────────────────────────────────────────── -->
    <Admin>
        <!-- CHANGED: 8085 -> 8185, off production's port -->
        <InetAddr>0.0.0.0:8185</InetAddr>
        <SQLTransport>websocket-any</SQLTransport>
    </Admin>

    <!-- ── Transports ─────────────────────────────────────────────────────── -->
    <Transports>
        <Transport>
            <Name>tcp-json</Name>
            <Type>tcp</Type>
            <!-- CHANGED: 9007 -> 9107 -->
            <InetAddr>0.0.0.0:9107</InetAddr>
            <Protocol>amps</Protocol>
            <MessageType>json</MessageType>
        </Transport>

        <Transport>
            <Name>websocket-any</Name>
            <Protocol>websocket</Protocol>
            <Type>tcp</Type>
            <!-- CHANGED: 9008 -> 9108 -->
            <InetAddr>0.0.0.0:9108</InetAddr>
        </Transport>
    </Transports>

    <!-- ── SOW — backing state-of-the-world topics ────────────────────────── -->
    <SOW>

        <Topic>
            <Name>trades</Name>
            <MessageType>json</MessageType>
            <Key>/id</Key>
            <!-- CHANGED -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-primary/sow/trades.sow</FileName>
        </Topic>

        <Queue>
            <Name>/queue/trades</Name>
            <MessageType>json</MessageType>
            <Semantics>at-least-once</Semantics>
            <UnderlyingTopic>trades</UnderlyingTopic>
            <LeaseTimeout>5000</LeaseTimeout>
            <MaxBacklog>1000000</MaxBacklog>
        </Queue>

        <Topic>
            <Name>orders</Name>
            <MessageType>json</MessageType>
            <Key>/id</Key>
            <!-- CHANGED -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-primary/sow/orders.sow</FileName>
        </Topic>

        <Queue>
            <Name>/queue/orders</Name>
            <MessageType>json</MessageType>
            <Semantics>at-least-once</Semantics>
            <UnderlyingTopic>orders</UnderlyingTopic>
            <LeaseTimeout>5000</LeaseTimeout>
            <MaxBacklog>500000</MaxBacklog>
        </Queue>

        <Topic>
            <Name>risk</Name>
            <MessageType>json</MessageType>
            <Key>/id</Key>
            <!-- CHANGED -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-primary/sow/risk.sow</FileName>
        </Topic>

        <Queue>
            <Name>/queue/risk</Name>
            <MessageType>json</MessageType>
            <Semantics>at-least-once</Semantics>
            <UnderlyingTopic>risk</UnderlyingTopic>
            <LeaseTimeout>5000</LeaseTimeout>
            <MaxBacklog>250000</MaxBacklog>
        </Queue>

        <Topic>
            <Name>test-trades</Name>
            <MessageType>json</MessageType>
            <Key>/id</Key>
            <!-- CHANGED -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-primary/sow/test-trades.sow</FileName>
        </Topic>

        <Queue>
            <Name>/queue/test-trades</Name>
            <MessageType>json</MessageType>
            <Semantics>at-least-once</Semantics>
            <UnderlyingTopic>test-trades</UnderlyingTopic>
            <LeaseTimeout>2000</LeaseTimeout>
            <MaxBacklog>10000</MaxBacklog>
        </Queue>

    </SOW>

    <!-- ── TransactionLog — journal for durability ────────────────────────── -->
    <TransactionLog>
        <!-- CHANGED: was /home/shan/AMPS/journal in production -->
        <JournalDirectory>/home/shan/app-env/data/amps-ha/amps-primary/journal</JournalDirectory>
        <JournalSize>1GB</JournalSize>

        <Topic><Name>trades</Name><MessageType>json</MessageType></Topic>
        <Topic><Name>/queue/trades</Name><MessageType>json</MessageType></Topic>

        <Topic><Name>orders</Name><MessageType>json</MessageType></Topic>
        <Topic><Name>/queue/orders</Name><MessageType>json</MessageType></Topic>

        <Topic><Name>risk</Name><MessageType>json</MessageType></Topic>
        <Topic><Name>/queue/risk</Name><MessageType>json</MessageType></Topic>

        <Topic><Name>test-trades</Name><MessageType>json</MessageType></Topic>
        <Topic><Name>/queue/test-trades</Name><MessageType>json</MessageType></Topic>
    </TransactionLog>

    <!-- ── Actions ────────────────────────────────────────────────────────── -->
    <Actions>

        <Action>
            <On><Module>amps-action-on-startup</Module></On>
            <Do>
                <Module>amps-action-do-remove-journal</Module>
                <Options><Age>7d</Age></Options>
            </Do>
        </Action>

        <!-- NOTE: <Path>./</Path> is relative to ampServer's working
             directory at launch. Chapter 9's start-primary.sh `cd`s into
             this instance's own data directory before exec'ing ampServer,
             so this check inspects the lab's disk, never production's. -->
        <Action>
            <On>
                <Module>amps-action-on-schedule</Module>
                <Options>
                    <Name>Periodic file system disk usage check</Name>
                    <Every>10s</Every>
                </Options>
            </On>
            <If>
                <Module>amps-action-if-file-system-usage</Module>
                <Options>
                    <Path>./</Path>
                    <GreaterThan>95%</GreaterThan>
                </Options>
            </If>
            <Do>
                <Module>amps-action-do-echo-message</Module>
                <Options>
                    <Message>CRITICAL: Shutting down AMPS (LAB PRIMARY) due to lack of disk space</Message>
                </Options>
            </Do>
            <Do>
                <Module>amps-action-do-shutdown</Module>
            </Do>
        </Action>

    </Actions>

</AMPSConfig>
```

---

## Chapter 6 – amps-config-secondary.xml (LAB)

```xml
<AMPSConfig>
    <!--
      AMPS Server Configuration — amps-queue-concurrency  [ LAB SECONDARY ]
      Install : /home/shan/amps-ha/amps-secondary   (independent copy — NOT production, NOT LAB PRIMARY)
      Started : /home/shan/amps-ha/amps-secondary/bin/ampServer --config /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml
    -->

    <!-- CHANGED -->
    <Name>amps-queue-concurrency-ha-secondary</Name>

    <Logging>
        <Target>
            <Protocol>stdout</Protocol>
            <Level>warning</Level>
            <IncludeErrors>00-0015</IncludeErrors>
        </Target>
        <Target>
            <Protocol>file</Protocol>
            <!-- CHANGED -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-secondary/logs/amps.log</FileName>
            <Level>info</Level>
            <RotationThreshold>10MB</RotationThreshold>
        </Target>
    </Logging>

    <Admin>
        <!-- CHANGED: LAB PRIMARY's 8185 + 10 -->
        <InetAddr>0.0.0.0:8195</InetAddr>
        <SQLTransport>websocket-any</SQLTransport>
    </Admin>

    <Transports>
        <Transport>
            <Name>tcp-json</Name>
            <Type>tcp</Type>
            <!-- CHANGED: LAB PRIMARY's 9107 + 10 -->
            <InetAddr>0.0.0.0:9117</InetAddr>
            <Protocol>amps</Protocol>
            <MessageType>json</MessageType>
        </Transport>

        <Transport>
            <Name>websocket-any</Name>
            <Protocol>websocket</Protocol>
            <Type>tcp</Type>
            <!-- CHANGED: LAB PRIMARY's 9108 + 10 -->
            <InetAddr>0.0.0.0:9118</InetAddr>
        </Transport>
    </Transports>

    <SOW>

        <Topic>
            <Name>trades</Name>
            <MessageType>json</MessageType>
            <Key>/id</Key>
            <!-- CHANGED -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-secondary/sow/trades.sow</FileName>
        </Topic>

        <Queue>
            <Name>/queue/trades</Name>
            <MessageType>json</MessageType>
            <Semantics>at-least-once</Semantics>
            <UnderlyingTopic>trades</UnderlyingTopic>
            <LeaseTimeout>5000</LeaseTimeout>
            <MaxBacklog>1000000</MaxBacklog>
        </Queue>

        <Topic>
            <Name>orders</Name>
            <MessageType>json</MessageType>
            <Key>/id</Key>
            <!-- CHANGED -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-secondary/sow/orders.sow</FileName>
        </Topic>

        <Queue>
            <Name>/queue/orders</Name>
            <MessageType>json</MessageType>
            <Semantics>at-least-once</Semantics>
            <UnderlyingTopic>orders</UnderlyingTopic>
            <LeaseTimeout>5000</LeaseTimeout>
            <MaxBacklog>500000</MaxBacklog>
        </Queue>

        <Topic>
            <Name>risk</Name>
            <MessageType>json</MessageType>
            <Key>/id</Key>
            <!-- CHANGED -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-secondary/sow/risk.sow</FileName>
        </Topic>

        <Queue>
            <Name>/queue/risk</Name>
            <MessageType>json</MessageType>
            <Semantics>at-least-once</Semantics>
            <UnderlyingTopic>risk</UnderlyingTopic>
            <LeaseTimeout>5000</LeaseTimeout>
            <MaxBacklog>250000</MaxBacklog>
        </Queue>

        <Topic>
            <Name>test-trades</Name>
            <MessageType>json</MessageType>
            <Key>/id</Key>
            <!-- CHANGED -->
            <FileName>/home/shan/app-env/data/amps-ha/amps-secondary/sow/test-trades.sow</FileName>
        </Topic>

        <Queue>
            <Name>/queue/test-trades</Name>
            <MessageType>json</MessageType>
            <Semantics>at-least-once</Semantics>
            <UnderlyingTopic>test-trades</UnderlyingTopic>
            <LeaseTimeout>2000</LeaseTimeout>
            <MaxBacklog>10000</MaxBacklog>
        </Queue>

    </SOW>

    <TransactionLog>
        <!-- CHANGED -->
        <JournalDirectory>/home/shan/app-env/data/amps-ha/amps-secondary/journal</JournalDirectory>
        <JournalSize>1GB</JournalSize>

        <Topic><Name>trades</Name><MessageType>json</MessageType></Topic>
        <Topic><Name>/queue/trades</Name><MessageType>json</MessageType></Topic>

        <Topic><Name>orders</Name><MessageType>json</MessageType></Topic>
        <Topic><Name>/queue/orders</Name><MessageType>json</MessageType></Topic>

        <Topic><Name>risk</Name><MessageType>json</MessageType></Topic>
        <Topic><Name>/queue/risk</Name><MessageType>json</MessageType></Topic>

        <Topic><Name>test-trades</Name><MessageType>json</MessageType></Topic>
        <Topic><Name>/queue/test-trades</Name><MessageType>json</MessageType></Topic>
    </TransactionLog>

    <Actions>

        <Action>
            <On><Module>amps-action-on-startup</Module></On>
            <Do>
                <Module>amps-action-do-remove-journal</Module>
                <Options><Age>7d</Age></Options>
            </Do>
        </Action>

        <Action>
            <On>
                <Module>amps-action-on-schedule</Module>
                <Options>
                    <Name>Periodic file system disk usage check</Name>
                    <Every>10s</Every>
                </Options>
            </On>
            <If>
                <Module>amps-action-if-file-system-usage</Module>
                <Options>
                    <Path>./</Path>
                    <GreaterThan>95%</GreaterThan>
                </Options>
            </If>
            <Do>
                <Module>amps-action-do-echo-message</Module>
                <Options>
                    <Message>CRITICAL: Shutting down AMPS (LAB SECONDARY) due to lack of disk space</Message>
                </Options>
            </Do>
            <Do>
                <Module>amps-action-do-shutdown</Module>
            </Do>
        </Action>

    </Actions>

</AMPSConfig>
```

**Replication (optional, not enabled):** as before — LAB PRIMARY does not push SOW state to LAB SECONDARY by default.

---

## Chapter 7 – Exact XML / Filesystem Differences

| Element | LAB PRIMARY | LAB SECONDARY | Why |
|---|---|---|---|
| Install directory | `/home/shan/amps-ha/amps-primary` | `/home/shan/amps-ha/amps-secondary` | Fully independent binaries, not shared |
| `<Name>` | `amps-queue-concurrency-ha-primary` | `amps-queue-concurrency-ha-secondary` | Disambiguate in logs/admin/alerts |
| TCP transport (`tcp-json`) | `9107` | `9117` | Avoid port conflict with each other and production |
| WS transport (`websocket-any`) | `9108` | `9118` | Same |
| Admin `InetAddr` | `8185` | `8195` | Same |
| Log file | `.../data/amps-ha/amps-primary/logs/amps.log` | `.../data/amps-ha/amps-secondary/logs/amps.log` | No log interleaving |
| `trades`/`orders`/`risk`/`test-trades` SOW files | `.../data/amps-ha/amps-primary/sow/*.sow` | `.../data/amps-ha/amps-secondary/sow/*.sow` | Independent state |
| `JournalDirectory` | `.../data/amps-ha/amps-primary/journal` | `.../data/amps-ha/amps-secondary/journal` | Independent transaction log |
| Disk-usage alert message | `"...LAB PRIMARY..."` | `"...LAB SECONDARY..."` | Alert text names the node |
| Everything else (`Queue` semantics, `LeaseTimeout`, `MaxBacklog`, `<Key>/id</Key>`) | identical | identical | Behavior must match for transparent failover |

And versus production, unconditionally for **both** lab nodes: install directory, data root, and all three ports differ — nothing in this lab shares a filesystem path or a port with `/home/shan/amps-5.3.5` / `/home/shan/AMPS` / `9007`/`9008`/`8085`.

---

## Chapter 8 – WSL Commands

### Build the Lab

```bash
mkdir -p /home/shan/amps-ha
mkdir -p /home/shan/app-env/data/amps-ha/amps-primary/{journal,logs,sow}
mkdir -p /home/shan/app-env/data/amps-ha/amps-secondary/{journal,logs,sow}

cp -r /home/shan/amps-5.3.5 /home/shan/amps-ha/amps-primary
cp -r /home/shan/amps-5.3.5 /home/shan/amps-ha/amps-secondary

mv /home/shan/amps-ha/amps-primary/amps-config.xml \
   /home/shan/amps-ha/amps-primary/amps-config-primary.xml
mv /home/shan/amps-ha/amps-secondary/amps-config.xml \
   /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml

sed -i 's#/home/shan/AMPS/#/home/shan/app-env/data/amps-ha/amps-primary/#g' \
    /home/shan/amps-ha/amps-primary/amps-config-primary.xml
sed -i 's#/home/shan/AMPS/#/home/shan/app-env/data/amps-ha/amps-secondary/#g' \
    /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml

sed -i 's#0\.0\.0\.0:9007#0.0.0.0:9107#; s#0\.0\.0\.0:9008#0.0.0.0:9108#; s#0\.0\.0\.0:8085#0.0.0.0:8185#' \
    /home/shan/amps-ha/amps-primary/amps-config-primary.xml
sed -i 's#0\.0\.0\.0:9007#0.0.0.0:9117#; s#0\.0\.0\.0:9008#0.0.0.0:9118#; s#0\.0\.0\.0:8085#0.0.0.0:8195#' \
    /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml

mkdir -p /home/shan/amps-ha/scripts
```

### Verify Ports

```bash
sudo lsof -i :9107 -i :9108 -i :8185   # LAB PRIMARY
sudo lsof -i :9117 -i :9118 -i :8195   # LAB SECONDARY
sudo lsof -i :9007 -i :9008 -i :8085   # confirm production, if running, is undisturbed
```

### Start / Stop

```bash
/home/shan/amps-ha/scripts/start-primary.sh
/home/shan/amps-ha/scripts/start-secondary.sh

/home/shan/amps-ha/scripts/stop-primary.sh
/home/shan/amps-ha/scripts/stop-secondary.sh
```

### Check Processes

```bash
pgrep -fal amps-config-primary.xml
pgrep -fal amps-config-secondary.xml
pgrep -fal amps-config.xml            # production, for comparison — should be unaffected
```

### Tail Logs

```bash
tail -f /home/shan/app-env/data/amps-ha/amps-primary/logs/amps.log
tail -f /home/shan/app-env/data/amps-ha/amps-secondary/logs/amps.log
```

---

## Chapter 9 – Startup Scripts, and New Lab-Only Aliases

### Startup scripts

Each script invokes its own instance's binary directly — no sharing.

**`start-primary.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

AMPS_BIN="/home/shan/amps-ha/amps-primary/bin/ampServer"
CONFIG="/home/shan/amps-ha/amps-primary/amps-config-primary.xml"
DATA_DIR="/home/shan/app-env/data/amps-ha/amps-primary"
PIDFILE="${DATA_DIR}/primary.pid"

if [ -f "${PIDFILE}" ] && kill -0 "$(cat "${PIDFILE}")" 2>/dev/null; then
    echo "LAB PRIMARY already running (pid $(cat "${PIDFILE}"))"
    exit 0
fi

cd "${DATA_DIR}"
nohup "${AMPS_BIN}" --config "${CONFIG}" \
    > "${DATA_DIR}/logs/startup.out" 2>&1 &

echo $! > "${PIDFILE}"
echo "LAB PRIMARY starting (pid $!) — config: ${CONFIG}"
```

**`start-secondary.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

AMPS_BIN="/home/shan/amps-ha/amps-secondary/bin/ampServer"
CONFIG="/home/shan/amps-ha/amps-secondary/amps-config-secondary.xml"
DATA_DIR="/home/shan/app-env/data/amps-ha/amps-secondary"
PIDFILE="${DATA_DIR}/secondary.pid"

if [ -f "${PIDFILE}" ] && kill -0 "$(cat "${PIDFILE}")" 2>/dev/null; then
    echo "LAB SECONDARY already running (pid $(cat "${PIDFILE}"))"
    exit 0
fi

cd "${DATA_DIR}"
nohup "${AMPS_BIN}" --config "${CONFIG}" \
    > "${DATA_DIR}/logs/startup.out" 2>&1 &

echo $! > "${PIDFILE}"
echo "LAB SECONDARY starting (pid $!) — config: ${CONFIG}"
```

**`stop-primary.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

PIDFILE="/home/shan/app-env/data/amps-ha/amps-primary/primary.pid"

if [ ! -f "${PIDFILE}" ]; then
    echo "No PID file — LAB PRIMARY not tracked as running"
    exit 0
fi

PID="$(cat "${PIDFILE}")"
if kill -0 "${PID}" 2>/dev/null; then
    kill "${PID}"
    echo "LAB PRIMARY (pid ${PID}) stop signal sent"
else
    echo "LAB PRIMARY pid ${PID} not running"
fi
rm -f "${PIDFILE}"
```

**`stop-secondary.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

PIDFILE="/home/shan/app-env/data/amps-ha/amps-secondary/secondary.pid"

if [ ! -f "${PIDFILE}" ]; then
    echo "No PID file — LAB SECONDARY not tracked as running"
    exit 0
fi

PID="$(cat "${PIDFILE}")"
if kill -0 "${PID}" 2>/dev/null; then
    kill "${PID}"
    echo "LAB SECONDARY (pid ${PID}) stop signal sent"
else
    echo "LAB SECONDARY pid ${PID} not running"
fi
rm -f "${PIDFILE}"
```

**`restart-all.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"${DIR}/stop-secondary.sh"
"${DIR}/stop-primary.sh"
sleep 2
"${DIR}/start-primary.sh"
"${DIR}/start-secondary.sh"

echo "Both LAB instances restarted."
```

```bash
chmod +x /home/shan/amps-ha/scripts/*.sh
```

### New lab-only aliases

Your existing production aliases (`cdamps`, `cdampsdata`, `cdampslog`, `startamps`, `tailamps`) are **not modified**. These are additions, in the same style:

```bash
# --- AMPS HA LAB (separate from production) ----------------------------
alias cdampsha='cd /home/shan/amps-ha'
alias cdampshaprimary='cd /home/shan/amps-ha/amps-primary'
alias cdampshasecondary='cd /home/shan/amps-ha/amps-secondary'

alias cdampshadata='cd /home/shan/app-env/data/amps-ha'
alias cdampshadataprimary='cd /home/shan/app-env/data/amps-ha/amps-primary'
alias cdampshadatasecondary='cd /home/shan/app-env/data/amps-ha/amps-secondary'
alias cdampshalogprimary='cd /home/shan/app-env/data/amps-ha/amps-primary/logs'
alias cdampshalogsecondary='cd /home/shan/app-env/data/amps-ha/amps-secondary/logs'

alias startampshaprimary='/home/shan/amps-ha/scripts/start-primary.sh'
alias startampshasecondary='/home/shan/amps-ha/scripts/start-secondary.sh'
alias stopampshaprimary='/home/shan/amps-ha/scripts/stop-primary.sh'
alias stopampshasecondary='/home/shan/amps-ha/scripts/stop-secondary.sh'
alias restartampsha='/home/shan/amps-ha/scripts/restart-all.sh'

alias tailampshaprimary='tail -f /home/shan/app-env/data/amps-ha/amps-primary/logs/amps.log'
alias tailampshasecondary='tail -f /home/shan/app-env/data/amps-ha/amps-secondary/logs/amps.log'
# -------------------------------------------------------------------------
```

`sbp` to pick these up.

---

## Chapter 10 – Spring Boot HAClient

> **Generating test traffic:** the `amps-publisher-simple` module (see repo root `CLAUDE.md` §
> "Publisher Module") publishes synthetic order-event messages at a configurable rate/burst onto
> whichever queues you point it at — point its `amps.publisher.uris` at this lab's `9107`/`9117` and
> `amps.publisher.targets[].queue` at one of `/queue/trades`, `/queue/orders`, `/queue/risk`,
> `/queue/test-trades`, and it becomes the "Publisher" referenced throughout Chapter 12's validation
> checklist below — no separate manual `amps_publish` invocation needed for repeated/soak testing.

### HAClient Configuration

```java
@Configuration
public class AmpsHaClientConfig {

    @Value("${amps.client-name:trades-service}")
    private String clientName;

    @Value("${amps.primary-uri:tcp://172.21.12.69:9107/amps/json}")
    private String primaryUri;

    @Value("${amps.secondary-uri:tcp://172.21.12.69:9117/amps/json}")
    private String secondaryUri;

    @Bean(destroyMethod = "close")
    public HAClient ampsHaClient() throws AMPSException {
        HAClient client = new HAClient(clientName);

        DefaultServerChooser chooser = new DefaultServerChooser();
        chooser.add(primaryUri);
        chooser.add(secondaryUri);
        client.setServerChooser(chooser);

        client.setReconnectDelayStrategy(
                new ExponentialDelayStrategy(200, 5_000, -1)
        );

        client.setResubscribeAfterReconnect(true);
        client.setBookmarkStore(new MemoryBookmarkStore());

        client.connectAndLogon();
        return client;
    }
}
```

```properties
amps.client-name=trades-service
amps.primary-uri=tcp://172.21.12.69:9107/amps/json
amps.secondary-uri=tcp://172.21.12.69:9117/amps/json
amps.queue.lease-timeout-ms=5000
```

For this lab, run this Spring Boot service against a dedicated `application-ha-lab.yaml` profile with the ports above, so it can never accidentally point at production's `9007` while you're testing failover.

`amps.queue.lease-timeout-ms=5000` must stay equal to `<LeaseTimeout>5000</LeaseTimeout>` in both lab config files — same coupling as before, now scoped entirely to lab-only files.

---

## Chapter 11 – JUnit Integration Tests

```java
class HaFailoverIntegrationTest {

    private static final Path SCRIPTS = Path.of("/home/shan/amps-ha/scripts");

    private HAClient client;

    @BeforeAll
    static void startBothServers() throws Exception {
        run(SCRIPTS, "start-primary.sh");
        run(SCRIPTS, "start-secondary.sh");
        waitForPort("172.21.12.69", 9107, Duration.ofSeconds(10));
        waitForPort("172.21.12.69", 9117, Duration.ofSeconds(10));
    }

    @AfterAll
    static void stopBothServers() throws Exception {
        run(SCRIPTS, "stop-primary.sh");
        run(SCRIPTS, "stop-secondary.sh");
    }

    @BeforeEach
    void connectClient() throws Exception {
        client = new HAClient("ha-integration-test");
        DefaultServerChooser chooser = new DefaultServerChooser();
        chooser.add("tcp://172.21.12.69:9107/amps/json");
        chooser.add("tcp://172.21.12.69:9117/amps/json");
        client.setServerChooser(chooser);
        client.setResubscribeAfterReconnect(true);
        client.connectAndLogon();
    }

    @AfterEach
    void disconnectClient() {
        client.close();
    }

    @Test
    void tradesQueueSurvivesPrimaryFailover() throws Exception {
        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        client.subscribe(received::add, "/queue/trades");

        client.publish("/queue/trades", "{\"id\":1,\"symbol\":\"AAPL\",\"qty\":100}");
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();

        run(SCRIPTS, "stop-primary.sh");
        waitForReconnect(client, Duration.ofSeconds(15));

        client.publish("/queue/trades", "{\"id\":2,\"symbol\":\"AAPL\",\"qty\":50}");
        assertThat(received.poll(10, TimeUnit.SECONDS)).isNotNull();

        run(SCRIPTS, "start-primary.sh");
        waitForPort("172.21.12.69", 9107, Duration.ofSeconds(10));
    }

    private static void run(Path dir, String script) throws Exception {
        new ProcessBuilder(dir.resolve(script).toString())
                .inheritIO().start().waitFor(10, TimeUnit.SECONDS);
    }

    private static void waitForPort(String host, int port, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (Socket s = new Socket(host, port)) { return; }
            catch (IOException e) { Thread.sleep(200); }
        }
        throw new AssertionError("Port " + port + " not reachable within " + timeout);
    }

    private static void waitForReconnect(HAClient client, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (client.isConnected()) return;
            Thread.sleep(250);
        }
        throw new AssertionError("HAClient did not reconnect within " + timeout);
    }
}
```

---

## Chapter 12 – Validation Checklist

- [ ] `/home/shan/amps-5.3.5` and `/home/shan/AMPS` confirmed unmodified after building the lab (`diff -r` against a pre-lab backup if you want to be certain)
- [ ] `amps-ha/amps-primary` and `amps-ha/amps-secondary` are full, independent copies — no symlinks back to production
- [ ] Both lab configs `grep`-clean of `/home/shan/AMPS` and of ports `9007`/`9008`/`8085`
- [ ] LAB PRIMARY starts, listening on `9107`; admin UI `8185` returns 200
- [ ] LAB SECONDARY starts, listening on `9117`; admin UI `8195` returns 200
- [ ] Production (if running) unaffected — still reachable on `9007`/`8085` throughout
- [ ] All four queues visible in the admin UI on both lab instances
- [ ] Publisher (`amps-publisher-simple`, or manual `amps_publish`) connects to LAB PRIMARY; subscriber on `/queue/trades` receives a message
- [ ] LAB PRIMARY stopped; HAClient reconnects to `9117`, resubscribes on `/queue/trades`
- [ ] Post-failover publish is received
- [ ] LAB PRIMARY restarted; `9107` reachable again; chooser behavior recorded
- [ ] Repeated for `/queue/orders` and `/queue/risk`
- [ ] Full JUnit suite (Chapter 11) passes
- [ ] `.bashrc` updated with the new lab-only alias block and re-sourced via `sbp`

---

## Chapter 13 – Expected Log Messages

**ampServer (LAB PRIMARY) — normal startup:**
```
INFO  [main] Instance 'amps-queue-concurrency-ha-primary' started, listening on tcp://0.0.0.0:9107
INFO  [main] WebSocket transport 'websocket-any' listening on 0.0.0.0:9108
INFO  [main] Admin UI listening on 0.0.0.0:8185
```

**ampServer (LAB PRIMARY) — clean shutdown:**
```
INFO  [main] Shutdown signal received, closing client connections
INFO  [main] Instance 'amps-queue-concurrency-ha-primary' stopped
```

**Spring Boot client (HAClient) — initial connection:**
```
INFO  Connected to tcp://172.21.12.69:9107
INFO  Logon successful, client name=trades-service
INFO  Subscription established: queue=/queue/trades
```

**HAClient — LAB PRIMARY failure detected:**
```
WARN  Connection lost: tcp://172.21.12.69:9107
WARN  Attempting reconnect (attempt 1, delay=200ms)
WARN  Attempting reconnect (attempt 2, delay=400ms)
```

**HAClient — failover to LAB SECONDARY:**
```
INFO  Connected to tcp://172.21.12.69:9117
INFO  Logon successful, client name=trades-service
INFO  Resubscribing existing subscriptions
INFO  Subscription successful: queue=/queue/trades (resumed from bookmark=42)
```

**HAClient — LAB PRIMARY recovers, chooser wraps back:**
```
WARN  Connection lost: tcp://172.21.12.69:9117
INFO  Connected to tcp://172.21.12.69:9107
INFO  Resubscribing existing subscriptions
INFO  Subscription successful: queue=/queue/trades (resumed from bookmark=57)
```

If reconnect never succeeds, check in order: (1) `pgrep -fal amps-config-primary.xml` / `-secondary.xml`, (2) `nc -zv 172.21.12.69 9107` / `9117`, (3) the target's own `amps.log` for the `CRITICAL: Shutting down AMPS` disk-guard line, (4) that your Spring config is actually pointed at `9107`/`9117` and not production's `9007`.

---

*End of HA_AMPS_LAB_SETUP_GUIDE.md*
