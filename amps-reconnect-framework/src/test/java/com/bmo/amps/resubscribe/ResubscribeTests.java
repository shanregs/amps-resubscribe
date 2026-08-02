package com.bmo.amps.resubscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.CommandId;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.DisconnectedException;

import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.manager.AmpsClientManager;
import com.bmo.amps.resubscribe.manager.DefaultAmpsClientManager;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.support.TestContextFactory;
import com.bmo.amps.resubscribe.util.ExponentialBackoffRetryPolicy;
import com.bmo.amps.resubscribe.util.RetryPolicy;

/** DESIGN.md §5 rule 1 & 4, §6 retry policy. See TEST_PLAN.md §3. */
class ResubscribeTests {

    private final HAClient haClient = mock(HAClient.class);
    private final AmpsClientManager manager = new DefaultAmpsClientManager(definition -> haClient);

    @AfterEach
    void cleanup() {
        for (AmpsClientContext context : manager.allContexts()) {
            TestContextFactory.shutdown(context);
        }
    }

    @Test
    void resubscribeReplacesOldSubscriptionIdWithADistinctNewOne() throws Exception {
        when(haClient.execute(any(Command.class)))
                .thenReturn(mock(MessageStream.class))
                .thenReturn(mock(MessageStream.class));

        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));
        manager.subscribe(ClientType.CLIENT_1);
        CommandId originalId = manager.contextFor(ClientType.CLIENT_1).subscriptionId();

        manager.resubscribe(ClientType.CLIENT_1);
        CommandId newId = manager.contextFor(ClientType.CLIENT_1).subscriptionId();

        assertThat(newId).isNotNull().isNotEqualTo(originalId);
    }

    @Test
    void resubscribeUnsubscribesTheStaleSubscription() throws Exception {
        when(haClient.execute(any(Command.class)))
                .thenReturn(mock(MessageStream.class))
                .thenReturn(mock(MessageStream.class));

        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));
        manager.subscribe(ClientType.CLIENT_1);
        CommandId originalId = manager.contextFor(ClientType.CLIENT_1).subscriptionId();

        manager.resubscribe(ClientType.CLIENT_1);

        verify(haClient, times(1)).unsubscribe(originalId);
    }

    @Test
    void resubscribeSucceedsEvenIfUnsubscribeOfStaleIdFails() throws Exception {
        when(haClient.execute(any(Command.class)))
                .thenReturn(mock(MessageStream.class))
                .thenReturn(mock(MessageStream.class));
        // unsubscribe(CommandId) declares "throws DisconnectedException", not the broader
        // AMPSException — Mockito enforces the declared checked-exception type exactly.
        doThrow(new DisconnectedException("already gone")).when(haClient).unsubscribe(any(CommandId.class));

        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));
        manager.subscribe(ClientType.CLIENT_1);

        // Must not throw even though the best-effort unsubscribe of the stale id fails.
        manager.resubscribe(ClientType.CLIENT_1);

        assertThat(manager.contextFor(ClientType.CLIENT_1).subscriptionId()).isNotNull();
    }

    @Test
    void resubscribeRetriesOnTransientFailureThenSucceeds() throws Exception {
        when(haClient.execute(any(Command.class)))
                .thenThrow(new AMPSException("transient nak"))
                .thenReturn(mock(MessageStream.class));

        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));
        manager.resubscribe(ClientType.CLIENT_1);

        verify(haClient, times(2)).execute(any(Command.class));
        assertThat(manager.contextFor(ClientType.CLIENT_1).subscriptionId()).isNotNull();
    }

    @Test
    void subscribeDoesNotRetryOnFailure() throws Exception {
        when(haClient.execute(any(Command.class))).thenThrow(new AMPSException("nak"));

        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));

        assertThatThrownBy(() -> manager.subscribe(ClientType.CLIENT_1)).isInstanceOf(RuntimeException.class);
        verify(haClient, times(1)).execute(any(Command.class));
    }

    @Test
    void exponentialBackoffDelayGrowsAndCapsAtMaxDelay() {
        RetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofMillis(100), Duration.ofSeconds(1), 2.0);

        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofMillis(800));
        assertThat(policy.nextDelay(5)).isEqualTo(Duration.ofSeconds(1)); // capped
        assertThat(policy.nextDelay(50)).isEqualTo(Duration.ofSeconds(1)); // stays capped
    }

    @Test
    void retryPolicyRejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(Duration.ZERO, Duration.ofSeconds(1), 2.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), 2.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(Duration.ofMillis(100), Duration.ofSeconds(1), 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
