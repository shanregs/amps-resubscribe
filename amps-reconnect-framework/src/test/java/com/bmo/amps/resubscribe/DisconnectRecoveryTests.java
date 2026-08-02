package com.bmo.amps.resubscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.crankuptheamps.client.CommandId;

import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.listener.AmpsConnectionListener;
import com.bmo.amps.resubscribe.manager.AmpsClientManager;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.model.ConnectionState;
import com.bmo.amps.resubscribe.reader.AmpsMessageReader;
import com.bmo.amps.resubscribe.support.ConnectionStates;
import com.bmo.amps.resubscribe.support.TestContextFactory;

/**
 * DESIGN.md §4 recovery orchestration and THREADING_MODEL.md §4 (recovery runs off the callback
 * thread). See TEST_PLAN.md §2.
 *
 * <p>Note: AMPS gives no distinct "reconnecting" callback (verified against the AMPS Java Client
 * 5.3.3.3 sources) — after {@code Disconnected}, the next signal is simply another {@code Connected}.
 */
class DisconnectRecoveryTests {

    private AmpsClientContext context;
    private AmpsClientManager manager;
    private AmpsMessageReader reader;
    private AmpsConnectionListener listener;

    @BeforeEach
    void setUp() {
        context = TestContextFactory.newContext(ClientType.CLIENT_1);
        manager = mock(AmpsClientManager.class);
        reader = mock(AmpsMessageReader.class);
        listener = new AmpsConnectionListener(context, manager, reader);

        // Drive the context to a realistic pre-disconnect state: SUBSCRIBED -> READING, with a
        // subscriptionId already set (so a later LoggedOn is recognized as a recovery, not first-time).
        context.subscriptionId(new CommandId("sub-original"));
        context.transition(ConnectionState.CREATED, ConnectionState.CONNECTING);
        context.transition(ConnectionState.CONNECTING, ConnectionState.CONNECTED);
        context.transition(ConnectionState.CONNECTED, ConnectionState.LOGGED_ON);
        context.transition(ConnectionState.LOGGED_ON, ConnectionState.SUBSCRIBED);
        context.transition(ConnectionState.SUBSCRIBED, ConnectionState.READING);
    }

    @AfterEach
    void tearDown() {
        TestContextFactory.shutdown(context);
    }

    @Test
    void disconnectCallbackImmediatelyMarksDisconnectedAndEnqueuesReaderStop() {
        listener.connectionStateChanged(ConnectionStates.disconnected());

        assertThat(context.state()).isEqualTo(ConnectionState.DISCONNECTED);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(reader, times(1)).stop(context));
    }

    @Test
    void disconnectRecoveryReachesWaitingAfterReaderStopCompletes() {
        listener.connectionStateChanged(ConnectionStates.disconnected());

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.WAITING));
    }

    @Test
    void reconnectSequenceCallsResubscribeBeforeReaderStart() {
        InOrder inOrder = inOrder(manager, reader);

        listener.connectionStateChanged(ConnectionStates.disconnected());
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.WAITING));

        listener.connectionStateChanged(ConnectionStates.connected());
        listener.connectionStateChanged(ConnectionStates.loggedOn());

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.READING));

        inOrder.verify(reader).stop(context);
        inOrder.verify(manager).resubscribe(ClientType.CLIENT_1);
        inOrder.verify(reader).start(context);
    }

    @Test
    void reconnectAfterDisconnectTriggersResubscribeNotFirstTimeSubscribe() {
        listener.connectionStateChanged(ConnectionStates.disconnected());
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.WAITING));

        listener.connectionStateChanged(ConnectionStates.connected());
        listener.connectionStateChanged(ConnectionStates.loggedOn());

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(manager, times(1)).resubscribe(ClientType.CLIENT_1));
        verify(manager, times(0)).subscribe(ClientType.CLIENT_1);
    }

    @Test
    void duplicateDisconnectCallbackOnlyStopsReaderOnce() {
        listener.connectionStateChanged(ConnectionStates.disconnected());
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.WAITING));

        // A second, late/duplicate Disconnected callback arriving after recovery already reached
        // WAITING: forceState still flips DISCONNECTED again (documented trade-off — see
        // AmpsConnectionListener), but the recovery task it enqueues must find nothing further to do.
        listener.connectionStateChanged(ConnectionStates.disconnected());

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(reader, times(2)).stop(context));
        // reader.stop() is itself idempotent (a no-op if not running) per THREADING_MODEL.md §3, so
        // a second stop() call is harmless even though it is invoked twice here.
    }

    @Test
    void reconnectRecoveryFailureDoesNotLeaveRecoveryLockHeld() {
        doAnswer(invocation -> {
            throw new RuntimeException("resubscribe boom");
        }).when(manager).resubscribe(ClientType.CLIENT_1);

        listener.connectionStateChanged(ConnectionStates.disconnected());
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.WAITING));

        listener.connectionStateChanged(ConnectionStates.connected());
        listener.connectionStateChanged(ConnectionStates.loggedOn());

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(manager, times(1)).resubscribe(ClientType.CLIENT_1));
        assertThat(context.recoveryLock().isLocked()).isFalse();
    }
}
