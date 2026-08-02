package com.bmo.amps.resubscribe.util;

import java.time.Duration;

/**
 * {@code delay(attempt) = min(initialDelay * multiplier^(attempt-1), maxDelay)}, per client's
 * {@code amps.clients[].retry} configuration (DESIGN.md {@literal §}7).
 */
public final class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;

    public ExponentialBackoffRetryPolicy(Duration initialDelay, Duration maxDelay, double multiplier) {
        if (initialDelay.isNegative() || initialDelay.isZero()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= initialDelay");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
    }

    @Override
    public Duration nextDelay(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
        }
        double factor = Math.pow(multiplier, attempt - 1);
        // Guard against overflow for pathologically large attempt counts before scaling the duration.
        if (Double.isInfinite(factor) || factor > 1_000_000d) {
            return maxDelay;
        }
        long candidateMillis = Math.round(initialDelay.toMillis() * factor);
        long cappedMillis = Math.min(candidateMillis, maxDelay.toMillis());
        return Duration.ofMillis(cappedMillis);
    }
}
