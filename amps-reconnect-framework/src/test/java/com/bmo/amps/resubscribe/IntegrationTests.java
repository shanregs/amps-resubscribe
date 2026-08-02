package com.bmo.amps.resubscribe;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests against a real, single-instance AMPS server (TEST_PLAN.md §9). Every other test
 * class in this project runs against mocked {@code HAClient} instances and needs no live server;
 * these are the only ones that do.
 *
 * <p>Disabled by default so {@code mvn test} stays hermetic in CI. To run locally:
 * <ol>
 *   <li>Start an AMPS instance reachable at the URI configured below (or override it).</li>
 *   <li>Create the queue topic(s) referenced by the test client definitions.</li>
 *   <li>Remove the {@code @Disabled} annotation, or run with
 *       {@code -Dtest=IntegrationTests -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition}.</li>
 * </ol>
 */
@Disabled("Requires a live AMPS server; see class Javadoc for how to enable locally")
class IntegrationTests {

    @Test
    void fullStartupPublishConsume() {
        // 1. Start AmpsLifecycleManager against the live AMPS instance.
        // 2. Publish a message to the configured queue via a separate, plain AMPS publisher client.
        // 3. Assert the configured MessageProcessor receives it (e.g., via a test-only
        //    MessageProcessor that records messages into a BlockingQueue for the test to poll).
        throw new UnsupportedOperationException("Fill in against your AMPS test environment");
    }

    @Test
    void killAndRestoreAmpsProcessRecoversWithoutAppRestart() {
        // 1. Start the framework, confirm message flow.
        // 2. Stop the AMPS server process (or block its port).
        // 3. Wait for the client to reach ConnectionState.WAITING (DisconnectRecoveryTests already
        //    covers the mechanics of this transition — this test proves it against a real server).
        // 4. Restart the AMPS server.
        // 5. Publish again; assert messages resume flowing with no restart of this JVM.
        throw new UnsupportedOperationException("Fill in against your AMPS test environment");
    }

    @Test
    void messageContinuityAcrossReconnect() {
        // Publish before, during (server-side queued), and after a disconnect; assert no loss and
        // no duplication at the processor (queue ack semantics via Message.ack() are what this
        // exercises for real, unlike the mocked-stream unit tests elsewhere in this project).
        throw new UnsupportedOperationException("Fill in against your AMPS test environment");
    }

    @Test
    void gracefulShutdownDrainsCleanly() {
        // AmpsLifecycleManager.stop() during active message flow: assert no exceptions logged and
        // no orphaned subscription left active server-side after disconnect.
        throw new UnsupportedOperationException("Fill in against your AMPS test environment");
    }
}
