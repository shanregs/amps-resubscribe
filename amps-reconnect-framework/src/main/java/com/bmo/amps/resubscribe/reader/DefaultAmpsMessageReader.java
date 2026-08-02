package com.bmo.amps.resubscribe.reader;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;

import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.processor.MessageProcessor;

/**
 * Read-loop shape and start/stop protocol per THREADING_MODEL.md {@literal §}3. The loop only ever
 * calls {@link MessageProcessor} — it never reaches back into {@code AmpsClientManager} or
 * {@code AmpsConnectionListener}.
 */
public class DefaultAmpsMessageReader implements AmpsMessageReader {

    private static final Logger log = LoggerFactory.getLogger(DefaultAmpsMessageReader.class);
    private static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(10);

    private final MessageProcessor messageProcessor;
    private final Duration stopTimeout;

    public DefaultAmpsMessageReader(MessageProcessor messageProcessor) {
        this(messageProcessor, DEFAULT_STOP_TIMEOUT);
    }

    public DefaultAmpsMessageReader(MessageProcessor messageProcessor, Duration stopTimeout) {
        this.messageProcessor = messageProcessor;
        this.stopTimeout = stopTimeout;
    }

    @Override
    public void start(AmpsClientContext context) {
        if (!context.readerRunning().compareAndSet(false, true)) {
            log.debug("{} Reader start ignored, already running", context.clientType());
            return;
        }
        context.readLoopShouldStop().set(false);
        Future<?> task = context.readerExecutor().submit(() -> readLoop(context));
        context.readerTask(task);
        log.info("{} Reader Started", context.clientType());
    }

    @Override
    public void stop(AmpsClientContext context) {
        if (!context.readerRunning().get()) {
            log.debug("{} Reader stop ignored, not running", context.clientType());
            return;
        }

        context.readLoopShouldStop().set(true);
        MessageStream stream = context.messageStream();
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception e) {
                log.debug("{} MessageStream close raised (expected if already disconnected): {}",
                        context.clientType(), e.toString());
            }
        }

        Future<?> task = context.readerTask();
        if (task != null) {
            try {
                task.get(stopTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("{} Reader did not stop within {}, forcing cancel", context.clientType(), stopTimeout);
                task.cancel(true);
            } catch (ExecutionException e) {
                log.warn("{} Reader task ended with an unexpected exception", context.clientType(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        context.readerRunning().set(false);
        log.info("{} Reader Stopped", context.clientType());
    }

    @Override
    public boolean isRunning(AmpsClientContext context) {
        return context.readerRunning().get();
    }

    private void readLoop(AmpsClientContext context) {
        ClientType type = context.clientType();
        // MessageStream implements Iterator<Message>/Iterable<Message> directly (AMPS Java Client
        // 5.3.3.3) — no separate iterator() call needed.
        MessageStream stream = context.messageStream();

        while (!context.readLoopShouldStop().get()) {
            Message message;
            try {
                if (!stream.hasNext()) {
                    log.debug("{} Reader loop exiting, stream ended", type);
                    break;
                }
                message = stream.next();
            } catch (RuntimeException e) {
                // The SDK surfaces a lost connection as a failure from the iterator. This is the
                // expected, non-fatal exit path (THREADING_MODEL.md §3) — the reader never tries to
                // reconnect itself; it just stops and lets AmpsConnectionListener drive recovery.
                log.debug("{} Reader loop exiting, connection lost: {}", type, e.toString());
                break;
            }

            if (message == null) {
                continue;
            }

            try {
                messageProcessor.process(type, message);
                message.ack();
            } catch (Exception e) {
                // A business-logic failure must never kill the reader thread.
                log.error("{} Message processing failed", type, e);
            }
        }
    }
}
