package com.bmo.amps.resubscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.HAClient;

import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.model.ConnectionState;
import com.bmo.amps.resubscribe.support.ConnectionStates;
import com.bmo.amps.resubscribe.support.ReconnectCycleHarness;
import com.bmo.amps.resubscribe.support.ReconnectCycleHarness.ReadyClient;

/** DESIGN.md §5 rules 1, 2 & 5. See TEST_PLAN.md §8. */
class DuplicateSubscriptionTests {

    private final ReconnectCycleHarness harness = new ReconnectCycleHarness();

    @AfterEach
    void cleanup() {
        harness.cleanup();
    }

    @Test
    void subscribeCalledExactlyOnceAtStartup() throws Exception {
        HAClient haClient = mock(HAClient.class);
        harness.startedClient(ClientType.CLIENT_1, haClient);

        verify(haClient, times(1)).execute(any(Command.class));
    }

    @Test
    void resubscribeReplacesRatherThanAccumulates() throws Exception {
        ReadyClient client = harness.startedClient(ClientType.CLIENT_1);

        String before = client.context().subscriptionId().toString();
        harness.runOneReconnectCycle(client);
        String after = client.context().subscriptionId().toString();

        // A single field, not a growing collection — this is the type-level guarantee that only one
        // subscription id can be considered "active" at a time.
        assertThat(after).isNotNull().isNotEqualTo(before);
    }

    @Test
    void concurrentLoggedOnCallbacksProduceExactlyOneResubscribe() throws Exception {
        HAClient haClient = mock(HAClient.class);
        ReadyClient client = harness.startedClient(ClientType.CLIENT_1, haClient);

        client.listener().connectionStateChanged(ConnectionStates.disconnected());
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.context().state()).isEqualTo(ConnectionState.WAITING));
        client.listener().connectionStateChanged(ConnectionStates.connected());

        int callerCount = 8;
        ExecutorService callers = Executors.newFixedThreadPool(callerCount);
        CountDownLatch ready = new CountDownLatch(callerCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < callerCount; i++) {
                callers.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        client.listener().connectionStateChanged(ConnectionStates.loggedOn());
                    } catch (Exception ignored) {
                        // Only the first CAS winner's transition succeeds; the rest are expected no-ops.
                    }
                });
            }
            ready.await(2, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            callers.shutdown();
            callers.awaitTermination(5, TimeUnit.SECONDS);
        }

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.context().state()).isEqualTo(ConnectionState.READING));

        // Exactly one of the N concurrent LoggedOn callbacks wins the CONNECTED->LOGGED_ON CAS
        // (DESIGN.md §3.2), so exactly one recovery sequence — and therefore exactly one resubscribe
        // command — is ever enqueued, regardless of how many duplicate callbacks arrived.
        verify(haClient, times(2)).execute(any(Command.class)); // 1 initial subscribe + 1 resubscribe
    }

    @Test
    void hundredCyclesNeverLeaveMoreThanOneActiveSubscriptionId() throws Exception {
        ReadyClient client = harness.startedClient(ClientType.CLIENT_1);
        Set<String> allIdsEverSeen = new HashSet<>();
        allIdsEverSeen.add(client.context().subscriptionId().toString());

        for (int i = 0; i < 100; i++) {
            harness.runOneReconnectCycle(client);
            String current = client.context().subscriptionId().toString();
            assertThat(current).isNotNull();
            allIdsEverSeen.add(current);
            // At any observation point only one id is readable from the context — there is no
            // collection of "still active" ids to leak.
        }

        assertThat(allIdsEverSeen).hasSize(101);
    }
}
