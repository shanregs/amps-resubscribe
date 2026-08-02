package com.bmo.amps.resubscribe.support;

import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

import com.crankuptheamps.client.HAClient;

import com.bmo.amps.resubscribe.config.AmpsClientDefinition;
import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.util.ExecutorFactory;
import com.bmo.amps.resubscribe.util.ExponentialBackoffRetryPolicy;
import com.bmo.amps.resubscribe.util.RetryPolicy;

/** Shared test fixture builder — every test class gets a consistently-wired {@code AmpsClientContext}. */
public final class TestContextFactory {

    private TestContextFactory() {
    }

    public static AmpsClientDefinition definition(ClientType type) {
        return new AmpsClientDefinition(
                type,
                List.of("tcp://localhost:9027/amps/json"),
                type.name().toLowerCase() + ".queue",
                new AmpsClientDefinition.RetryConfig(Duration.ofMillis(5), Duration.ofMillis(50), 2.0));
    }

    public static AmpsClientContext newContext(ClientType type) {
        return newContext(type, mock(HAClient.class));
    }

    public static AmpsClientContext newContext(ClientType type, HAClient haClient) {
        AmpsClientDefinition definition = definition(type);
        RetryPolicy retryPolicy = new ExponentialBackoffRetryPolicy(
                definition.retry().initialDelay(), definition.retry().maxDelay(), definition.retry().multiplier());
        ExecutorService readerExecutor = ExecutorFactory.createSingleThreadExecutor(type, "reader");
        ExecutorService recoveryExecutor = ExecutorFactory.createSingleThreadExecutor(type, "recovery");
        return new AmpsClientContext(type, haClient, definition, retryPolicy, readerExecutor, recoveryExecutor);
    }

    public static void shutdown(AmpsClientContext context) {
        context.readerExecutor().shutdownNow();
        context.recoveryExecutor().shutdownNow();
    }
}
