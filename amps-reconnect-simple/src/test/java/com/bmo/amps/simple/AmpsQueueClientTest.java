package com.bmo.amps.simple;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.CommandId;
import com.crankuptheamps.client.ConnectionStateListener;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.MessageStream;

/**
 * Exercises {@link AmpsQueueClient} against a mocked {@code HAClient} (injected via the
 * package-private {@code setHaClientFactory} test seam) — no live AMPS server required. Mirrors the
 * guarantees {@code amps-reconnect-framework}'s test suite proves for its split-class design: no
 * duplicate subscriptions, no duplicate/leaked reader threads, clean stop.
 *
 * <p>The real {@code ConnectionStateListener} instance registered by {@code AmpsQueueClient} is
 * captured via Mockito and invoked directly to simulate AMPS connection-state callbacks — the same
 * technique the framework module's tests use, since AMPS itself is not present in this test run.
 */
class AmpsQueueClientTest {

    private static final String CLIENT_NAME = "CLIENT_1";

    private final HAClient haClient = mock(HAClient.class);
    private final MessageProcessor messageProcessor = mock(MessageProcessor.class);
    private final AmpsSubscribeConfig config = new AmpsSubscribeConfig(
            List.of("tcp://localhost:9027/amps/json", "tcp://localhost:9028/amps/json"),
            "orders.queue",
            1);
    private final AmpsQueueClient client = new AmpsQueueClient(config, messageProcessor);

    @BeforeEach
    void wireTestHaClientFactory() {
        client.setHaClientFactory(def -> haClient);
    }

    @AfterEach
    void tearDown() {
        client.stop();
    }

    private ConnectionStateListener captureRegisteredListener() {
        ArgumentCaptor<ConnectionStateListener> captor = ArgumentCaptor.forClass(ConnectionStateListener.class);
        verify(haClient).addConnectionStateListener(captor.capture());
        return captor.getValue();
    }

    /** A MessageStream whose hasNext() blocks (simulating an open subscription) until close() is called. */
    private static MessageStream blockingStream() {
        MessageStream stream = mock(MessageStream.class);
        // The real MessageStream.iterator() returns `this` (AMPS Java Client 5.3.3.3 source) — the
        // mock needs the same self-reference, since AmpsQueueClient.readLoop() reads the stream via
        // a for-each loop, which calls iterator() once up front.
        when(stream.iterator()).thenReturn(stream);
        AtomicBoolean closed = new AtomicBoolean(false);
        when(stream.hasNext()).thenAnswer(invocation -> {
            while (!closed.get()) {
                Thread.sleep(5);
            }
            return false;
        });
        doAnswer(invocation -> {
            closed.set(true);
            return null;
        }).when(stream).close();
        return stream;
    }

    @Test
    void startCreatesHAClientAndConnects() throws Exception {
        when(haClient.execute(any(Command.class))).thenAnswer(invocation -> blockingStream());

        client.start();

        verify(haClient, times(1)).connectAndLogon();
        assertThat(client.isRunning()).isTrue();
    }

    @Test
    void firstLoggedOnSubscribesAndStartsReader() throws Exception {
        when(haClient.execute(any(Command.class))).thenAnswer(invocation -> blockingStream());
        client.start();
        ConnectionStateListener listener = captureRegisteredListener();

        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isTrue());
        assertThat(client.subscriptionIdFor(CLIENT_NAME)).isNotNull();
        verify(haClient, times(1)).execute(any(Command.class));
    }

    @Test
    void disconnectStopsTheReader() throws Exception {
        when(haClient.execute(any(Command.class))).thenAnswer(invocation -> blockingStream());
        client.start();
        ConnectionStateListener listener = captureRegisteredListener();
        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isTrue());

        listener.connectionStateChanged(ConnectionStateListener.Disconnected);

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isFalse());
    }

    @Test
    void reconnectResubscribesWithNewIdAndUnsubscribesTheStaleOne() throws Exception {
        when(haClient.execute(any(Command.class))).thenAnswer(invocation -> blockingStream());
        client.start();
        ConnectionStateListener listener = captureRegisteredListener();

        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isTrue());
        CommandId firstId = client.subscriptionIdFor(CLIENT_NAME);

        listener.connectionStateChanged(ConnectionStateListener.Disconnected);
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isFalse());

        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isTrue());
        CommandId secondId = client.subscriptionIdFor(CLIENT_NAME);

        assertThat(secondId).isNotNull().isNotEqualTo(firstId);
        verify(haClient, times(1)).unsubscribe(firstId);
        verify(haClient, times(2)).execute(any(Command.class));
    }

    @Test
    void concurrentLoggedOnCallbacksProduceExactlyOneSubscribe() throws Exception {
        when(haClient.execute(any(Command.class))).thenAnswer(invocation -> blockingStream());
        client.start();
        ConnectionStateListener listener = captureRegisteredListener();

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
                        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
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
                .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isTrue());

        // Only one of the N racing callbacks wins the "recovering" CAS guard, so exactly one
        // subscribe command is ever issued for this batch of concurrent LoggedOn callbacks.
        verify(haClient, times(1)).execute(any(Command.class));
    }

    @Test
    void manyReconnectCyclesLeaveNoLingeringReaderThreads() throws Exception {
        // Note: Thread.getAllStackTraces() does NOT enumerate virtual threads (JEP 425) — thread
        // presence is instead verified by capturing each cycle's Thread reference directly via the
        // readerThreadFor(...) test accessor and checking isAlive()/identity.
        when(haClient.execute(any(Command.class))).thenAnswer(invocation -> blockingStream());
        client.start();
        ConnectionStateListener listener = captureRegisteredListener();

        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isTrue());
        Thread previousReader = client.readerThreadFor(CLIENT_NAME);
        assertThat(previousReader.isAlive()).isTrue();

        for (int i = 0; i < 50; i++) {
            listener.connectionStateChanged(ConnectionStateListener.Disconnected);
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isFalse());
            // stopReaderLocked() only flips running to false AFTER join() confirms the thread dead.
            assertThat(previousReader.isAlive()).isFalse();

            listener.connectionStateChanged(ConnectionStateListener.LoggedOn);
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isTrue());
            Thread currentReader = client.readerThreadFor(CLIENT_NAME);

            assertThat(currentReader).isNotSameAs(previousReader);
            assertThat(currentReader.isAlive()).isTrue();
            previousReader = currentReader;
        }
    }

    @Test
    void stopStopsTheReaderAndClosesTheHAClient() throws Exception {
        when(haClient.execute(any(Command.class))).thenAnswer(invocation -> blockingStream());
        client.start();
        ConnectionStateListener listener = captureRegisteredListener();
        listener.connectionStateChanged(ConnectionStateListener.LoggedOn);
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(client.isReaderRunning(CLIENT_NAME)).isTrue());

        client.stop();

        assertThat(client.isReaderRunning(CLIENT_NAME)).isFalse();
        assertThat(client.isRunning()).isFalse();
        verify(haClient, times(1)).close();
    }
}
