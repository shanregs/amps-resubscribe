package com.bmo.amps.publisher;

import java.beans.ExceptionListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.crankuptheamps.client.*;
import com.crankuptheamps.client.exception.AMPSException;
import com.crankuptheamps.client.exception.DisconnectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Publishes synthetic messages onto one or more configured queues at a steady, per-queue rate (with a
 * burst allowance), so {@code amps-reconnect-simple} / {@code amps-reconnect-framework} have live
 * traffic to observe during HA failover testing (see {@code docs/HA_AMPS_LAB_SETUP_GUIDE.md}).
 *
 * <p>Connects a single {@link HAClient} at startup and reuses it across all target queues — AMPS's
 * {@code publish()} is safe to call concurrently, so one connection backing several virtual-thread
 * publish loops (one per {@link AmpsPublishConfig.Target}) is enough.
 *
 * <p>Publishing pauses (without burning rate-limit tokens) while the client is not logged on, and
 * resumes automatically once {@link HAClient} reconnects.
 *
 * <p><b>Why there's a separate {@code connectWithRetry()} loop:</b> {@link HAClient#connectAndLogon()}
 * only retries across the servers offered by its {@link ServerChooser}; once {@link DefaultServerChooser}
 * has cycled through every configured URI without success, it returns an empty URI and
 * {@code connectAndLogon()} throws — permanently, for that call. AMPS's own background reconnect
 * machinery only takes over <i>after</i> an initial connect has succeeded and later drops; it never
 * activates if the very first {@code connectAndLogon()} never got through (e.g. a single-URI dev config,
 * or the whole cluster being down at startup). So this class runs its own indefinite, exponentially
 * backed-off retry loop around {@code connectAndLogon()} on a dedicated virtual thread, decoupled from
 * Spring's {@link SmartLifecycle} startup phase, so a down AMPS server at boot doesn't block application
 * startup and a full-outage-then-recovery scenario actually recovers automatically.
 *
 * <p>This class does not need to resubscribe anything on reconnect since publishing carries no
 * subscription state.
 */
@Component
public class AmpsPublisher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AmpsPublisher.class);
    private static final long DISCONNECTED_POLL_MS = 200;
    private static final long THREAD_JOIN_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long CONNECT_RETRY_INITIAL_DELAY_MS = 1000;
    private static final long CONNECT_RETRY_MAX_DELAY_MS = 15_000;

    private final AmpsPublishConfig config;
    private final OrderPayloadGenerator payloadGenerator;
    private final Map<String, Thread> publisherThreads = new ConcurrentHashMap<>();
    private final AtomicBoolean loggedOn = new AtomicBoolean(false);
    private final AtomicBoolean everLoggedOn = new AtomicBoolean(false);
    private volatile boolean running = false;
    private volatile HAClient haClient;
    private volatile Thread connectorThread;

    // Package-private test seam (default: real AMPS HAClient) — see AmpsPublisherTest.
    private Supplier<HAClient> haClientFactory;

    public AmpsPublisher(AmpsPublishConfig config, OrderPayloadGenerator payloadGenerator) {
        this.config = config;
        this.payloadGenerator = payloadGenerator;
        this.haClientFactory = () -> createDefaultHaClient(config);
    }

    void setHaClientFactory(Supplier<HAClient> haClientFactory) {
        this.haClientFactory = haClientFactory;
    }

    private static HAClient createDefaultHaClient(AmpsPublishConfig config) {
        HAClient client = new HAClient(config.clientName());
        try {
            client.setHeartbeat(5, 15);
        } catch (DisconnectedException e) {
            log.error("{} Error during heartbeat configuration", config.clientName(), e);
            throw new RuntimeException(e);
        }
        DefaultServerChooser chooser = new DefaultServerChooser();
        for (String uri : config.uris()) {
            chooser.add(uri);
        }
        client.setServerChooser(chooser);
        client.setReconnectDelayStrategy(
                new LoggingDelayStrategy(new ExponentialDelayStrategy(), config.clientName()));
        return client;
    }

    /**
     * Wraps AMPS's {@code ExponentialDelayStrategy} purely for observability. Note that
     * {@code getConnectWaitDuration()} is called on <i>every</i> connection attempt, including the very
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
                log.error("{} Error during reconnect delay calculation", clientName, e);
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
        haClient = haClientFactory.get();
        haClient.setExceptionListener((ExceptionListener) ex ->
                log.warn("{} Connection exception during retry: {}", config.clientName(), ex.toString()));
        haClient.addConnectionStateListener(this::onConnectionStateChanged);

        running = true;

        connectorThread = Thread.ofVirtual()
                .name("amps-connector-" + config.clientName())
                .start(this::connectWithRetry);

        for (AmpsPublishConfig.Target target : config.targets()) {
            RateLimiter limiter = new RateLimiter(target.rps(), target.burst());
            Thread thread = Thread.ofVirtual()
                    .name("amps-publisher-" + target.queue())
                    .start(() -> publishLoop(target, limiter));
            publisherThreads.put(target.queue(), thread);
        }
    }

    /**
     * Keeps calling {@link HAClient#connectAndLogon()} until it succeeds or the publisher is stopped.
     * {@code connectAndLogon()} only retries across the URIs its {@link ServerChooser} offers and then
     * throws for good — this loop is what makes the publisher actually recover from a total outage (all
     * configured servers down) instead of giving up permanently after one exhausted pass.
     */
    private void connectWithRetry() {
        long delay = CONNECT_RETRY_INITIAL_DELAY_MS;
        while (running && !loggedOn.get()) {
            try {
                haClient.connectAndLogon();
                return; // success — LoggedOn callback (below) flips loggedOn/everLoggedOn
            } catch (AMPSException e) {
                log.warn("{} Connect attempt failed, retrying in {}ms: {}",
                        config.clientName(), delay, e.toString());
                sleepQuietly(delay);
                delay = Math.min(delay * 2, CONNECT_RETRY_MAX_DELAY_MS);
            }
        }
    }

    private void onConnectionStateChanged(int newState) {
        switch (newState) {
            case ConnectionStateListener.Connected ->
                    log.info("{} Connected", config.clientName());
            case ConnectionStateListener.LoggedOn -> {
                loggedOn.set(true);
                if (everLoggedOn.compareAndSet(false, true)) {
                    log.info("{} LoggedOn, publishing started", config.clientName());
                } else {
                    log.info("{} Reconnected and LoggedOn, publishing resumed", config.clientName());
                }
            }
            case ConnectionStateListener.Disconnected -> {
                loggedOn.set(false);
                log.warn("{} Disconnected, publishing paused until reconnect", config.clientName());
            }
            case ConnectionStateListener.Shutdown -> {
                loggedOn.set(false);
                log.error("{} Shutdown reported by client; connectWithRetry loop will keep retrying "
                        + "independently", config.clientName());
            }
            case ConnectionStateListener.PublishReplayed ->
                    log.info("{} Publish store replayed after reconnect", config.clientName());
            case ConnectionStateListener.HeartbeatInitiated ->
                    log.debug("{} Heartbeat initiated", config.clientName());
            case ConnectionStateListener.Resubscribed ->
                    log.debug("{} Resubscribed", config.clientName());
            default -> log.debug("{} No handling needed for connection state {}", config.clientName(), newState);
        }
    }

    private void publishLoop(AmpsPublishConfig.Target target, RateLimiter limiter) {
        log.info("{} Publisher Started [queue={}, rps={}, burst={}]",
                config.clientName(), target.queue(), target.rps(), target.burst());
        while (running) {
            if (!loggedOn.get()) {
                sleepQuietly(DISCONNECTED_POLL_MS);
                continue;
            }
            try {
                limiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!running) {
                break;
            }
            try {
                String publishData = payloadGenerator.next(target.queue());
                haClient.publish(target.queue(), publishData);
                log.info("{} Publisher published [queue={}], data - {}", config.clientName(), target.queue(), publishData);
            } catch (AMPSException e) {
                log.debug("{} Publish failed [queue={}]: {}", config.clientName(), target.queue(), e.toString());
            }
        }
        log.info("{} Publisher Stopped [queue={}]", config.clientName(), target.queue());
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

        if (connectorThread != null) {
            connectorThread.interrupt();
            try {
                connectorThread.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        for (Thread thread : publisherThreads.values()) {
            try {
                thread.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        publisherThreads.clear();

        if (haClient != null) {
            try {
                haClient.close();
            } catch (RuntimeException e) {
                log.warn("{} Error during disconnect", config.clientName(), e);
            }
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
    boolean isLoggedOn() {
        return loggedOn.get();
    }

    List<Thread> publisherThreads() {
        return List.copyOf(publisherThreads.values());
    }
}