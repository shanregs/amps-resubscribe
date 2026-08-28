package com.bmo.amps.subscriber;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.crankuptheamps.client.CommandId;
import com.crankuptheamps.client.HAClient;

/**
 * Wires up one {@link AmpsClientConnection} per configured client and starts/stops them all
 * together. All the reconnect/resubscribe/reader-restart logic lives in {@link AmpsClientConnection}
 * — this class is deliberately just the map of clients plus Spring lifecycle plumbing.
 */
@Component
public class AmpsSubscriber implements SmartLifecycle {

    private final AmpsSubscribeConfig config;
    private final MessageProcessor messageProcessor;
    private final Map<String, AmpsClientConnection> clients = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    // Package-private test seam (default: real AMPS HAClient) — see AmpsSubscriberTest. Kept as a
    // mutable field rather than a constructor parameter so Spring sees exactly one constructor.
    private Function<AmpsSubscribeConfig.ClientDef, HAClient> haClientFactory =
            AmpsClientConnection::createDefaultHaClient;

    public AmpsSubscriber(AmpsSubscribeConfig config, MessageProcessor messageProcessor) {
        this.config = config;
        this.messageProcessor = messageProcessor;
    }

    void setHaClientFactory(Function<AmpsSubscribeConfig.ClientDef, HAClient> haClientFactory) {
        this.haClientFactory = haClientFactory;
    }

    @Override
    public void start() {
        running = true;
        for (AmpsSubscribeConfig.ClientDef definition : config.clients()) {
            AmpsClientConnection connection =
                    new AmpsClientConnection(definition, messageProcessor, haClientFactory);
            clients.put(definition.name(), connection);
            connection.start();
        }
    }

    @Override
    public void stop() {
        running = false;
        for (AmpsClientConnection connection : clients.values()) {
            connection.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** Test-observability only — package-private, not part of the public API. */
    boolean isReaderRunning(String clientName) {
        AmpsClientConnection connection = clients.get(clientName);
        return connection != null && connection.isReaderRunning();
    }

    CommandId subscriptionIdFor(String clientName) {
        AmpsClientConnection connection = clients.get(clientName);
        return connection != null ? connection.currentSubscriptionId() : null;
    }

    Thread readerThreadFor(String clientName) {
        AmpsClientConnection connection = clients.get(clientName);
        return connection != null ? connection.readerThread() : null;
    }
}
