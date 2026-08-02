package com.bmo.amps.resubscribe.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.ConnectionStateListener;

import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.manager.AmpsClientManager;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.model.ConnectionState;
import com.bmo.amps.resubscribe.reader.AmpsMessageReader;

/**
 * Translates AMPS connection-state callbacks into {@link ConnectionState} transitions and
 * orchestrates recovery by calling {@link AmpsClientManager} and {@link AmpsMessageReader} — the
 * only class allowed to call both (ARCHITECTURE.md {@literal §}4). Owns no resources itself.
 *
 * <p><b>AMPS SDK contract</b> (verified against the AMPS Java Client 5.3.3.3 sources):
 * {@code com.crankuptheamps.client.ConnectionStateListener} is a functional interface with method
 * {@code void connectionStateChanged(int newState_)} — no checked exception, and the state is a
 * plain {@code int} constant ({@code Disconnected=0}, {@code Shutdown=1}, {@code Connected=2},
 * {@code LoggedOn=4}, {@code PublishReplayed=8}, {@code HeartbeatInitiated=16},
 * {@code Resubscribed=32}), not an enum. Critically, <b>the callback carries no client identity</b>
 * — this is why exactly one {@code AmpsConnectionListener} instance is created per
 * {@code AmpsClientContext} and registered only on that one client's {@code HAClient}
 * (see {@code AmpsLifecycleManager}); a single listener instance must never be shared across
 * multiple {@code HAClient}s, or callbacks from different clients would be indistinguishable.
 *
 * <p>There is also no distinct "reconnecting" notification — AMPS retries internally and the next
 * signal received is simply another {@code Connected}. {@link ConnectionState#WAITING} therefore
 * covers that entire interval (see {@code ConnectionState} Javadoc).
 *
 * <p><b>Callback-thread discipline (THREADING_MODEL.md {@literal §}4):</b> AMPS documents that
 * commands must not be submitted from within a connection-state callback (deadlock risk — the
 * callback runs on the client's receive thread, which is also what would deliver the command's
 * ack). Every method below therefore does only a state transition on the callback thread and
 * defers the actual recovery work (which submits commands) to {@code context.recoveryExecutor()}.
 */
public class AmpsConnectionListener implements ConnectionStateListener {

    private static final Logger log = LoggerFactory.getLogger(AmpsConnectionListener.class);

    private final AmpsClientContext context;
    private final AmpsClientManager manager;
    private final AmpsMessageReader reader;

    public AmpsConnectionListener(AmpsClientContext context, AmpsClientManager manager, AmpsMessageReader reader) {
        this.context = context;
        this.manager = manager;
        this.reader = reader;
    }

    @Override
    public void connectionStateChanged(int newState) {
        log.debug("{} AMPS connection state callback: {}", context.clientType(), newState);
        switch (newState) {
            case ConnectionStateListener.Connected -> onConnected();
            case ConnectionStateListener.LoggedOn -> onLoggedOn();
            case ConnectionStateListener.Disconnected -> onDisconnected();
            case ConnectionStateListener.Shutdown -> onDisconnected();
            default -> log.debug("{} No handling needed for connection state {}", context.clientType(), newState);
        }
    }

    private void onConnected() {
        if (!context.transition(ConnectionState.CONNECTING, ConnectionState.CONNECTED)) {
            context.transition(ConnectionState.WAITING, ConnectionState.CONNECTED);
        }
    }

    private void onLoggedOn() {
        if (!context.transition(ConnectionState.CONNECTED, ConnectionState.LOGGED_ON)) {
            log.debug("{} LoggedOn callback ignored, unexpected prior state {}",
                    context.clientType(), context.state());
            return;
        }

        boolean firstTimeSubscribe = context.subscriptionId() == null;
        context.recoveryExecutor().submit(() -> {
            if (firstTimeSubscribe) {
                runInitialSubscribe();
            } else {
                runReconnectRecovery();
            }
        });
    }

    private void onDisconnected() {
        // Disconnect can arrive from several prior states (mid-connect, mid-subscribe, or while
        // reading) — force rather than CAS-guard this one transition, then let the recovery task
        // below re-establish a clean sequence from WAITING onward.
        context.forceState(ConnectionState.DISCONNECTED);
        context.recoveryExecutor().submit(this::runDisconnectRecovery);
    }

    private void runDisconnectRecovery() {
        ClientType type = context.clientType();
        context.recoveryLock().lock();
        try {
            reader.stop(context);
            context.transition(ConnectionState.DISCONNECTED, ConnectionState.WAITING);
        } catch (RuntimeException e) {
            log.error("{} Disconnect recovery failed", type, e);
        } finally {
            context.recoveryLock().unlock();
        }
    }

    private void runInitialSubscribe() {
        ClientType type = context.clientType();
        context.recoveryLock().lock();
        try {
            manager.subscribe(type);
            reader.start(context);
            context.transition(ConnectionState.SUBSCRIBED, ConnectionState.READING);
        } catch (RuntimeException e) {
            log.error("{} Initial subscribe failed", type, e);
        } finally {
            context.recoveryLock().unlock();
        }
    }

    private void runReconnectRecovery() {
        ClientType type = context.clientType();
        context.recoveryLock().lock();
        try {
            manager.resubscribe(type);
            context.transition(ConnectionState.LOGGED_ON, ConnectionState.RESUBSCRIBED);
            reader.start(context);
            context.transition(ConnectionState.RESUBSCRIBED, ConnectionState.READING);
            log.info("{} Reader Restarted", type);
        } catch (RuntimeException e) {
            log.error("{} Reconnect recovery failed", type, e);
        } finally {
            context.recoveryLock().unlock();
        }
    }
}
