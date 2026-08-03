package com.bmo.amps.publisher.readable;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.ReconnectDelayStrategy;

/**
 * Wraps an AMPS {@link ReconnectDelayStrategy} (normally {@code ExponentialDelayStrategy}) purely to
 * log every connect/reconnect attempt. It does not change the delay, just observes and reports it.
 *
 * <p>AMPS calls {@code getConnectWaitDuration()} on every attempt, including the very first
 * {@code connectAndLogon()} at startup — so attempt #1 is logged as "Initial connect", not
 * "Reconnect", to avoid alarming logs on a normal, healthy startup.
 */
final class ReconnectLogger implements ReconnectDelayStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReconnectLogger.class);

    private final ReconnectDelayStrategy delegate;
    private final String clientName;
    private final AtomicInteger attemptCount = new AtomicInteger(0);

    ReconnectLogger(ReconnectDelayStrategy delegate, String clientName) {
        this.delegate = delegate;
        this.clientName = clientName;
    }

    @Override
    public int getConnectWaitDuration(String uri) {
        int delayMillis;
        try {
            delayMillis = delegate.getConnectWaitDuration(uri);
        } catch (Exception e) {
            log.error("{} Error computing reconnect delay", clientName, e);
            throw new RuntimeException(e);
        }

        int attemptNumber = attemptCount.incrementAndGet();
        String label = attemptNumber == 1 ? "Initial connect attempt" : "Reconnect attempt #" + attemptNumber;
        log.warn("{} {} to {} scheduled after {}ms delay", clientName, label, uri, delayMillis);
        return delayMillis;
    }

    @Override
    public void reset() {
        int attemptsBeforeReset = attemptCount.getAndSet(0);
        if (attemptsBeforeReset > 1) {
            log.info("{} Reconnected successfully after {} attempt(s)", clientName, attemptsBeforeReset);
        }
        delegate.reset();
    }
}
