package com.bmo.amps.resubscribe;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Failover tests against a real primary/secondary AMPS pair (TEST_PLAN.md §6). Per ARCHITECTURE.md
 * §5 and CALL_FLOW.md §3, failover is deliberately *not* a distinct code path — it is the ordinary
 * disconnect/recovery sequence (already covered against mocks by {@code DisconnectRecoveryTests})
 * landing on a different physical server because of {@code HAClient}'s configured
 * {@code ServerChooser}. These tests exist to prove that claim against real infrastructure, not to
 * exercise framework logic that doesn't otherwise have coverage.
 *
 * <p>Disabled by default — requires two live AMPS instances. See {@code IntegrationTests} for the
 * general pattern to enable locally.
 */
@Disabled("Requires a live primary/secondary AMPS pair; see class Javadoc")
class FailoverTests {

    @Test
    void primaryShutdownTriggersFailoverToSecondary() {
        throw new UnsupportedOperationException("Fill in against a real primary/secondary AMPS pair");
    }

    @Test
    void resubscribeSucceedsAgainstSecondary() {
        throw new UnsupportedOperationException("Fill in against a real primary/secondary AMPS pair");
    }

    @Test
    void failoverPreservesMessageProcessingWithoutRestart() {
        throw new UnsupportedOperationException("Fill in against a real primary/secondary AMPS pair");
    }

    @Test
    void failbackToPrimaryAlsoRecovers() {
        throw new UnsupportedOperationException("Fill in against a real primary/secondary AMPS pair");
    }
}
