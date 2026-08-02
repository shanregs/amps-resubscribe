package com.bmo.amps.simple;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;

/**
 * Creates the configured HAClients, listens for their connection-state changes, resubscribes and
 * restarts each client's reader loop after every reconnect, and never leaves two readers or two
 * live subscriptions for the same client at once. Deliberately a single class (see project's
 * simplified-module goal) — contrast with the {@code amps-reconnect-framework} module, which splits
 * this same responsibility across ~10 SOLID-oriented classes.
 *
 * <p><b>Why one listener callback per client, not one shared listener:</b> AMPS's
 * {@code ConnectionStateListener.connectionStateChanged(int)} carries no client identity, so each
 * {@code HAClient} is registered with its own lambda capturing its own name — never register the
 * same listener instance across multiple clients.
 *
 * <p><b>Why recovery hops to a virtual thread:</b> AMPS forbids submitting commands (like our
 * resubscribe) from inside the connection-state callback — that callback runs on the client's
 * receive thread, which is also what would deliver the command's ack, so calling it inline risks
 * deadlock. Virtual threads make "just spawn one" cheap enough to do per callback instead of
 * maintaining a dedicated executor.
 */
@Component
public class AmpsQueueClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AmpsQueueClient.class);
    private static final long READER_JOIN_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

    private final AmpsSubscribeConfig config;
    private final MessageProcessor messageProcessor;
    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    // Package-private test seam (default: real AMPS HAClient) — see AmpsQueueClientTest. Kept as a
    // mutable field rather than a constructor parameter so Spring sees exactly one constructor.
    private Function<AmpsSubscribeConfig.ClientDef, HAClient> haClientFactory = AmpsQueueClient::createDefaultHaClient;

    public AmpsQueueClient(AmpsSubscribeConfig config, MessageProcessor messageProcessor) {
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
        return haClient;
    }

    @Override
    public void start() {
        for (AmpsSubscribeConfig.ClientDef def : config.clients()) {
            ClientState state = new ClientState(def);
            clients.put(def.name(), state);

            state.haClient = haClientFactory.apply(def);
            state.haClient.addConnectionStateListener(
                    newState -> onConnectionStateChanged(def.name(), newState));

            try {
                state.haClient.connectAndLogon();
            } catch (AMPSException e) {
                log.error("{} Failed to connect", def.name(), e);
            }
        }
        running = true;
    }

    private void onConnectionStateChanged(String clientName, int newState) {
        ClientState state = clients.get(clientName);
        if (state == null) {
            return;
        }
        switch (newState) {
            case ConnectionStateListener.Connected ->
                    log.info("{} Connected", clientName);
            case ConnectionStateListener.LoggedOn -> {
                log.info("{} LoggedOn", clientName);
                Thread.ofVirtual().name("amps-recovery-" + clientName)
                        .start(() -> resubscribeAndRestartReader(state));
            }
            case ConnectionStateListener.Disconnected -> {
                log.warn("{} Disconnected", clientName);
                Thread.ofVirtual().name("amps-recovery-" + clientName)
                        .start(() -> stopReader(state));
            }
            case ConnectionStateListener.Shutdown -> {
                log.error("{} Shutdown, client will not reconnect", clientName);
                Thread.ofVirtual().name("amps-recovery-" + clientName)
                        .start(() -> stopReader(state));
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
     * whatever was there before, if anything" (DESIGN.md-equivalent idempotency, condensed).
     */
    private void resubscribeAndRestartReader(ClientState state) {
        if (!state.recovering.compareAndSet(false, true)) {
            log.debug("{} Recovery already in progress, ignoring duplicate LoggedOn callback",
                    state.definition.name());
            return;
        }
        try {
            state.lock.lock();
            try {
                stopReaderLocked(state);

                CommandId staleId = state.subscriptionId;
                CommandId newId = CommandId.nextIdentifier();
                try {
                    Command command = new Command("sow_and_subscribe")
                            .setTopic(state.definition.queue())
                            .setAckType(Message.AckType.Processed)
                            .setCommandId(newId);
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
        } finally {
            state.recovering.set(false);
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
            // LoggedOn callback (via onConnectionStateChanged) drives recovery.
            log.debug("{} Reader loop exiting, connection lost: {}", clientName, e.toString());
            return;
        }
        log.debug("{} Reader loop exiting, stream ended", clientName);
    }

    @Override
    public void stop() {
        for (ClientState state : clients.values()) {
            stopReader(state);
            try {
                state.haClient.close();
            } catch (RuntimeException e) {
                log.warn("{} Error during disconnect", state.definition.name(), e);
            }
        }
        running = false;
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

    /** Per-client runtime state. Private/nested by design — not one of this module's 4 core classes. */
    private static final class ClientState {
        final AmpsSubscribeConfig.ClientDef definition;
        final ReentrantLock lock = new ReentrantLock();
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicBoolean recovering = new AtomicBoolean(false);
        volatile HAClient haClient;
        volatile CommandId subscriptionId;
        volatile MessageStream stream;
        volatile Thread readerThread;

        ClientState(AmpsSubscribeConfig.ClientDef definition) {
            this.definition = definition;
        }
    }
}
