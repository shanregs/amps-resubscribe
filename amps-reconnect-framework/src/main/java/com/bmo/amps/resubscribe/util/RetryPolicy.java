package com.bmo.amps.resubscribe.util;

import java.time.Duration;

/**
 * Computes backoff delays for resubscribe retries (DESIGN.md {@literal §}6). Implementations must be
 * stateless with respect to {@code attempt} — the caller tracks the attempt counter and resets it to 1
 * after a successful operation, so the same policy instance can be shared safely across concurrent
 * clients.
 */
public interface RetryPolicy {

    /**
     * @param attempt 1-based attempt number (1 = first retry after the initial failed try)
     * @return delay to wait before this attempt
     */
    Duration nextDelay(int attempt);
}
