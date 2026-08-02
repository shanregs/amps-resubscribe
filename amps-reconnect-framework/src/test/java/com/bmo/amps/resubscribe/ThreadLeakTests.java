package com.bmo.amps.resubscribe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.support.ReconnectCycleHarness;
import com.bmo.amps.resubscribe.support.ReconnectCycleHarness.ReadyClient;

/**
 * Stress test for THREADING_MODEL.md §2 & §3's leak-prevention guarantees: thread count is bounded
 * by (reader + recovery executor) × client count for the life of the process, never by reconnect
 * cycle count. See TEST_PLAN.md §7.
 */
@Tag("stress")
class ThreadLeakTests {

    private final ReconnectCycleHarness harness = new ReconnectCycleHarness();

    @AfterEach
    void cleanup() {
        harness.cleanup();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void hundredReconnectCyclesDoNotGrowReaderThreadCount() throws Exception {
        ReadyClient client = harness.startedClient(ClientType.CLIENT_1);

        long baseline = liveThreadsNamed("amps-reader-CLIENT_1") + liveThreadsNamed("amps-recovery-CLIENT_1");
        assertThat(baseline).isEqualTo(2); // one reader thread, one (idle) recovery thread

        for (int i = 0; i < 100; i++) {
            harness.runOneReconnectCycle(client);

            if (i % 10 == 0) {
                long current = liveThreadsNamed("amps-reader-CLIENT_1") + liveThreadsNamed("amps-recovery-CLIENT_1");
                assertThat(current)
                        .as("live amps-reader/recovery threads after cycle %d", i)
                        .isEqualTo(baseline);
            }
        }

        long finalCount = liveThreadsNamed("amps-reader-CLIENT_1") + liveThreadsNamed("amps-recovery-CLIENT_1");
        assertThat(finalCount).isEqualTo(baseline);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shutdownTerminatesAllExecutorsAndThreads() throws Exception {
        ReadyClient client = harness.startedClient(ClientType.CLIENT_1);

        // Mirrors AmpsLifecycleManager.stop() (CALL_FLOW.md §4): the reader must be stopped — which
        // unblocks its MessageStream.hasNext() — before the executor is shut down, or the still-running
        // task holds the executor open indefinitely.
        client.reader().stop(client.context());
        client.context().readerExecutor().shutdown();
        client.context().recoveryExecutor().shutdown();
        boolean readerTerminated = client.context().readerExecutor().awaitTermination(10, TimeUnit.SECONDS);
        boolean recoveryTerminated = client.context().recoveryExecutor().awaitTermination(10, TimeUnit.SECONDS);

        assertThat(readerTerminated).isTrue();
        assertThat(recoveryTerminated).isTrue();
        assertThat(liveThreadsNamed("amps-reader-CLIENT_1")).isZero();
        assertThat(liveThreadsNamed("amps-recovery-CLIENT_1")).isZero();
    }

    private static long liveThreadsNamed(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith(prefix))
                .filter(Thread::isAlive)
                .count();
    }
}
