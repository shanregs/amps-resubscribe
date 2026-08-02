package com.bmo.amps.resubscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;

import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.processor.MessageProcessor;
import com.bmo.amps.resubscribe.reader.AmpsMessageReader;
import com.bmo.amps.resubscribe.reader.DefaultAmpsMessageReader;
import com.bmo.amps.resubscribe.support.TestContextFactory;

/**
 * THREADING_MODEL.md §3 start/stop protocol and read-loop shape. See TEST_PLAN.md §4.
 *
 * <p>{@code MessageStream} implements {@code Iterator<Message>} directly (verified against the AMPS
 * Java Client 5.3.3.3 sources) — tests stub {@code hasNext()}/{@code next()} on the stream mock
 * itself rather than a separate {@code Iterator}.
 */
class ReaderRestartTests {

    private final MessageProcessor processor = mock(MessageProcessor.class);
    private final AmpsMessageReader reader = new DefaultAmpsMessageReader(processor);
    private final AmpsClientContext context = TestContextFactory.newContext(ClientType.CLIENT_1);

    @AfterEach
    void cleanup() {
        TestContextFactory.shutdown(context);
    }

    @Test
    void startIsNoOpIfAlreadyRunning() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        CountDownLatch entered = new CountDownLatch(1);
        MessageStream stream = mock(MessageStream.class);
        when(stream.hasNext()).thenAnswer(invocation -> {
            entered.countDown();
            while (!closed.get()) {
                Thread.sleep(10);
            }
            return false;
        });
        context.messageStream(stream);

        reader.start(context);
        reader.start(context); // second call must be a no-op

        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        // Only one reader task was ever submitted, so hasNext() is polled by exactly one thread.
        Future<?> task = context.readerTask();

        closed.set(true);
        reader.stop(context);
        assertThat(task.isDone()).isTrue();
    }

    @Test
    void stopBlocksUntilThreadTerminated() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        CountDownLatch entered = new CountDownLatch(1);
        MessageStream stream = mock(MessageStream.class);
        when(stream.hasNext()).thenAnswer(invocation -> {
            entered.countDown();
            while (!closed.get()) {
                Thread.sleep(10);
            }
            return false;
        });
        doAnswer(invocation -> {
            closed.set(true);
            return null;
        }).when(stream).close();
        context.messageStream(stream);

        reader.start(context);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        Future<?> task = context.readerTask();
        reader.stop(context);

        assertThat(task.isDone()).isTrue();
        assertThat(context.readerRunning().get()).isFalse();
    }

    @Test
    void stopForcesCancelAfterTimeoutIfLoopIgnoresClose() throws Exception {
        AmpsMessageReader shortTimeoutReader = new DefaultAmpsMessageReader(processor, Duration.ofMillis(150));
        CountDownLatch entered = new CountDownLatch(1);
        MessageStream stream = mock(MessageStream.class);
        when(stream.hasNext()).thenAnswer(invocation -> {
            entered.countDown();
            // Never reacts to close() or interruption promptly, forcing the timeout path.
            //noinspection InfiniteLoopStatement
            while (true) {
                Thread.sleep(5000);
            }
        });
        context.messageStream(stream);

        shortTimeoutReader.start(context);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        shortTimeoutReader.stop(context);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000);
        assertThat(context.readerRunning().get()).isFalse();
    }

    @Test
    void restartAfterStopSubmitsNewTaskOnSameExecutor() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        MessageStream firstStream = mock(MessageStream.class);
        when(firstStream.hasNext()).thenAnswer(invocation -> {
            while (!closed.get()) {
                Thread.sleep(10);
            }
            return false;
        });
        doAnswer(inv -> {
            closed.set(true);
            return null;
        }).when(firstStream).close();
        context.messageStream(firstStream);

        reader.start(context);
        Future<?> firstTask = context.readerTask();
        reader.stop(context);

        MessageStream secondStream = mock(MessageStream.class);
        when(secondStream.hasNext()).thenReturn(false);
        context.messageStream(secondStream);

        var readerExecutorBefore = context.readerExecutor();
        reader.start(context);
        Future<?> secondTask = context.readerTask();

        assertThat(context.readerExecutor()).isSameAs(readerExecutorBefore);
        assertThat(secondTask).isNotSameAs(firstTask);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(secondStream, times(1)).hasNext());
    }

    @Test
    void businessExceptionDoesNotStopTheLoop() throws Exception {
        Message message1 = mock(Message.class);
        Message message2 = mock(Message.class);
        MessageStream stream = mock(MessageStream.class);
        when(stream.hasNext()).thenReturn(true, true, false);
        when(stream.next()).thenReturn(message1, message2);
        context.messageStream(stream);

        doThrow(new RuntimeException("business bug")).when(processor).process(eq(ClientType.CLIENT_1), eq(message1));

        reader.start(context);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            verify(processor, times(1)).process(ClientType.CLIENT_1, message1);
            verify(processor, times(1)).process(ClientType.CLIENT_1, message2);
        });
        // message1's ack() must be skipped since processing failed; message2 acked normally.
        verify(message1, times(0)).ack();
        verify(message2, times(1)).ack();
    }

    @Test
    void connectionExceptionEndsLoopWithoutThrowingAndWithoutSelfRestart() throws Exception {
        MessageStream stream = mock(MessageStream.class);
        when(stream.hasNext()).thenThrow(new RuntimeException("connection lost"));
        context.messageStream(stream);

        reader.start(context);
        Future<?> task = context.readerTask();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(task.isDone()).isTrue());
        org.assertj.core.api.Assertions.assertThatCode(task::get).doesNotThrowAnyException();
        // Per THREADING_MODEL.md §3, readerRunning is only ever flipped by stop() — the loop exiting
        // on its own does not, by design, self-report as stopped.
        assertThat(context.readerRunning().get()).isTrue();

        reader.stop(context); // Listener always calls stop() as part of recovery regardless.
        assertThat(context.readerRunning().get()).isFalse();
    }
}
