package com.bmo.amps.resubscribe.manager;

import com.crankuptheamps.client.HAClient;

import com.bmo.amps.resubscribe.config.AmpsClientDefinition;

/**
 * Testability seam: production code uses the default AMPS-backed factory; unit tests supply one
 * that returns a Mockito mock so {@link DefaultAmpsClientManager} can be tested without a live
 * AMPS server (CLAUDE.md Project Objectives — "testability").
 */
@FunctionalInterface
public interface HAClientFactory {
    HAClient create(AmpsClientDefinition definition);
}
