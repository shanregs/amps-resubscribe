package com.bmo.amps.resubscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.MessageStream;

import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.manager.AmpsClientManager;
import com.bmo.amps.resubscribe.manager.DefaultAmpsClientManager;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.model.ConnectionState;
import com.bmo.amps.resubscribe.support.TestContextFactory;

/** DESIGN.md startup call flow, CALL_FLOW.md §1. See TEST_PLAN.md §1. */
class StartupTests {

    private final HAClient haClient = mock(HAClient.class);
    private final AmpsClientManager manager = new DefaultAmpsClientManager(definition -> haClient);

    @AfterEach
    void cleanup() {
        for (AmpsClientContext context : manager.allContexts()) {
            TestContextFactory.shutdown(context);
        }
    }

    @Test
    void createsOneContextPerConfiguredClient() {
        manager.initialize(List.of(
                TestContextFactory.definition(ClientType.CLIENT_1),
                TestContextFactory.definition(ClientType.CLIENT_2)));

        assertThat(manager.allContexts()).hasSize(2);
        assertThat(manager.contextFor(ClientType.CLIENT_1).clientType()).isEqualTo(ClientType.CLIENT_1);
        assertThat(manager.contextFor(ClientType.CLIENT_2).clientType()).isEqualTo(ClientType.CLIENT_2);
    }

    @Test
    void everyContextStartsInCreatedState() {
        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));

        assertThat(manager.contextFor(ClientType.CLIENT_1).state()).isEqualTo(ConnectionState.CREATED);
    }

    @Test
    void eachClientGetsIndependentReaderAndRecoveryExecutors() {
        manager.initialize(List.of(
                TestContextFactory.definition(ClientType.CLIENT_1),
                TestContextFactory.definition(ClientType.CLIENT_2)));

        AmpsClientContext client1 = manager.contextFor(ClientType.CLIENT_1);
        AmpsClientContext client2 = manager.contextFor(ClientType.CLIENT_2);

        assertThat(client1.readerExecutor()).isNotSameAs(client1.recoveryExecutor());
        assertThat(client1.readerExecutor()).isNotSameAs(client2.readerExecutor());
        assertThat(client1.recoveryExecutor()).isNotSameAs(client2.recoveryExecutor());
    }

    @Test
    void connectTransitionsCreatedToConnectingAndInvokesConnectAndLogon() throws Exception {
        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));

        manager.connect(ClientType.CLIENT_1);

        assertThat(manager.contextFor(ClientType.CLIENT_1).state()).isEqualTo(ConnectionState.CONNECTING);
        verify(haClient, times(1)).connectAndLogon();
    }

    @Test
    void connectIsNoOpIfNotInCreatedState() throws Exception {
        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));
        manager.connect(ClientType.CLIENT_1);

        manager.connect(ClientType.CLIENT_1);

        // Second call finds state already CONNECTING (not CREATED) and must not invoke connectAndLogon again.
        verify(haClient, times(1)).connectAndLogon();
    }

    @Test
    void subscribeCalledExactlyOnceAtStartup() throws Exception {
        MessageStream stream = mock(MessageStream.class);
        when(haClient.execute(any(Command.class))).thenReturn(stream);

        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));
        manager.subscribe(ClientType.CLIENT_1);
        manager.subscribe(ClientType.CLIENT_1); // duplicate call, must no-op (DESIGN.md §5 rule 1)

        verify(haClient, times(1)).execute(any(Command.class));
    }

    @Test
    void subscribeSuccessRecordsSubscriptionIdAndMessageStream() throws Exception {
        MessageStream stream = mock(MessageStream.class);
        when(haClient.execute(any(Command.class))).thenReturn(stream);

        manager.initialize(List.of(TestContextFactory.definition(ClientType.CLIENT_1)));
        AmpsClientContext context = manager.contextFor(ClientType.CLIENT_1);
        context.transition(ConnectionState.CREATED, ConnectionState.CONNECTING);
        context.transition(ConnectionState.CONNECTING, ConnectionState.CONNECTED);
        context.transition(ConnectionState.CONNECTED, ConnectionState.LOGGED_ON);

        manager.subscribe(ClientType.CLIENT_1);

        assertThat(context.subscriptionId()).isNotNull();
        // MessageStream implements both Iterator<Message> and Iterable<Message>, which makes the
        // Iterator/Iterable assertThat(...) overloads ambiguous — disambiguate with an Object cast.
        assertThat((Object) context.messageStream()).isSameAs(stream);
        assertThat(context.state()).isEqualTo(ConnectionState.SUBSCRIBED);
    }

    @Test
    void startupIsIndependentPerClient() {
        manager.initialize(List.of(
                TestContextFactory.definition(ClientType.CLIENT_1),
                TestContextFactory.definition(ClientType.CLIENT_2)));

        AmpsClientContext client1 = manager.contextFor(ClientType.CLIENT_1);
        AmpsClientContext client2 = manager.contextFor(ClientType.CLIENT_2);

        client1.transition(ConnectionState.CREATED, ConnectionState.CONNECTING);

        assertThat(client1.state()).isEqualTo(ConnectionState.CONNECTING);
        assertThat(client2.state()).isEqualTo(ConnectionState.CREATED);
    }
}
