package com.bmo.amps.resubscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.crankuptheamps.client.HAClient;

import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.model.ConnectionState;
import com.bmo.amps.resubscribe.support.ConnectionStates;
import com.bmo.amps.resubscribe.support.ReconnectCycleHarness;
import com.bmo.amps.resubscribe.support.ReconnectCycleHarness.ReadyClient;

/**
 * Component-level: real {@code DefaultAmpsClientManager} + {@code DefaultAmpsMessageReader} +
 * {@code AmpsConnectionListener} wired together against a mocked {@code HAClient}. See
 * TEST_PLAN.md §5.
 */
class MultiReconnectTests {

    private final ReconnectCycleHarness harness = new ReconnectCycleHarness();

    @AfterEach
    void cleanup() {
        harness.cleanup();
    }

    @Test
    void tenSequentialReconnectCyclesEachReachReading() throws Exception {
        ReadyClient client = harness.startedClient(ClientType.CLIENT_1, mock(HAClient.class));
        Set<String> seenSubscriptionIds = new HashSet<>();
        seenSubscriptionIds.add(client.context().subscriptionId().toString());

        for (int i = 0; i < 10; i++) {
            harness.runOneReconnectCycle(client);
            seenSubscriptionIds.add(client.context().subscriptionId().toString());
        }

        assertThat(seenSubscriptionIds).hasSize(11); // 1 initial subscribe + 10 distinct resubscribes
    }

    @Test
    void readerThreadCountStaysAtOneAfterEachCycle() throws Exception {
        ReadyClient client = harness.startedClient(ClientType.CLIENT_1, mock(HAClient.class));

        for (int i = 0; i < 5; i++) {
            harness.runOneReconnectCycle(client);
            long liveReaderThreads = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("amps-reader-CLIENT_1"))
                    .filter(Thread::isAlive)
                    .count();
            assertThat(liveReaderThreads).isEqualTo(1);
        }
    }

    @Test
    void multipleClientsRecoverIndependently() throws Exception {
        ReadyClient client1 = harness.startedClient(ClientType.CLIENT_1, mock(HAClient.class));
        ReadyClient client2 = harness.startedClient(ClientType.CLIENT_2, mock(HAClient.class));

        client1.listener().connectionStateChanged(ConnectionStates.disconnected());
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client1.context().state()).isEqualTo(ConnectionState.WAITING));

        assertThat(client2.context().state()).isEqualTo(ConnectionState.READING);
    }
}
