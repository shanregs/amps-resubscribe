package com.bmo.amps.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.crankuptheamps.client.ConnectionStateListener;
import com.crankuptheamps.client.HAClient;

/**
 * Exercises {@link AmpsPublisher} against a mocked {@code HAClient} (injected via the package-private
 * {@code setHaClientFactory} test seam) — no live AMPS server required. Mirrors the technique used by
 * {@code amps-reconnect-simple}'s {@code AmpsQueueClientTest}.
 */
class AmpsPublisherTest {

    private final HAClient haClient = mock(HAClient.class);
    private final OrderPayloadGenerator payloadGenerator = new OrderPayloadGenerator();
    private AmpsPublisher publisher;

    @BeforeEach
    void wireTestHaClientFactory() {
        AmpsPublishConfig config = new AmpsPublishConfig(
                "TEST_PUBLISHER",
                List.of("tcp://localhost:9107/amps/json"),
                List.of(new AmpsPublishConfig.Target("orders.queue", 1000, 1000)));
        publisher = new AmpsPublisher(config, payloadGenerator);
        publisher.setHaClientFactory(() -> haClient);
    }

    @AfterEach
    void tearDown() {
        publisher.stop();
    }

    private ConnectionStateListener captureRegisteredListener() {
        ArgumentCaptor<ConnectionStateListener> captor = ArgumentCaptor.forClass(ConnectionStateListener.class);
        verify(haClient).addConnectionStateListener(captor.capture());
        return captor.getValue();
    }

    @Test
    void startConnectsAndLaunchesOnePublisherThreadPerTarget() throws Exception {
        publisher.start();

        verify(haClient, times(1)).connectAndLogon();
        assertThat(publisher.isRunning()).isTrue();
        assertThat(publisher.publisherThreads()).hasSize(1);
    }

    @Test
    void publishingIsPausedUntilLoggedOn() throws Exception {
        publisher.start();

        // No LoggedOn callback fired yet — publish loop must not have called haClient.publish().
        Thread.sleep(150);
        verify(haClient, never()).publish(anyString(), anyString());

        ConnectionStateListener listener = captureRegisteredListener();
        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);

        verify(haClient, timeout(2000).atLeastOnce()).publish(anyString(), anyString());
    }

    @Test
    void disconnectPausesPublishingAgain() throws Exception {
        publisher.start();
        ConnectionStateListener listener = captureRegisteredListener();
        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);
        await().atMost(Duration.ofSeconds(2)).until(publisher::isLoggedOn);

        listener.connectionStateChanged(ConnectionStateListener.Disconnected);

        await().atMost(Duration.ofSeconds(2)).until(() -> !publisher.isLoggedOn());
    }

    @Test
    void stopStopsThreadsAndClosesHaClient() throws Exception {
        publisher.start();
        ConnectionStateListener listener = captureRegisteredListener();
        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);
        verify(haClient, timeout(2000).atLeastOnce()).publish(anyString(), anyString());
        List<Thread> threadsBeforeStop = publisher.publisherThreads();

        publisher.stop();

        assertThat(publisher.isRunning()).isFalse();
        verify(haClient, times(1)).close();
        assertThat(threadsBeforeStop).allMatch(t -> !t.isAlive());
    }
}
