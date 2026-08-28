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

No AMPS-to-AMPS replication is configured by default — LAB PRIMARY and LAB SECONDARY have independent SOW/journal state. Chapter 14 walks through enabling one-directional (primary → secondary) SOW replication on a dedicated port, for the case where you want a failed-over client to see pre-existing SOW state on LAB SECONDARY rather than an empty one.

### Port Allocation

| Purpose | Production (untouched) | LAB PRIMARY | LAB SECONDARY |
|---|---|---|---|
| TCP transport (`tcp-json`) | 9007 | 9107 | 9117 |
| WebSocket transport (`websocket-any`) | 9008 | 9108 | 9118 |
| Admin UI (HTTP) | 8085 | 8185 | 8195 |
| Replication transport (`replication`) | n/a — not used in production | 9109 | 9119 |

The replication port follows the same "+10 for secondary" convention as the other three, and is only used
node-to-node — no client ever connects to it. It sits idle (configured but unused) until Chapter 14 enables
replication; both lab nodes bind it from first startup so no restart is needed later just to open the port.

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

        <!-- ADDED (Chapter 14): node-to-node replication only, no client ever
             connects here. Bound from first startup even before Replication
             is enabled below, so opening this port never requires a restart. -->
        <Transport>
            <Name>replication</Name>
            <Type>tcp</Type>
            <InetAddr>0.0.0.0:9109</InetAddr>
            <Protocol>amps</Protocol>
            <MessageType>fix</MessageType>
        </Transport>
    </Transports>

    <!-- ── Replication (Chapter 14) ───────────────────────────────────────────
         ADDED. LAB PRIMARY is the only side with a <Replication> block: it
         actively connects OUT to LAB SECONDARY's replication transport and
         pushes SOW updates one-directionally. LAB SECONDARY only needs the
         matching <Transport> above to receive — it stays passive and defines
         no <Replication> block of its own. See Chapter 14 for the full
         active-passive rationale and how to switch to active-active. -->
    <Replication>
        <Name>amps-queue-concurrency-ha-primary-repl</Name>
        <Destinations>
            <Destination>
                <Name>amps-queue-concurrency-ha-secondary</Name>
                <URI>tcp://172.21.12.69:9119/amps/fix</URI>
            </Destination>
        </Destinations>
        <!-- Explicit include-list, matching this file's existing style of
             enumerating all four queues rather than relying on an implicit
             "replicate everything" default. test-trades is deliberately
             left out — see Chapter 14's caution about SOW replication
             changing what a resubscribe test actually proves. -->
        <Topics>
            <Topic><Name>trades</Name></Topic>
            <Topic><Name>orders</Name></Topic>
            <Topic><Name>risk</Name></Topic>
        </Topics>
    </Replication>

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

        <!-- ADDED (Chapter 14): receive-only side of replication. LAB PRIMARY
             connects out to this port; this node never defines a
             <Replication> block itself in the active-passive setup below. -->
        <Transport>
            <Name>replication</Name>
            <Type>tcp</Type>
            <!-- CHANGED: LAB PRIMARY's 9109 + 10 -->
            <InetAddr>0.0.0.0:9119</InetAddr>
            <Protocol>amps</Protocol>
            <MessageType>fix</MessageType>
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

**Replication:** LAB SECONDARY's config above already includes the `replication` transport (`9119`) that lets
it *receive* SOW updates. Whether it actually receives anything depends entirely on LAB PRIMARY's
`<Replication>` block (Chapter 5) being present and pointed at this port — see Chapter 14 to enable it, or
to confirm it's off.

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
| `replication` transport `InetAddr` | `9109` | `9119` | Node-to-node only, never a client target |
| `<Replication>` block | present, `<Destinations>` → secondary's `9119` | absent — receive-only | Chapter 14: active-passive, primary pushes out |
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
sudo lsof -i :9107 -i :9108 -i :8185 -i :9109   # LAB PRIMARY
sudo lsof -i :9117 -i :9118 -i :8195 -i :9119   # LAB SECONDARY
sudo lsof -i :9007 -i :9008 -i :8085            # confirm production, if running, is undisturbed
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
- [ ] (Chapter 14, if replication enabled) LAB PRIMARY log shows the replication destination connected to `9119`
- [ ] (Chapter 14) a record published to LAB PRIMARY's `trades`/`orders`/`risk` topics appears in LAB SECONDARY's SOW within a few seconds
- [ ] (Chapter 14) `test-trades` deliberately does *not* replicate — confirms the include-list is scoped as intended
- [ ] (Chapter 14) with replication OFF (default), confirm the two SOW files diverge as expected — the reconnect/resubscribe tests in this project depend on that being the normal state

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

**ampServer (LAB PRIMARY) — replication destination connects (Chapter 14, once enabled):**
```
INFO  [main] Replication 'amps-queue-concurrency-ha-primary-repl' connecting to destination 'amps-queue-concurrency-ha-secondary' (tcp://172.21.12.69:9119/amps/fix)
INFO  [main] Replication destination 'amps-queue-concurrency-ha-secondary' connected
```

**ampServer (LAB SECONDARY) — accepts inbound replication connection:**
```
INFO  [main] Replication transport 'replication' accepted connection from amps-queue-concurrency-ha-primary
```

If replication never connects, check in order: (1) the `replication` transport is present and listening on both nodes (`sudo lsof -i :9109` / `:9119`), (2) LAB PRIMARY's `<Replication><Destinations><Destination><URI>` matches LAB SECONDARY's actual replication port and message type exactly, (3) LAB PRIMARY's own `amps.log` for a rejected-connection or auth error rather than a silent timeout, (4) that you didn't accidentally add a `<Replication>` block to LAB SECONDARY too — see Chapter 14's note on why that would fight the active-passive direction, not add redundancy.

---

## Chapter 14 – Enabling Replication Between LAB PRIMARY and LAB SECONDARY

### Why you'd turn this on — and why it's off by default

Everything through Chapter 13 tests **client-side** recovery: `HAClient` detects a dropped connection,
reconnects, resubscribes, and this project's reconnect framework resumes reading — all without needing
LAB PRIMARY and LAB SECONDARY to know anything about each other's data. That's deliberate: the two nodes
start with independent, empty SOW/journal state precisely so that a resubscribe test after failover proves
the *client's* recovery logic worked, not that the server handed it pre-populated state.

Replication is a different, optional concern: it keeps LAB SECONDARY's **SOW** (not the queue's delivery/
lease state) in sync with LAB PRIMARY's, so that if you fail over *after* LAB PRIMARY has already
accumulated SOW records, LAB SECONDARY isn't starting from an empty topic. Turn it on when you specifically
want to validate that scenario. Leave it off — the default throughout this guide — when you just want to
exercise `HAClient` reconnect/resubscribe/reader-restart, which is this project's actual `CLAUDE.md` success
criteria and doesn't depend on replication at all.

This chapter's setup is deliberately the *simple* one-directional case. If you're validating the
distributed-queue ownership/ACK-replication scenario in
`docs/AMPS_Primary_Secondary_HA_Queue_Behavior_Summary.md` — 5 subscribers on each node, ownership
transfer, at-least-once redelivery across failover — skip ahead to Chapter 15, which reconfigures this
into a bidirectional setup with the queue's ACK/ownership state (not just SOW rows) replicated.

> **Schema caveat:** the `<Replication>` / `<Destinations>` / `<Topics>` element names and the `fix`
> replication message type below reflect 60East's general AMPS replication model. Tag names have shifted
> across AMPS server releases, so before starting either node with these blocks in place, cross-check them
> against the Replication Guide bundled with your AMPS 5.3.3.3 install (typically alongside the other PDFs
> in the install's `doc/` directory) or `amps-config.xsd` if one ships with it. A bad element name is a safe
> failure — `ampServer` refuses to start rather than half-applying it — but confirm the exact spelling before
> you rely on this in anything beyond the lab.

### Active-passive, not active-active

This chapter wires up **one-directional** replication: LAB PRIMARY pushes to LAB SECONDARY, never the
reverse. That matches how the lab is actually used — `DefaultServerChooser` always tries LAB PRIMARY first
(Chapter 10), so LAB PRIMARY is the node that normally takes writes. One-directional replication means only
LAB PRIMARY needs a `<Replication>` block; LAB SECONDARY just needs the matching `replication` transport
open to *receive* on.

Active-active (both nodes replicate to each other) is possible — add a mirror-image `<Replication>` block
to LAB SECONDARY's config, pointed at LAB PRIMARY's `9109` — but it means both nodes can independently
accept writes that then need conflict resolution, which is unnecessary complexity for a lab whose job is
proving out client failover, not multi-master write conflict handling. Don't add it to LAB SECONDARY unless
you have a specific reason to test that; a `<Replication>` block on both sides at once is what the
Chapter 13 troubleshooting note above is warning you away from.

### What's already in place

Chapters 5 and 6 above already contain everything this chapter needs:

* Both nodes bind a `replication` transport (`9109` primary, `9119` secondary) — added at the same time
  `amps-config-primary.xml` / `amps-config-secondary.xml` were built, so no restart is needed just to open
  the port.
* LAB PRIMARY's config carries the `<Replication>` block with one `<Destination>` pointed at LAB
  SECONDARY's `9119`.
* LAB SECONDARY defines no `<Replication>` block — it's receive-only.
* The `<Topics>` include-list under LAB PRIMARY's `<Replication>` block names `trades`, `orders`, and
  `risk` explicitly, and **deliberately omits `test-trades`** — see the caution below.

If you built your configs before this chapter existed, apply the same three edits — the `replication`
transport on both files, and the `<Replication>` block on the primary only — shown in Chapters 5 and 6.

### Enable it

```bash
/home/shan/amps-ha/scripts/restart-all.sh
```

Both nodes need to come up together since LAB PRIMARY dials out to LAB SECONDARY's replication port on
startup; `restart-all.sh` already stops secondary-then-primary and starts primary-then-secondary
(Chapter 9), which is the right order here too — bringing LAB PRIMARY up before LAB SECONDARY just means
its first few replication connection attempts retry until LAB SECONDARY's port is listening, which
`ampServer` handles on its own.

### Verify

```bash
grep -A6 "<Replication>" /home/shan/amps-ha/amps-primary/amps-config-primary.xml
# expect: Destinations block present, Name = amps-queue-concurrency-ha-secondary, URI port 9119

grep -c "<Replication>" /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml
# expect: 0 — secondary must stay receive-only in this active-passive setup

sudo lsof -i :9109   # LAB PRIMARY replication transport, listening
sudo lsof -i :9119   # LAB SECONDARY replication transport, listening

tail -20 /home/shan/app-env/data/amps-ha/amps-primary/logs/amps.log | grep -i replicat
# expect the "connecting to destination" / "connected" lines from Chapter 13
```

Functional check — publish on LAB PRIMARY, confirm it lands on LAB SECONDARY's SOW:

```bash
# publish a trade to LAB PRIMARY directly (or via amps-publisher-simple pointed at 9107)
amps_publish -server 172.21.12.69:9107/amps/json -topic trades \
    -message '{"id":"repl-check-1","symbol":"AAPL","qty":10}'

# a few seconds later, query LAB SECONDARY's SOW for the same record
amps_sow -server 172.21.12.69:9117/amps/json -topic trades -filter "/id = 'repl-check-1'"
# expect: the record appears, even though it was never published directly to LAB SECONDARY
```

Then confirm the exclusion works as intended — publish to `test-trades` on LAB PRIMARY and confirm it does
**not** show up in LAB SECONDARY's `test-trades` SOW.

### Disabling it again

Remove the `<Replication>` block from `amps-config-primary.xml` and restart both nodes. This stops *new*
replication traffic; it does not retroactively remove records LAB SECONDARY already received while
replication was on — clear its SOW files under
`/home/shan/app-env/data/amps-ha/amps-secondary/sow/` if you need the two nodes back to genuinely
independent state for a clean resubscribe test.

---

## Chapter 15 – Bidirectional Replication for the 5+5 Ownership/ACK Scenario

This chapter reconfigures Chapter 14's setup for the scenario documented in
`docs/AMPS_Primary_Secondary_HA_Queue_Behavior_Summary.md`: 10 subscribers, 5 preferring LAB PRIMARY and 5
preferring LAB SECONDARY, a single replicated queue, at-least-once delivery, and message ownership that can
transfer between nodes. Read that document first — this chapter only covers the *server* config; Chapter 16
onward (once the test module exists) covers driving the scenario from the client side.

### Why Chapter 14's setup isn't enough here

Chapter 14 is intentionally one-directional and framed as optional: LAB PRIMARY pushes SOW rows to LAB
SECONDARY so a failed-over client doesn't see an empty topic, and the guide tells you to leave it off while
testing pure `HAClient` resubscribe. This scenario is the opposite case — both nodes are meant to be live
and locally serving subscribers *at the same time*, and per the summary doc's §15 ("Publisher Connection to
Secondary") and §7 ("Ownership Transfer"), either node can accept a publish and either node can end up
owning a message. A one-directional, primary-only `<Replication>` block can't support that: LAB SECONDARY
would have no way to tell LAB PRIMARY about messages it received or ownership it took on.

### What changes

1. **Add a mirrored `<Replication>` block to LAB SECONDARY** (Chapter 6's config), pointed back at LAB
   PRIMARY's `9109`. Combined with LAB PRIMARY's existing block (Chapter 5), this makes replication
   bidirectional — each node is both a source and a destination.

   ```xml
   <!-- amps-config-secondary.xml — ADD (Chapter 15). Mirrors LAB PRIMARY's block from Chapter 5,
        making replication bidirectional instead of the Chapter 14 active-passive default. -->
   <Replication>
       <Name>amps-queue-concurrency-ha-secondary-repl</Name>
       <Destinations>
           <Destination>
               <Name>amps-queue-concurrency-ha-primary</Name>
               <URI>tcp://172.21.12.69:9109/amps/fix</URI>
           </Destination>
       </Destinations>
       <Topics>
           <Topic><Name>trades</Name></Topic>
           <Topic><Name>orders</Name></Topic>
           <Topic><Name>risk</Name></Topic>
       </Topics>
   </Replication>
   ```

   With this in place, Chapter 13's troubleshooting note ("don't add `<Replication>` to both sides") no
   longer applies — for *this* scenario, both sides having one is correct.

2. **Pick which queue you're testing and keep the include-list to just that one (plus its dependents, if
   any) while you're focused on this scenario.** The three lab queues (`trades`, `orders`, `risk`) all stay
   eligible, but running the 5+5 test against all three at once makes the per-message telemetry (Chapter
   16's module) harder to read. `/queue/trades` is the natural default — it's what Chapters 10–13 already
   use for the worked examples.

3. **ACK/ownership durability — `SyncType`.** The summary doc's §13 calls this out explicitly: if
   replication of the ACK/ownership state is asynchronous, there's a window where LAB PRIMARY has recorded
   an ACK that LAB SECONDARY hasn't received yet, and a LAB PRIMARY failure inside that window can surface
   as a redelivery on LAB SECONDARY even though the message was technically already ACKed. That's expected
   at-least-once behavior per §9–§12 of the summary doc, but if you want to *narrow* that window as much as
   this lab's replication config allows, look for the synchronous/durable replication option in your
   installed AMPS version's Replication Guide — the option is commonly named `SyncType` (values along the
   lines of a synchronous/blocking mode vs. an async/fire-and-forget one) but, as flagged in Chapter 14, the
   exact element name and accepted values must be confirmed against the docs bundled with your AMPS 5.3.3.3
   install before you rely on the number. Whichever mode you pick, record it alongside your test results —
   it's one of the three questions (§16 of the summary doc: where is the message, who owns it, is it ACKed)
   that explains most of what you'll observe.

4. **Everything else — ports, transports, SOW/journal paths, queue `LeaseTimeout`/`MaxBacklog` — is
   unchanged from Chapters 5 and 6.** This chapter only adds the second `<Replication>` block and the
   `SyncType` decision.

### Client-side placement: 5 subscribers preferring each node

`DefaultServerChooser` tries URIs in the order they're added (Chapter 10). To bias 5 subscriber instances
toward LAB PRIMARY and 5 toward LAB SECONDARY — while still letting every one of them fail over to the
*other* node if their preferred one goes down, which is required for the Chapter 17 (summary doc §17, Test
E) primary-failure test — just reverse the add order per group:

```java
// Subscribers S1-S5: primary-preferred
DefaultServerChooser chooser = new DefaultServerChooser();
chooser.add("tcp://172.21.12.69:9107/amps/json");   // LAB PRIMARY first
chooser.add("tcp://172.21.12.69:9117/amps/json");   // LAB SECONDARY as failover

// Subscribers S6-S10: secondary-preferred
DefaultServerChooser chooser = new DefaultServerChooser();
chooser.add("tcp://172.21.12.69:9117/amps/json");   // LAB SECONDARY first
chooser.add("tcp://172.21.12.69:9107/amps/json");   // LAB PRIMARY as failover
```

Each subscriber still needs a distinct AMPS client name (Chapter 10's `clientName`) — ten subscribers
sharing one name will fight each other's queue lease. The test module in Chapter 16 handles this
per-subscriber naming and grouping automatically; the snippet above is just the underlying mechanism.

### Verification

```bash
grep -c "<Replication>" /home/shan/amps-ha/amps-primary/amps-config-primary.xml
grep -c "<Replication>" /home/shan/amps-ha/amps-secondary/amps-config-secondary.xml
# expect: 1 on both, now that Chapter 15's mirrored block is in place

/home/shan/amps-ha/scripts/restart-all.sh

tail -20 /home/shan/app-env/data/amps-ha/amps-primary/logs/amps.log   | grep -i replicat
tail -20 /home/shan/app-env/data/amps-ha/amps-secondary/logs/amps.log | grep -i replicat
# expect a "connected" line on each side now, not just LAB PRIMARY's outbound side
```

Then run Chapter 16's module against Tests A–E from the summary doc's §17. Chapter 14's SOW-only
functional check (publish to primary, confirm it lands on secondary) still applies here as a smoke test
before running the full scenario.

---

## Chapter 16 – 5+5 Ownership/ACK Test Module

*Placeholder — this chapter is filled in once the client-side test module for the
`AMPS_Primary_Secondary_HA_Queue_Behavior_Summary.md` scenario (Chapter 15) is designed and built. It will
document the module's name, how to configure the 5-primary/5-secondary subscriber split, and how to run
Tests A–E from the summary doc's §17 against it.*

---

*End of HA_AMPS_LAB_SETUP_GUIDE.md*
