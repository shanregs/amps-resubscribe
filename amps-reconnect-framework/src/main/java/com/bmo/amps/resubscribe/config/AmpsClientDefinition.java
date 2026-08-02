package com.bmo.amps.resubscribe.config;

import java.time.Duration;
import java.util.List;

import com.bmo.amps.resubscribe.model.ClientType;

/**
 * One entry under {@code amps.clients} in application.yml (DESIGN.md {@literal §}7). Immutable —
 * {@code AmpsClientManager} reads this once at {@code initialize()} time; nothing about a running
 * client's configuration changes at runtime.
 *
 * <p>{@code uris} is a list, not a single URI, so {@code DefaultServerChooser} can be given every
 * failover candidate (e.g. a primary and a secondary AMPS instance) — see
 * {@code docs/HA_AMPS_LAB_SETUP_GUIDE.md} for a local primary/secondary setup to test against.
 */
public record AmpsClientDefinition(
        ClientType type,
        List<String> uris,
        String queue,
        RetryConfig retry
) {

    public AmpsClientDefinition {
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (uris == null || uris.isEmpty()) {
            throw new IllegalArgumentException("at least one uri is required for " + type);
        }
        if (queue == null || queue.isBlank()) {
            throw new IllegalArgumentException("queue is required for " + type);
        }
        if (retry == null) {
            retry = RetryConfig.defaults();
        }
    }

    public record RetryConfig(Duration initialDelay, Duration maxDelay, double multiplier) {

        public RetryConfig {
            if (initialDelay == null) {
                initialDelay = Duration.ofMillis(500);
            }
            if (maxDelay == null) {
                maxDelay = Duration.ofSeconds(30);
            }
            if (multiplier <= 0) {
                multiplier = 2.0;
            }
        }

        public static RetryConfig defaults() {
            return new RetryConfig(Duration.ofMillis(500), Duration.ofSeconds(30), 2.0);
        }
    }
}
