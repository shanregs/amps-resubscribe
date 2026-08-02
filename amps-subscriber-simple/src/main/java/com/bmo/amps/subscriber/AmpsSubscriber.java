package com.bmo.amps.subscriber;

import java.beans.ExceptionListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.CommandId;
import com.crankuptheamps.client.ConnectionStateListener;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.ExponentialDelayStrategy;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.ReconnectDelayStrategy;
import com.crankuptheamps.client.exception.AMPSException;

/**
 * Creates the configured HAClients, listens for every AMPS connection-state event, and after any
 * disconnect resubscribes and restarts each client's reader loop so queue consumption resumes
 * automatically — never leaving two readers or two live subscriptions for the same client at once.
 * Deliberately a single class (see project's simplified-module goal) — contrast with the
 * {@code amps-reconnect-framework} module, which splits this same responsibility across ~10
 * SOLID-oriented classes.
 *
 * <p><b>Why one listener callback per client, not one shared listener:</b> AMPS's
 * {@code ConnectionStateListener.connectionStateChanged(int)} carries no client identity, so each
 * {@code HAClient} is registered with its own lambda capturing its own name — never register the
 * same listener instance across multiple clients.
 *
 * <p><b>Why recovery work is submitted to a single-thread executor instead of run inline or on an ad
 * hoc thread per event:</b> AMPS forbids submitting commands (like our resubscribe) from inside the
 * connection-state callback — that callback runs on the client's receive thread, which is also what
 * would deliver the command's ack, so calling it inline risks deadlock. A single-thread
 * {@link ExecutorService} per client gets recovery work off that thread while also guaranteeing
 * submitted tasks run strictly in submission order — so a burst of events (e.g. Disconnected, then
 * LoggedOn, then Disconnected again during a flapping link) is always handled in the order AMPS
 * raised them, with no risk of a later event's recovery finishing before an earlier one's.
 *
 * <p><b>Why there's a {@code connectWithRetry()} loop:</b> {@link HAClient#connectAndLogon()} only
 * retries across the servers its {@link com.crankuptheamps.client.ServerChooser} offers; once
 * {@link DefaultServerChooser} has cycled through every configured URI without success, it returns an
 * empty URI and {@code connectAndLogon()} throws — permanently, for that call. AMPS's own background
 * reconnect machinery only takes over <i>after</i> an initial connect has succeeded and later drops;
 * it never activates if the very first {@code connectAndLogon()} never got through (e.g. the whole
 * cluster being briefly unreachable at startup). So each client's initial connect runs through its own
 * indefinite, exponentially backed-off retry loop on a dedicated virtual thread, decoupled from
 * Spring's {@link SmartLifecycle} startup phase.
 *
 * <p><b>Thread inventory (no more, no fewer):</b> one virtual connector thread per client (exits once
 * connected); one single-thread virtual executor per client for recovery work (idle between events);
 * one virtual reader thread per client while connected, iterating its {@link MessageStream}; one ad
 * hoc virtual thread per inbound message (see {@link MessageProcessor}); plus AMPS's own internal
 * per-{@code HAClient} socket thread, which this class never creates or touches directly.
 */
@Component
public class AmpsSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AmpsSubscriber.class);
    private static final long READER_JOIN_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long CONNECT_RETRY_INITIAL_DELAY_MS = 1000;
    private static final long CONNECT_RETRY_MAX_DELAY_MS = 15_000;

    private final AmpsSubscribeConfig config;
    private final MessageProcessor messageProcessor;
    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    // Package-private test seam (default: real AMPS HAClient) — see AmpsSubscriberTest. Kept as a
    // mutable field rather than a constructor parameter so Spring sees exactly one constructor.
    private Function<AmpsSubscribeConfig.ClientDef, HAClient> haClientFactory = AmpsSubscriber::createDefaultHaClient;

    public AmpsSubscriber(AmpsSubscribeConfig config, MessageProcessor messageProcessor) {
        this.config = config;
        this.messageProcessor = messageProcessor;
    }

    void setHaClientFactory(Function<AmpsSubscribeConfig.ClientDef, HAClient> haClientFactory) {
        this.haClientFactory = haClientFactory;
    }

    private static HAClient createDefaultHaClient(AmpsSubscribeConfig.ClientDef def) {
        HAClient haClient = new HAClient(def.name());
        DefaultServerChooser serverChooser = new DefaultServerChooser();
        for (String uri : def.uris()) {
            serverChooser.add(uri);
        }
        haClient.setServerChooser(serverChooser);
        haClient.setReconnectDelayStrategy(
                new LoggingDelayStrategy(new ExponentialDelayStrategy(), def.name()));
        haClient.setExceptionListener((ExceptionListener) ex ->
                log.warn("{} Connection exception during retry: {}", def.name(), ex.toString()));
        return haClient;
    }

    /**
     * Wraps AMPS's {@code ExponentialDelayStrategy} purely for observability. Note that
     * {@code getConnectWaitDuration()} is called on every connection attempt, including the very
     * first one on a fresh {@code connectAndLogon()} — not just retries after a disconnect — so the
     * first call (attempt #1) is labeled as the initial connect rather than a "reconnect" to avoid
     * misleading logs on normal startup.
     */
    private static final class LoggingDelayStrategy implements ReconnectDelayStrategy {
        private final ReconnectDelayStrategy delegate;
        private final String clientName;
        private final AtomicInteger attempt = new AtomicInteger(0);

        LoggingDelayStrategy(ReconnectDelayStrategy delegate, String clientName) {
            this.delegate = delegate;
            this.clientName = clientName;
        }

        @Override
        public int getConnectWaitDuration(String uri) {
            int delay = 0;
            try {
                delay = delegate.getConnectWaitDuration(uri);
            } catch (Exception e) {
                log.error("{} Error getting connect wait duration", clientName, e);
                throw new RuntimeException(e);
            }
            int attemptNumber = attempt.incrementAndGet();
            String label = attemptNumber == 1 ? "Initial connect attempt" : "Reconnect attempt #" + attemptNumber;
            log.warn("{} {} to {} scheduled after {}ms delay", clientName, label, uri, delay);
            return delay;
        }

        @Override
        public void reset() {
            int previousAttempts = attempt.getAndSet(0);
            if (previousAttempts > 1) {
                log.info("{} Reconnect succeeded after {} attempt(s), counter reset",
                        clientName, previousAttempts);
            }
            delegate.reset();
        }
    }

    @Override
    public void start() {
        running = true;
        for (AmpsSubscribeConfig.ClientDef def : config.clients()) {
            ClientState state = new ClientState(def);
            clients.put(def.name(), state);

            state.haClient = haClientFactory.apply(def);
            state.haClient.addConnectionStateListener(
                    newState -> onConnectionStateChanged(def.name(), newState));

            Thread.ofVirtual().name("amps-connector-" + def.name())
                    .start(() -> connectWithRetry(state));
        }
    }

    /**
     * Keeps calling {@link HAClient#connectAndLogon()} for this client until it succeeds or the
     * subscriber is stopped. See the class javadoc for why {@code connectAndLogon()} alone isn't
     * enough to recover from a total outage at startup.
     */
    private void connectWithRetry(ClientState state) {
        long delay = CONNECT_RETRY_INITIAL_DELAY_MS;
        while (running && !state.loggedOn.get()) {
            try {
                state.haClient.connectAndLogon();
                return; // success — the LoggedOn event (below) drives resubscribe/reader start
            } catch (AMPSException e) {
                log.warn("{} Connect attempt failed, retrying in {}ms: {}",
                        state.definition.name(), delay, e.toString());
                sleepQuietly(delay);
                delay = Math.min(delay * 2, CONNECT_RETRY_MAX_DELAY_MS);
            }
        }
    }

    /**
     * Runs on AMPS's client receive thread — must return immediately. The flag write is safe to do
     * here directly (no ordering concerns for a plain boolean); the actual subscribe/reader work is
     * submitted to {@code state.recoveryExecutor} so it runs off this thread, strictly in the order
     * events arrive.
     */
    private void onConnectionStateChanged(String clientName, int newState) {
        ClientState state = clients.get(clientName);
        if (state == null) {
            return;
        }
        switch (newState) {
            case ConnectionStateListener.Connected ->
                    log.info("{} Connected", clientName);
            case ConnectionStateListener.LoggedOn -> {
                state.loggedOn.set(true);
                log.info("{} LoggedOn", clientName);
                state.recoveryExecutor.submit(() -> resubscribeAndRestartReader(state));
            }
            case ConnectionStateListener.Disconnected -> {
                state.loggedOn.set(false);
                log.warn("{} Disconnected", clientName);
                state.recoveryExecutor.submit(() -> stopReader(state));
            }
            case ConnectionStateListener.Shutdown -> {
                state.loggedOn.set(false);
                log.warn("{} Shutdown reported by client; connectWithRetry loop will keep "
                        + "retrying independently", clientName);
                state.recoveryExecutor.submit(() -> stopReader(state));
            }
            case ConnectionStateListener.HeartbeatInitiated ->
                    log.debug("{} Heartbeat initiated", clientName);
            case ConnectionStateListener.Resubscribed ->
                    log.debug("{} Resubscribed", clientName);
            default -> log.debug("{} No handling needed for connection state {}", clientName, newState);
        }
    }

    /**
     * Handles both the very first subscribe (staleId is null) and every reconnect (staleId is the
     * previous subscription) identically — resubscribe is just "subscribe fresh, then clean up
     * whatever was there before, if anything". Always runs on {@code state.recoveryExecutor}'s single
     * thread, so calling {@code execute()}/{@code unsubscribe()} here is safe.
     */
    private void resubscribeAndRestartReader(ClientState state) {
        state.lock.lock();
        try {
            stopReaderLocked(state);

            CommandId staleId = state.subscriptionId;
            CommandId newId = CommandId.nextIdentifier();
            try {
                Command command = new Command("subscribe").setTopic(state.definition.queue());
                state.stream = state.haClient.execute(command);
                state.subscriptionId = newId;
                log.info("{} Subscribed [subscriptionId={}]", state.definition.name(), newId);
            } catch (AMPSException e) {
                log.error("{} Subscribe failed", state.definition.name(), e);
                return;
            }

            if (staleId != null) {
                try {
                    state.haClient.unsubscribe(staleId);
                } catch (AMPSException e) {
                    log.debug("{} Unsubscribe of stale id {} failed (expected if the connection "
                            + "already dropped it server-side)", state.definition.name(), staleId);
                }
            }

            startReaderLocked(state);
        } finally {
            state.lock.unlock();
        }
    }

    private void stopReader(ClientState state) {
        state.lock.lock();
        try {
            stopReaderLocked(state);
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * Stops and joins the current reader thread (if any) before returning — never a "signal and
     * hope". Always called under {@code state.lock}, so this method itself doesn't need to guard
     * against concurrent entry; what matters is that {@code state.running} is only flipped to
     * {@code false} <em>after</em> the old thread is confirmed dead, never before — otherwise a
     * racing {@link #startReaderLocked} could see "not running" and launch a second reader thread
     * while the old one is still alive and mid-join.
     */
    private void stopReaderLocked(ClientState state) {
        if (!state.running.get()) {
            return;
        }
        MessageStream stream = state.stream;
        if (stream != null) {
            try {
                stream.close(); // unblocks a reader thread parked in the for-loop below
            } catch (RuntimeException e) {
                log.debug("{} MessageStream close raised (expected if already disconnected): {}",
                        state.definition.name(), e.toString());
            }
        }
        Thread reader = state.readerThread;
        if (reader != null) {
            try {
                reader.join(READER_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state.running.set(false);
        log.info("{} Reader Stopped", state.definition.name());
    }

    /**
     * No-op if a reader is already running — never two concurrent readers per client. Only called
     * under {@code state.lock} (see {@code stopReaderLocked}'s Javadoc for why that's sufficient
     * instead of a CAS). {@code readerThread} is assigned <em>before</em> {@code running} flips to
     * {@code true}, so an external observer that sees "running" can never see a stale/missing thread
     * reference for it.
     */
    private void startReaderLocked(ClientState state) {
        if (state.running.get()) {
            log.debug("{} startReader ignored, already running", state.definition.name());
            return;
        }
        Thread reader = Thread.ofVirtual()
                .name("amps-reader-" + state.definition.name())
                .start(() -> readLoop(state));
        state.readerThread = reader;
        state.running.set(true);
        log.info("{} Reader Started", state.definition.name());
    }

    private void readLoop(ClientState state) {
        String clientName = state.definition.name();
        try {
            for (Message message : state.stream) {
                if (message == null) {
                    continue;
                }
                try {
                    messageProcessor.process(AmpsMessage.from(clientName, message));
                } catch (RuntimeException e) {
                    log.error("{} Failed to hand off message for processing", clientName, e);
                }
            }
        } catch (RuntimeException e) {
            // Connection lost mid-read — expected, non-fatal. No self-restart here; the next
            // LoggedOn event drives recovery via the recovery executor.
            log.debug("{} Reader loop exiting, connection lost: {}", clientName, e.toString());
            return;
        }
        log.debug("{} Reader loop exiting, stream ended", clientName);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        for (ClientState state : clients.values()) {
            stopReader(state);
            try {
                state.haClient.close();
            } catch (RuntimeException e) {
                log.warn("{} Error during disconnect", state.definition.name(), e);
            }
            state.recoveryExecutor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** Test-observability only — package-private, not part of the public API. */
    boolean isReaderRunning(String clientName) {
        ClientState state = clients.get(clientName);
        return state != null && state.running.get();
    }

    CommandId subscriptionIdFor(String clientName) {
        ClientState state = clients.get(clientName);
        return state != null ? state.subscriptionId : null;
    }

    Thread readerThreadFor(String clientName) {
        ClientState state = clients.get(clientName);
        return state != null ? state.readerThread : null;
    }

    /** Per-client runtime state. Private/nested by design — not one of this module's core classes. */
    private static final class ClientState {
        final AmpsSubscribeConfig.ClientDef definition;
        final ReentrantLock lock = new ReentrantLock();
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicBoolean loggedOn = new AtomicBoolean(false);
        final ExecutorService recoveryExecutor;
        volatile HAClient haClient;
        volatile CommandId subscriptionId;
        volatile MessageStream stream;
        volatile Thread readerThread;

        ClientState(AmpsSubscribeConfig.ClientDef definition) {
            this.definition = definition;
            this.recoveryExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("amps-recovery-" + definition.name()).factory());
        }
    }
}