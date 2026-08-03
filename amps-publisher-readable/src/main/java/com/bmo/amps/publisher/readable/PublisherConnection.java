package com.bmo.amps.publisher.readable;

import java.beans.ExceptionListener;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.ConnectionStateListener;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.ExponentialDelayStrategy;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.DisconnectedException;

/**
 * Owns the single {@code HAClient} this publisher connects with: builds it, connects (retrying
 * indefinitely), and tracks whether it is currently logged on. {@link QueuePublishLoop} asks
 * {@link #isLoggedOn()} before every publish and calls {@link #publish(String, String)} to send —
 * it never touches the {@code HAClient} directly.
 *
 * <p>Publishing carries no subscription state, so unlike {@code amps-subscriber-readable}'s
 * {@code QueueSubscription}, there is nothing to resubscribe after a reconnect — tracking
 * {@link #isLoggedOn()} is all recovery requires here.
 */
final class PublisherConnection {

    private static final Logger log = LoggerFactory.getLogger(PublisherConnection.class);
    private static final long INITIAL_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 15_000;
    private static final long CONNECTOR_STOP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    private final String clientName;
    private final HAClient haClient;

    private volatile boolean shouldRun;
    private volatile boolean loggedOn;
    private final AtomicBoolean everLoggedOn = new AtomicBoolean(false);
    private volatile Thread connectorThread;

    PublisherConnection(PublisherSettings settings) {
        this.clientName = settings.clientName();
        this.haClient = buildDefaultHaClient(settings);
        this.haClient.setExceptionListener((ExceptionListener) ex ->
                log.warn("{} Connection exception during retry: {}", clientName, ex.toString()));
        this.haClient.addConnectionStateListener(this::onConnectionStateChanged);
    }

    private static HAClient buildDefaultHaClient(PublisherSettings settings) {
        HAClient client = new HAClient(settings.clientName());
        try {
            client.setHeartbeat(5, 15);
        } catch (DisconnectedException e) {
            log.error("{} Error during heartbeat configuration", settings.clientName(), e);
            throw new RuntimeException(e);
        }
        DefaultServerChooser serverChooser = new DefaultServerChooser();
        for (String uri : settings.uris()) {
            serverChooser.add(uri);
        }
        client.setServerChooser(serverChooser);
        client.setReconnectDelayStrategy(
                new ReconnectLogger(new ExponentialDelayStrategy(), settings.clientName()));
        return client;
    }

    void start() {
        shouldRun = true;
        connectorThread = Thread.ofVirtual().name("amps-connector-" + clientName).start(this::connectWithRetry);
    }

    /**
     * Retries {@code connectAndLogon()} indefinitely, with exponential backoff, until it succeeds or
     * {@link #stop()} is called — see {@code QueueSubscription.connectWithRetry()} in
     * amps-subscriber-readable for the full rationale (same reasoning applies here: AMPS's own
     * reconnect machinery never activates until a first connect has succeeded at least once).
     */
    private void connectWithRetry() {
        long delayMs = INITIAL_RETRY_DELAY_MS;
        while (shouldRun && !loggedOn) {
            try {
                haClient.connectAndLogon();
                return; // success is reported back to us via onConnectionStateChanged(LoggedOn)
            } catch (AMPSException e) {
                log.warn("{} Connect attempt failed, retrying in {}ms: {}", clientName, delayMs, e.toString());
                sleepQuietly(delayMs);
                delayMs = Math.min(delayMs * 2, MAX_RETRY_DELAY_MS);
            }
        }
    }

    private void onConnectionStateChanged(int newState) {
        switch (newState) {
            case ConnectionStateListener.Connected -> log.info("{} Connected", clientName);
            case ConnectionStateListener.LoggedOn -> {
                loggedOn = true;
                if (everLoggedOn.compareAndSet(false, true)) {
                    log.info("{} LoggedOn, publishing started", clientName);
                } else {
                    log.info("{} Reconnected and LoggedOn, publishing resumed", clientName);
                }
            }
            case ConnectionStateListener.Disconnected -> {
                loggedOn = false;
                log.warn("{} Disconnected, publishing paused until reconnect", clientName);
            }
            case ConnectionStateListener.Shutdown -> {
                loggedOn = false;
                log.error("{} Shutdown reported by client; connectWithRetry keeps retrying independently",
                        clientName);
            }
            case ConnectionStateListener.PublishReplayed -> log.info("{} Publish store replayed after reconnect", clientName);
            case ConnectionStateListener.HeartbeatInitiated -> log.debug("{} Heartbeat initiated", clientName);
            case ConnectionStateListener.Resubscribed -> log.debug("{} Resubscribed", clientName);
            default -> log.debug("{} No handling needed for connection state {}", clientName, newState);
        }
    }

    boolean isLoggedOn() {
        return loggedOn;
    }

    void publish(String queue, String data) throws AMPSException {
        haClient.publish(queue, data);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void stop() {
        shouldRun = false;
        if (connectorThread != null) {
            connectorThread.interrupt();
            try {
                connectorThread.join(CONNECTOR_STOP_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            haClient.close();
        } catch (RuntimeException e) {
            log.warn("{} Error during disconnect", clientName, e);
        }
    }
}
