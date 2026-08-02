package com.bmo.amps.publisher;

import java.util.concurrent.TimeUnit;

/**
 * Token-bucket rate limiter: {@code ratePerSecond} tokens refill continuously, up to a maximum of
 * {@code burstCapacity} banked tokens. The bucket starts full, so a caller that has been idle (or has
 * just started up) can immediately emit a burst of up to {@code burstCapacity} messages before being
 * throttled back to the steady-state {@code ratePerSecond}.
 *
 * <p>Deliberately hand-rolled rather than pulling in Guava's {@code RateLimiter} — this is the only
 * rate-limiting need in the module and the token-bucket math is a dozen lines, not worth a new
 * dependency.
 */
final class RateLimiter {

    private final double ratePerNano;
    private final double burstCapacity;
    private double tokens;
    private long lastRefillNanos;

    RateLimiter(double ratePerSecond, double burstCapacity) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be positive: " + ratePerSecond);
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be positive: " + burstCapacity);
        }
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        this.burstCapacity = burstCapacity;
        this.tokens = burstCapacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Blocks the calling thread until one token is available, then consumes it. */
    void acquire() throws InterruptedException {
        while (true) {
            long waitNanos;
            synchronized (this) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                waitNanos = (long) Math.ceil((1.0 - tokens) / ratePerNano);
            }
            if (waitNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        tokens = Math.min(burstCapacity, tokens + elapsed * ratePerNano);
        lastRefillNanos = now;
    }
}
