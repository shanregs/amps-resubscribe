package com.bmo.amps.resubscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.crankuptheamps.client.Message;

import com.bmo.amps.resubscribe.config.AmpsClientDefinition;
import com.bmo.amps.resubscribe.config.AmpsProperties;
import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.lifecycle.AmpsLifecycleManager;
import com.bmo.amps.resubscribe.manager.AmpsClientManager;
import com.bmo.amps.resubscribe.manager.DefaultAmpsClientManager;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.model.ConnectionState;
import com.bmo.amps.resubscribe.processor.MessageProcessor;
import com.bmo.amps.resubscribe.reader.AmpsMessageReader;
import com.bmo.amps.resubscribe.reader.DefaultAmpsMessageReader;
import com.bmo.amps.resubscribe.support.AmpsProcessController;

/**
 * Automates TC-001 through TC-006 from {@code docs/HA_CLIENT_TEST_MATRIX_STEP_BY_STEP.xlsx} /
 * {@code docs/HA_CLIENT_TEST_MATRIX_STEP_BY_STEP.md}. Each test method's step comments map 1:1 to
 * the "Step No" / "Action" / "Expected Result" columns for that TC ID in the spreadsheet.
 *
 * <p>Unlike every other test class in this project, these run against <b>real</b> AMPS server
 * processes (via {@link AmpsProcessController}) and a real {@link AmpsLifecycleManager} — no
 * mocked {@code HAClient}. {@code @Disabled} by default: enable only once
 * {@code docs/HA_AMPS_LAB_SETUP_GUIDE.md}'s primary/secondary instances exist and
 * {@link AmpsProcessController}'s default start/stop commands (or your {@code -Damps.test.*}
 * overrides) are verified to work in your environment. "We'll run it later" — this class is the
 * "later."
 *
 * <p>Steps described in the spreadsheet as pure observation ("Watch application logs", "Observe
 * client") are represented here as bounded waits plus a state assertion, since there's no log-
 * scraping harness in this project — treat the console output during a run as the human-readable
 * companion to these assertions, not a replacement for them.
 */
@Disabled("Requires live primary/secondary AMPS instances — see docs/HA_CLIENT_TEST_MATRIX_STEP_BY_STEP.md")
class HAClientTestMatrixTests {

    private static final String PRIMARY_URI = "tcp://localhost:9007/amps/json";
    private static final String SECONDARY_URI = "tcp://localhost:9017/amps/json";
    private static final String QUEUE = "orders.queue";
    private static final Duration READING_TIMEOUT = Duration.ofSeconds(20);

    private final AmpsProcessController amps = new AmpsProcessController();
    private final List<Message> received = Collections.synchronizedList(new ArrayList<>());
    private final MessageProcessor processor = (type, message) -> received.add(message);

    private AmpsLifecycleManager lifecycle;
    private AmpsClientContext context;

    @AfterEach
    void tearDown() throws Exception {
        if (lifecycle != null) {
            lifecycle.stop();
        }
        // Leave every test starting from a clean, fully-stopped pair, regardless of what state the
        // test itself left the servers in.
        if (amps.isPrimaryUp()) {
            amps.stopPrimary();
        }
        if (amps.isSecondaryUp()) {
            amps.stopSecondary();
        }
    }

    private void startApp() {
        AmpsClientDefinition definition =
                new AmpsClientDefinition(ClientType.CLIENT_1, List.of(PRIMARY_URI, SECONDARY_URI), QUEUE, null);
        AmpsProperties properties = new AmpsProperties(List.of(definition));
        AmpsClientManager manager = new DefaultAmpsClientManager();
        AmpsMessageReader reader = new DefaultAmpsMessageReader(processor);
        lifecycle = new AmpsLifecycleManager(properties, manager, reader);
        lifecycle.start();
        context = manager.contextFor(ClientType.CLIENT_1);
    }

    private void awaitReading() {
        await().atMost(READING_TIMEOUT).untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.READING));
    }

    private void publish(String json) throws Exception {
        context.haClient().publish(QUEUE, json);
    }

    /**
     * TC-001 — Initial Connection. Objective: verify the app connects to Primary.
     * Preconditions: Primary on 9007, Secondary on 9017.
     */
    @Test
    void tc001_initialConnection() throws Exception {
        // Step 1: Start Primary -> Connected to Primary
        amps.startPrimary();
        assertThat(amps.isPrimaryUp()).isTrue();

        // Step 2: Start Secondary -> No errors
        amps.startSecondary();
        assertThat(amps.isSecondaryUp()).isTrue();

        // Step 3: Start the app -> Subscriptions created
        startApp();

        // Step 4: Verify logs show connection to Primary -> Subscriptions created
        awaitReading();
        assertThat(context.subscriptionId()).isNotNull();
    }

    /**
     * TC-002 — Primary Failure. Objective: verify failover to Secondary.
     * Preconditions: both servers running, app connected to Primary.
     */
    @Test
    void tc002_primaryFailure() throws Exception {
        amps.startPrimary();
        amps.startSecondary();
        startApp();
        awaitReading();

        // Step 1: Publish a few messages (against Primary) -> received while still on Primary
        publish("{\"seq\":1}");
        publish("{\"seq\":2}");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSizeGreaterThanOrEqualTo(2));

        // Step 2: Stop Primary (Ctrl+C) -> disconnect detected, recovery begins
        amps.stopPrimary();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.WAITING));

        // Step 3 & 4: Application fails over to Secondary and resubscribes -> back to READING
        awaitReading();

        // Step 5: Publish another message (now landing on Secondary) -> still received
        int beforeFailover = received.size();
        publish("{\"seq\":3}");
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(received.size()).isGreaterThan(beforeFailover));
    }

    /**
     * TC-003 — Primary Recovery. Objective: verify behavior once Primary restarts while the app is
     * running on Secondary. {@code DefaultServerChooser}'s default policy does not force a
     * reconnect back to a now-healthy earlier-added server (verify against your AMPS client
     * version) — this test asserts the safe invariant ("never left disconnected"), not a specific
     * fail-back behavior, since that's a configuration choice rather than something this framework
     * enforces.
     * Preconditions: connected to Secondary (Primary intentionally left down).
     */
    @Test
    void tc003_primaryRecovery() throws Exception {
        amps.startSecondary();
        startApp(); // Primary is down, so the app must connect via Secondary
        awaitReading();

        // Step 1: Start Primary -> application behaves as designed
        amps.startPrimary();

        // Step 2: Observe the client for a bounded window -> application behaves as designed
        Thread.sleep(Duration.ofSeconds(5).toMillis());

        // Step 3: Verify configured reconnect/failback policy -> application behaves as designed.
        // Concretely: it must be READING (steady on Secondary) or, if a failback-triggered
        // reconnect happened, it must recover back to READING — never stuck disconnected.
        assertThat(context.state()).isIn(ConnectionState.READING, ConnectionState.WAITING);
        if (context.state() == ConnectionState.WAITING) {
            awaitReading();
        }
    }

    /**
     * TC-004 — Secondary Failure. Objective: verify no impact on the app while it's on Primary.
     * Preconditions: connected to Primary.
     */
    @Test
    void tc004_secondaryFailureNoImpact() throws Exception {
        amps.startPrimary();
        amps.startSecondary();
        startApp();
        awaitReading();

        // Step 1: Stop Secondary -> No interruption (app isn't using it)
        amps.stopSecondary();
        assertThat(context.state()).isEqualTo(ConnectionState.READING);

        // Step 2 & 3: Continue publishing/consuming -> No interruption
        int before = received.size();
        publish("{\"seq\":1}");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received.size()).isGreaterThan(before));
        assertThat(context.state()).isEqualTo(ConnectionState.READING);
    }

    /**
     * TC-005 — Both Servers Down. Objective: verify the retry loop and eventual reconnect.
     * Preconditions: application running (against Primary).
     */
    @Test
    void tc005_bothServersDown() throws Exception {
        amps.startPrimary();
        amps.startSecondary();
        startApp();
        awaitReading();

        // Step 1: Stop Primary -> retries continue (app moving toward Secondary)
        amps.stopPrimary();
        // Step 2: Stop Secondary too, before failover completes -> both down
        amps.stopSecondary();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.WAITING));

        // Step 3: Observe retry logs -> reconnect when a server becomes available. Concretely:
        // still WAITING (not stuck/crashed) while both remain down.
        Thread.sleep(Duration.ofSeconds(5).toMillis());
        assertThat(context.state()).isEqualTo(ConnectionState.WAITING);

        // Step 4: Start Secondary -> reconnect when server available
        amps.startSecondary();
        awaitReading();
    }

    /**
     * TC-006 — Repeated Failover. Objective: verify stability (reconnect every cycle, no resource
     * leaks) across 5 stop/start cycles of both servers.
     * Preconditions: both running.
     */
    @Test
    void tc006_repeatedFailover() throws Exception {
        amps.startPrimary();
        amps.startSecondary();
        startApp();
        awaitReading();

        for (int cycle = 1; cycle <= 5; cycle++) {
            // Step 1: Stop Primary -> no resource leaks (see ThreadLeakTests for the unit-level
            // guarantee this exercises end-to-end)
            amps.stopPrimary();
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(context.state()).isNotEqualTo(ConnectionState.READING));

            // Step 2: Start Primary -> reconnect each cycle
            amps.startPrimary();
            awaitReading();

            // Step 3 & 4: Stop then start Secondary -> reconnect each cycle (no-op for the app,
            // since it's on Primary at this point — see TC-004; exercised here for completeness)
            amps.stopSecondary();
            amps.startSecondary();
            assertThat(context.state()).isEqualTo(ConnectionState.READING);
        }
        // Step 5: Repeat 5 cycles -> reconnect each cycle (loop above)
    }
}
