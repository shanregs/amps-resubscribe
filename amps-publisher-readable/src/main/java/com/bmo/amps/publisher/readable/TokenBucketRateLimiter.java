package com.bmo.amps.publisher.readable;

import java.util.concurrent.TimeUnit;

/**
 * Token-bucket rate limiter: {@code ratePerSecond} tokens refill continuously, up to a maximum of
 * {@code burstCapacity} banked tokens. The bucket starts full, so a caller that has just started up
 * (or was idle) can immediately emit a burst of up to {@code burstCapacity} messages before being
 * throttled back to the steady-state {@code ratePerSecond}.
 *
 * <p>Deliberately hand-rolled rather than pulling in a rate-limiting library — this is the only
 * rate-limiting need in the module and the token-bucket math is a dozen lines.
 */
final class TokenBucketRateLimiter {

    private final double ratePerNano;
    private final double burstCapacity;
    private double availableTokens;
    private long lastRefillAtNanos;

    TokenBucketRateLimiter(double ratePerSecond, double burstCapacity) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be positive: " + ratePerSecond);
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be positive: " + burstCapacity);
        }
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        this.burstCapacity = burstCapacity;
        this.availableTokens = burstCapacity;
        this.lastRefillAtNanos = System.nanoTime();
    }

    /** Blocks the calling thread until one token is available, then consumes it. */
    void acquire() throws InterruptedException {
        while (true) {
            long waitNanos;
            synchronized (this) {
                refill();
                if (availableTokens >= 1.0) {
                    availableTokens -= 1.0;
                    return;
                }
                waitNanos = (long) Math.ceil((1.0 - availableTokens) / ratePerNano);
            }
            if (waitNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillAtNanos;
        if (elapsedNanos <= 0) {
            return;
        }
        availableTokens = Math.min(burstCapacity, availableTokens + elapsedNanos * ratePerNano);
        lastRefillAtNanos = now;
    }
}
