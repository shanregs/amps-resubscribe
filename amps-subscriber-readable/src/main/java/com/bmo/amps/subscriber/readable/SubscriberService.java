package com.bmo.amps.subscriber.readable;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Wires up one {@link QueueSubscription} per configured client and starts/stops them all together.
 * All the reconnect/resubscribe/reader-restart logic lives in {@link QueueSubscription} — this class
 * is deliberately just the list of clients plus Spring lifecycle plumbing.
 */
@Component
public class SubscriberService implements SmartLifecycle {

    private final List<QueueSubscription> subscriptions = new ArrayList<>();
    private final SubscriberSettings settings;
    private final MessageHandler messageHandler;
    private volatile boolean running = false;

    public SubscriberService(SubscriberSettings settings, MessageHandler messageHandler) {
        this.settings = settings;
        this.messageHandler = messageHandler;
    }

    @Override
    public void start() {
        for (SubscriberSettings.ClientDefinition definition : settings.clientDefinitions()) {
            QueueSubscription subscription = new QueueSubscription(definition, messageHandler);
            subscriptions.add(subscription);
            subscription.start();
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        for (QueueSubscription subscription : subscriptions) {
            subscription.stop();
        }
        subscriptions.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
