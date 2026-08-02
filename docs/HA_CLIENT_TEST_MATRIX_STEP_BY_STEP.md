# HA_CLIENT_TEST_MATRIX_STEP_BY_STEP.md

Elaborated, runnable version of `docs/HA_CLIENT_TEST_MATRIX_STEP_BY_STEP.xlsx` (TC-001–TC-006).
Each TC below expands the spreadsheet's Step No / Action / Expected Result columns with the exact
commands and log lines to look for, and says which automated test method (in
`HAClientTestMatrixTests.java`) covers it. The `Pass/Fail` / `Notes` columns from the spreadsheet
are kept here too, for whoever runs this manually to fill in.

**Prerequisite:** both AMPS instances set up per `docs/HA_AMPS_LAB_SETUP_GUIDE.md`. Do the manual
walkthrough in that doc's §8 at least once before trusting the automated suite.

**Automated suite:** `amps-reconnect-framework/src/test/java/com/bmo/amps/resubscribe/HAClientTestMatrixTests.java`,
`@Disabled` by default. Enable it (or run with a filter that includes it) once your local setup is
verified — "we'll run it later" refers to this class.

---

## Environment

(From the xlsx "Environment" sheet — also the defaults baked into `AmpsProcessController` and both
modules' `application.yml`.)

| Item | Value |
|---|---|
| Primary TCP | `9007` |
| Secondary TCP | `9017` |
| Primary Admin | `8085` |
| Secondary Admin | `8185` |
| Primary Folder | `/home/shan/AMPS_PRIMARY` |
| Secondary Folder | `/home/shan/AMPS_SECONDARY` |
| HAClient Endpoints | `tcp://localhost:9007/amps/json; tcp://localhost:9017/amps/json` |

**These are `AmpsProcessController`'s original, simpler two-instance defaults — not the same
environment as `HA_AMPS_LAB_SETUP_GUIDE.md`'s fully-isolated lab.** The lab guide deliberately moved
its LAB PRIMARY/SECONDARY off `9007`/`9008`/`8085` (production's ports) onto `9107`/`9108`/`8185` and
`9117`/`9118`/`8195`, specifically so the lab can run alongside production without touching it — see
that guide's Chapter 1. `9007` above is production's port; running `HAClientTestMatrixTests` with the
defaults in this table against a real environment where production is also listening on `9007` will
collide with it. To run this matrix against the isolated lab instead, override:

```
-Damps.test.primary.port=9107 -Damps.test.secondary.port=9117
-Damps.test.primary.folder=/home/shan/amps-ha/amps-primary
-Damps.test.secondary.folder=/home/shan/amps-ha/amps-secondary
```

If any of these differ in your actual setup, override via the matching `-Damps.test.*` system
property when running `HAClientTestMatrixTests` (see that class's Javadoc and
`AmpsProcessController`) — don't hand-edit the defaults unless you're also changing
`HA_AMPS_LAB_SETUP_GUIDE.md` and `application.yml` to match.

---

## TC-001 — Initial Connection

**Objective:** verify the app connects to Primary.
**Preconditions:** Primary configured for port 9007, Secondary for port 9017 (neither started yet).
**Automated by:** `tc001_initialConnection()`

| Step | Action | Expected Result | Pass/Fail | Notes |
|---|---|---|---|---|
| 1 | Start Primary: `cd /home/shan/AMPS_PRIMARY && ./bin/ampServer ./config.xml` | Startup banner printed; `ss -ltnp \| grep 9007` shows it listening | | |
| 2 | Start Secondary: `cd /home/shan/AMPS_SECONDARY && ./bin/ampServer ./config.xml` | Startup banner printed; `ss -ltnp \| grep 9017` shows it listening; no errors from either instance | | |
| 3 | Start the app (`mvn -pl amps-reconnect-framework spring-boot:run`, or run `HAClientTestMatrixTests.startApp()`'s equivalent: build an `AmpsLifecycleManager` and call `start()`) | App logs `CLIENT_1 CREATED -> CONNECTING`, then `... -> CONNECTED`, `... -> LOGGED_ON`; a subscription command is issued | | |
| 4 | Verify logs show connection to Primary: grep app logs for `CLIENT_1 Subscribed` and `CLIENT_1 ... -> READING` | State is `READING`; `subscriptionId` is non-null; connection is to **port 9007** specifically (not 9017) — confirm via Primary's own log showing a new client connection, or via `AmpsClientContext.subscriptionId()` in a debugger/test | | |

---

## TC-002 — Primary Failure

**Objective:** verify failover to Secondary.
**Preconditions:** both servers running, app connected to Primary and `READING` (i.e. TC-001 passed).
**Automated by:** `tc002_primaryFailure()`

| Step | Action | Expected Result | Pass/Fail | Notes |
|---|---|---|---|---|
| 1 | Publish a few messages to `orders.queue` against **Primary** (`amps_publish -server localhost:9007/amps/json -topic orders.queue -file some.json`, or call `HAClient.publish(...)` from a test) | Messages received by the app's `MessageProcessor` while still on Primary | | |
| 2 | Stop Primary (Ctrl+C in its terminal, or `pkill -f "AMPS_PRIMARY/config.xml"`) | App detects the disconnect: logs `CLIENT_1 ... -> DISCONNECTED` then `... -> WAITING` (no distinct "reconnecting" log line — AMPS gives no such signal, see `THREADING_MODEL.md` §4) | | |
| 3 | Watch application logs for the reconnect sequence | `... -> CONNECTED` (now against Secondary), `... -> LOGGED_ON`, `CLIENT_1 Resubscribed [subscriptionId=...]` (a **new**, different subscriptionId than TC-001's), `... -> READING` — all with no JVM restart | | |
| 4 | Verify the app is now on Secondary | Publishing against Primary's port no longer reaches the app (it's down); the reconnect banner in the app's log corresponds to the secondary's connection | | |
| 5 | Publish another message, this time against **Secondary** (`amps_publish -server localhost:9017/amps/json ...`) | Message received by `MessageProcessor` — processing resumed transparently after failover | | |

---

## TC-003 — Primary Recovery

**Objective:** verify behavior once Primary comes back while the app is running on Secondary.
**Preconditions:** app connected to Secondary (Primary down — e.g. straight after TC-002).
**Automated by:** `tc003_primaryRecovery()`

| Step | Action | Expected Result | Pass/Fail | Notes |
|---|---|---|---|---|
| 1 | Start Primary again | Primary comes up cleanly; app's connection state is unaffected at the moment Primary starts (no immediate reconnect attempt just because Primary exists again) | | |
| 2 | Observe the client for ~30–60 seconds | App remains `READING`, still against Secondary, continuing to process messages — **or**, if you've explicitly configured a fail-back policy on your `ServerChooser`, it reconnects to Primary and recovers back to `READING` | | Record which behavior you observed and whether it matches what you intended to configure — `DefaultServerChooser`'s out-of-the-box policy does not force fail-back; that's a deliberate design choice to verify against your actual `ServerChooser` setup, not an assumption this framework makes for you. |
| 3 | Verify configured reconnect/failback policy matches what step 2 actually did | App never ends up stuck disconnected — it's either steady on Secondary or has fully recovered to `READING` after any failback-triggered reconnect | | |

---

## TC-004 — Secondary Failure

**Objective:** verify no impact on the app while it's on Primary.
**Preconditions:** app connected to Primary and `READING`.
**Automated by:** `tc004_secondaryFailureNoImpact()`

| Step | Action | Expected Result | Pass/Fail | Notes |
|---|---|---|---|---|
| 1 | Stop Secondary (`pkill -f "AMPS_SECONDARY/config.xml"`) | No log activity from the app at all — it isn't using Secondary, so this is a non-event for it | | |
| 2 | Continue publishing to Primary | Messages keep flowing to `MessageProcessor`, no delay or error | | |
| 3 | Continue consuming | State remains `READING` throughout; no `DISCONNECTED`/`WAITING` transition logged | | |

---

## TC-005 — Both Servers Down

**Objective:** verify the retry loop and eventual reconnect once a server becomes available again.
**Preconditions:** app running (against Primary).
**Automated by:** `tc005_bothServersDown()`

| Step | Action | Expected Result | Pass/Fail | Notes |
|---|---|---|---|---|
| 1 | Stop Primary | App transitions toward `WAITING`/reconnect, as in TC-002 | | |
| 2 | Stop Secondary too, before the app finishes failing over | Both instances down; app settles into `WAITING` and stays there — must not crash, throw unhandled exceptions, or exit the process | | |
| 3 | Observe retry logs for ~10–30 seconds | Periodic reconnect attempts (governed by `HAClient`'s own retry/backoff, not this framework's `RetryPolicy` — that only governs *resubscribe* retries after a successful reconnect, see `DESIGN.md` §6); state remains `WAITING`, no crash | | |
| 4 | Start Secondary | App reconnects once a server is reachable: `... -> CONNECTED` → `LOGGED_ON` → `Resubscribed` → `... -> READING`, same as any other recovery | | |

---

## TC-006 — Repeated Failover

**Objective:** verify stability (reconnect every cycle, no resource/thread leaks) across multiple
failover cycles.
**Preconditions:** both servers running, app `READING`.
**Automated by:** `tc006_repeatedFailover()` (5 cycles; see also `ThreadLeakTests` for the
mocked-server unit coverage of the same underlying guarantee this exercises end-to-end)

| Step | Action | Expected Result | Pass/Fail | Notes |
|---|---|---|---|---|
| 1 | Stop Primary | App reaches `WAITING`/`READING`-again as in TC-002; check thread count (`amps-reader-*`, `amps-recovery-*`) does not grow cycle over cycle — see `THREADING_MODEL.md` §2 for why it shouldn't | | |
| 2 | Start Primary | App recovers to `READING` | | |
| 3 | Stop Secondary | No impact (per TC-004) — app is on Primary at this point in the cycle | | |
| 4 | Start Secondary | Still no impact; Secondary is simply available again as a failover target | | |
| 5 | Repeat steps 1–4 for a total of **5 cycles** | Every cycle reconnects cleanly; final state is `READING`; no accumulation of live `amps-reader-*`/`amps-recovery-*` threads beyond one of each per client (verify via `jstack <pid> \| grep amps-` if running the app as a real process, or via the thread-identity assertions the automated test uses) | | |

---

## Sign-off

| TC ID | Scenario | Result | Tested by | Date |
|---|---|---|---|---|
| TC-001 | Initial Connection | | | |
| TC-002 | Primary Failure | | | |
| TC-003 | Primary Recovery | | | |
| TC-004 | Secondary Failure | | | |
| TC-005 | Both Servers Down | | | |
| TC-006 | Repeated Failover | | | |
