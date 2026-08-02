package com.bmo.amps.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void nonPositiveRateIsRejected() {
        assertThatThrownBy(() -> new RateLimiter(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveBurstIsRejected() {
        assertThatThrownBy(() -> new RateLimiter(10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bucketStartsFullAllowingAnImmediateBurst() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(1, 5);

        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // All 5 burst tokens were pre-filled, so 5 acquires at rate=1/s should not need to wait ~4s.
        assertThat(elapsedMs).isLessThan(500);
    }

    @Test
    void acquireBlocksOnceBurstIsExhausted() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(10, 1);
        limiter.acquire(); // drains the single burst token

        long start = System.nanoTime();
        limiter.acquire(); // must wait ~100ms for the next token at 10/s
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(80);
    }
}
