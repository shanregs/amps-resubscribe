package com.bmo.amps.subscriber;

import java.beans.ExceptionListener;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.CommandId;
import com.crankuptheamps.client.ConnectionStateListener;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.ExponentialDelayStrategy;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;

/**
 * Owns one AMPS {@code HAClient} end to end: connects (retrying indefinitely), subscribes to the
 * configured queue, runs a reader thread that hands messages to {@link MessageProcessor}, and
 * recovers automatically after every disconnect — resubscribe, then restart the reader — with no
 * duplicate subscriptions and no duplicate reader threads, ever. {@link AmpsSubscriber} owns one of
 * these per configured client and does nothing else.
 *
 * <p>Read this class top to bottom as one story:
 * <ol>
 *   <li><b>{@link #start()}</b> builds the {@code HAClient}, registers a connection-state listener,
 *       and launches {@link #connectWithRetry()} on its own thread.</li>
 *   <li><b>{@link #onConnectionStateChanged(int)}</b> receives every connection-state event AMPS
 *       reports. It never does real recovery work itself — AMPS forbids issuing commands (like
 *       subscribe) from inside this callback, since the callback runs on the same thread that would
 *       deliver that command's acknowledgement. So it only updates a flag and hands the real work to
 *       {@code recoveryQueue}.</li>
 *   <li><b>{@code recoveryQueue}</b> is a single background thread that runs one recovery task at a
 *       time, strictly in the order the connection-state events happened — so a burst of events (say,
 *       Disconnected then LoggedOn then Disconnected again, on a flapping link) is always handled in
 *       that same order. Every field this connection mutates during recovery (current stream, current
 *       subscription id, reader thread) is written <em>only</em> from inside a task submitted to
 *       {@code recoveryQueue} — one thread owns those writes at all times, so there's no compound
 *       operation to guard with a lock. Each field is still marked {@code volatile} so a caller on any
 *       other thread (e.g. a test polling {@link #isReaderRunning()}) reliably sees the latest value.</li>
 *   <li><b>{@link #resubscribeAndRestartReader()}</b> and <b>{@link #stopReader()}</b> are where the
 *       real work happens: stop the old reader (if any), (re)subscribe, start a fresh reader.</li>
 *   <li><b>{@link #readMessages()}</b> is the reader loop: it hands off messages from the current
 *       {@code MessageStream} until that stream is closed (recovery stopping it) or the connection is
 *       lost (the stream throws).</li>
 * </ol>
 */
final class AmpsClientConnection {

    private static final Logger log = LoggerFactory.getLogger(AmpsClientConnection.class);
    private static final long READER_STOP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long INITIAL_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 15_000;

    private final AmpsSubscribeConfig.ClientDef definition;
    private final MessageProcessor messageProcessor;
    private final Function<AmpsSubscribeConfig.ClientDef, HAClient> haClientFactory;

    // Recovery work (see class javadoc) runs here: one thread, one task at a time, in arrival order.
    private final ExecutorService recoveryQueue;

    // Written only from inside a task submitted to recoveryQueue (including during stop(), which
    // submits its own task rather than touching these directly); volatile so any other thread
    // (e.g. a caller of the accessors below) always observes the latest value.
    private volatile MessageStream currentStream;
    private volatile CommandId currentSubscriptionId;
    private volatile Thread readerThread;
    private volatile boolean readerRunning;

    private volatile boolean shouldRun;
    private volatile boolean loggedOn;
    private volatile HAClient haClient;

    AmpsClientConnection(AmpsSubscribeConfig.ClientDef definition, MessageProcessor messageProcessor,
                          Function<AmpsSubscribeConfig.ClientDef, HAClient> haClientFactory) {
        this.definition = definition;
        this.messageProcessor = messageProcessor;
        this.haClientFactory = haClientFactory;
        this.recoveryQueue = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("amps-recovery-" + definition.name()).factory());
    }

    static HAClient createDefaultHaClient(AmpsSubscribeConfig.ClientDef definition) {
        HAClient client = new HAClient(definition.name());
        DefaultServerChooser serverChooser = new DefaultServerChooser();
        for (String uri : definition.uris()) {
            serverChooser.add(uri);
        }
        client.setServerChooser(serverChooser);
        client.setReconnectDelayStrategy(
                new LoggingDelayStrategy(new ExponentialDelayStrategy(), definition.name()));
        client.setExceptionListener((ExceptionListener) ex ->
                log.warn("{} Connection exception during retry: {}", definition.name(), ex.toString()));
        return client;
    }

    // ---------------------------------------------------------------------------------------------
    // Step 1: start
    // ---------------------------------------------------------------------------------------------

    void start() {
        shouldRun = true;
        haClient = haClientFactory.apply(definition);
        haClient.addConnectionStateListener(this::onConnectionStateChanged);
        Thread.ofVirtual().name("amps-connector-" + definition.name()).start(this::connectWithRetry);
    }

    /**
     * Retries {@code connectAndLogon()} indefinitely, with exponential backoff, until it succeeds or
     * {@link #stop()} is called. This loop exists because {@code connectAndLogon()} only retries
     * across the URIs its {@code ServerChooser} offers — once every configured URI has failed once, it
     * throws for good and never retries again on its own. AMPS's background reconnect machinery only
     * takes over <em>after</em> a first successful connect later drops, so without this loop, a total
     * outage right at startup (e.g. the whole AMPS cluster briefly unreachable) would leave this client
     * stuck disconnected forever instead of recovering once the cluster comes back.
     */
    private void connectWithRetry() {
        long delayMs = INITIAL_RETRY_DELAY_MS;
        while (shouldRun && !loggedOn) {
            try {
                haClient.connectAndLogon();
                return; // success is reported back to us via onConnectionStateChanged(LoggedOn)
            } catch (AMPSException e) {
                log.warn("{} Connect attempt failed, retrying in {}ms: {}",
                        definition.name(), delayMs, e.toString());
                sleepQuietly(delayMs);
                delayMs = Math.min(delayMs * 2, MAX_RETRY_DELAY_MS);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step 2: react to connection-state events
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs on AMPS's own client thread — must return immediately and must never call back into AMPS
     * (subscribe/unsubscribe) directly. That's why the actual recovery work is only ever queued onto
     * {@code recoveryQueue} here, never executed inline.
     */
    private void onConnectionStateChanged(int newState) {
        String clientName = definition.name();
        switch (newState) {
            case ConnectionStateListener.Connected -> log.info("{} Connected", clientName);
            case ConnectionStateListener.LoggedOn -> {
                loggedOn = true;
                log.info("{} LoggedOn", clientName);
                recoveryQueue.submit(this::resubscribeAndRestartReader);
            }
            case ConnectionStateListener.Disconnected -> {
                loggedOn = false;
                log.warn("{} Disconnected", clientName);
                recoveryQueue.submit(this::stopReader);
            }
            case ConnectionStateListener.Shutdown -> {
                loggedOn = false;
                log.warn("{} Shutdown reported by client; connectWithRetry keeps retrying independently",
                        clientName);
                recoveryQueue.submit(this::stopReader);
            }
            case ConnectionStateListener.HeartbeatInitiated -> log.debug("{} Heartbeat initiated", clientName);
            case ConnectionStateListener.Resubscribed -> log.debug("{} Resubscribed", clientName);
            default -> log.debug("{} No handling needed for connection state {}", clientName, newState);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step 3: recovery work — runs exclusively on recoveryQueue's single thread (see field comment)
    // ---------------------------------------------------------------------------------------------

    /**
     * Handles both the very first subscribe and every later resubscribe identically: stop whatever
     * reader is currently running, subscribe fresh, then clean up the previous subscription (if any).
     */
    private void resubscribeAndRestartReader() {
        stopReader();

        String clientName = definition.name();
        CommandId staleSubscriptionId = currentSubscriptionId;
        try {
            currentStream = haClient.execute(new Command("subscribe").setTopic(definition.queue()));
            currentSubscriptionId = CommandId.nextIdentifier();
            log.info("{} Subscribed [subscriptionId={}]", clientName, currentSubscriptionId);
        } catch (AMPSException e) {
            log.error("{} Subscribe failed", clientName, e);
            return;
        }

        if (staleSubscriptionId != null) {
            try {
                haClient.unsubscribe(staleSubscriptionId);
            } catch (AMPSException e) {
                log.debug("{} Unsubscribing stale id {} failed (expected if the connection already "
                        + "dropped it server-side): {}", clientName, staleSubscriptionId, e.toString());
            }
        }

        startReader();
    }

    /**
     * Closes the current stream — which unblocks the reader thread parked inside
     * {@link #readMessages()} — then waits for that thread to fully exit before returning. Waiting
     * for the exit, rather than just signalling it, guarantees {@link #startReader()} can never see a
     * stale reader still running when it starts a new one.
     */
    private void stopReader() {
        if (!readerRunning) {
            return;
        }
        String clientName = definition.name();
        if (currentStream != null) {
            try {
                currentStream.close();
            } catch (RuntimeException e) {
                log.debug("{} MessageStream close raised (expected if already disconnected): {}",
                        clientName, e.toString());
            }
        }
        try {
            readerThread.join(READER_STOP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        readerRunning = false;
        log.info("{} Reader stopped", clientName);
    }

    private void startReader() {
        readerThread = Thread.ofVirtual().name("amps-reader-" + definition.name()).start(this::readMessages);
        readerRunning = true;
        log.info("{} Reader started", definition.name());
    }

    // ---------------------------------------------------------------------------------------------
    // Step 4: read loop
    // ---------------------------------------------------------------------------------------------

    private void readMessages() {
        String clientName = definition.name();
        try {
            for (Message message : currentStream) {
                if (message == null) {
                    continue;
                }
                try {
                    messageProcessor.process(AmpsMessage.from(clientName, message));
                } catch (RuntimeException e) {
                    log.error("{} Failed to hand off message for processing", clientName, e);
                }
            }
            log.debug("{} Reader loop exiting, stream ended", clientName);
        } catch (RuntimeException e) {
            // Connection lost mid-read — expected, not fatal. No self-restart here: the next
            // LoggedOn event drives recovery via resubscribeAndRestartReader().
            log.debug("{} Reader loop exiting, connection lost: {}", clientName, e.toString());
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shutdown
    // ---------------------------------------------------------------------------------------------

    void stop() {
        shouldRun = false;
        // Submitted (not called directly) so the reader-stop still happens on recoveryQueue's thread,
        // the same thread every other mutation of this connection's state happens on — even during
        // shutdown, that state is never touched from two threads at once.
        runOnRecoveryThreadAndWait(this::stopReader);
        try {
            haClient.close();
        } catch (RuntimeException e) {
            log.warn("{} Error during disconnect", definition.name(), e);
        }
        recoveryQueue.shutdownNow();
    }

    private void runOnRecoveryThreadAndWait(Runnable task) {
        try {
            recoveryQueue.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("{} Recovery task failed during shutdown", definition.name(), e.getCause());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Test observability — package-private, not part of the public API
    // ---------------------------------------------------------------------------------------------

    boolean isReaderRunning() {
        return readerRunning;
    }

    CommandId currentSubscriptionId() {
        return currentSubscriptionId;
    }

    Thread readerThread() {
        return readerThread;
    }
}
