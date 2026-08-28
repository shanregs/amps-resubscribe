package com.bmo.amps.subscriber;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.ReconnectDelayStrategy;

/**
 * Wraps AMPS's {@code ExponentialDelayStrategy} purely for observability. Note that
 * {@code getConnectWaitDuration()} is called on every connection attempt, including the very first
 * one on a fresh {@code connectAndLogon()} — not just retries after a disconnect — so the first call
 * (attempt #1) is labeled as the initial connect rather than a "reconnect" to avoid misleading logs
 * on normal startup.
 */
final class LoggingDelayStrategy implements ReconnectDelayStrategy {

    private static final Logger log = LoggerFactory.getLogger(LoggingDelayStrategy.class);

    private final ReconnectDelayStrategy delegate;
    private final String clientName;
    private final AtomicInteger attempt = new AtomicInteger(0);

    LoggingDelayStrategy(ReconnectDelayStrategy delegate, String clientName) {
        this.delegate = delegate;
        this.clientName = clientName;
    }

    @Override
    public int getConnectWaitDuration(String uri) {
        int delay;
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
