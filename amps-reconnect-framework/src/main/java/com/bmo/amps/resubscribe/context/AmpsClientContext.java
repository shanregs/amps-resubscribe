package com.bmo.amps.resubscribe.context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.CommandId;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.MessageStream;

import com.bmo.amps.resubscribe.config.AmpsClientDefinition;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.model.ConnectionState;
import com.bmo.amps.resubscribe.util.RetryPolicy;

/**
 * Per-client mutable runtime state — the single source of truth every other component reads and
 * writes through (DESIGN.md {@literal §}2, {@literal §}3.2). No field is ever assigned outside this
 * class's own methods.
 * <p>
 * One instance is created per configured {@link ClientType} by {@code AmpsClientManager.initialize()}
 * and lives for the entire process lifetime.
 */
public final class AmpsClientContext {

    private static final Logger log = LoggerFactory.getLogger(AmpsClientContext.class);

    private final ClientType clientType;
    private final HAClient haClient;
    private final AmpsClientDefinition definition;
    private final RetryPolicy retryPolicy;
    private final ExecutorService readerExecutor;
    private final ExecutorService recoveryExecutor;
    private final ReentrantLock recoveryLock = new ReentrantLock();
    private final AtomicBoolean readerRunning = new AtomicBoolean(false);
    private final AtomicBoolean readLoopShouldStop = new AtomicBoolean(false);
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.CREATED);

    private volatile CommandId subscriptionId;
    private volatile MessageStream messageStream;
    private volatile Future<?> readerTask;

    public AmpsClientContext(
            ClientType clientType,
            HAClient haClient,
            AmpsClientDefinition definition,
            RetryPolicy retryPolicy,
            ExecutorService readerExecutor,
            ExecutorService recoveryExecutor
    ) {
        this.clientType = clientType;
        this.haClient = haClient;
        this.definition = definition;
        this.retryPolicy = retryPolicy;
        this.readerExecutor = readerExecutor;
        this.recoveryExecutor = recoveryExecutor;
    }

    public ClientType clientType() {
        return clientType;
    }

    public HAClient haClient() {
        return haClient;
    }

    public AmpsClientDefinition definition() {
        return definition;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public ExecutorService readerExecutor() {
        return readerExecutor;
    }

    public ExecutorService recoveryExecutor() {
        return recoveryExecutor;
    }

    public ReentrantLock recoveryLock() {
        return recoveryLock;
    }

    public ConnectionState state() {
        return state.get();
    }

    /**
     * Guarded transition: succeeds only if the current state equals {@code expectedFrom}. A mismatch
     * (e.g. a duplicate/late AMPS callback) is a no-op logged at DEBUG rather than an error — this is
     * what makes duplicate callbacks harmless (DESIGN.md {@literal §}3.2, {@literal §}5 rule 2).
     */
    public boolean transition(ConnectionState expectedFrom, ConnectionState to) {
        boolean success = state.compareAndSet(expectedFrom, to);
        if (success) {
            log.info("{} {} -> {}", clientType, expectedFrom, to);
        } else {
            log.debug("{} transition {} -> {} ignored, actual state is {}", clientType, expectedFrom, to, state.get());
        }
        return success;
    }

    /**
     * Unconditional transition used only for shutdown, which must proceed regardless of whatever
     * state a client happens to be in (DESIGN.md {@literal §}3.1 footnote).
     */
    public void forceState(ConnectionState to) {
        ConnectionState previous = state.getAndSet(to);
        log.info("{} {} -> {} (forced)", clientType, previous, to);
    }

    public CommandId subscriptionId() {
        return subscriptionId;
    }

    public void subscriptionId(CommandId subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public MessageStream messageStream() {
        return messageStream;
    }

    public void messageStream(MessageStream messageStream) {
        this.messageStream = messageStream;
    }

    public AtomicBoolean readerRunning() {
        return readerRunning;
    }

    public AtomicBoolean readLoopShouldStop() {
        return readLoopShouldStop;
    }

    public Future<?> readerTask() {
        return readerTask;
    }

    public void readerTask(Future<?> readerTask) {
        this.readerTask = readerTask;
    }
}
