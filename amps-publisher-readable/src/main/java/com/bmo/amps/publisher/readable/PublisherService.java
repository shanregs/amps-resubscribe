package com.bmo.amps.publisher.readable;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Wires up the single {@link PublisherConnection} and one {@link QueuePublishLoop} per configured
 * target queue, and starts/stops them all together. All the connect-with-retry and publish-loop logic
 * lives in those two classes — this class is deliberately just the wiring plus Spring lifecycle
 * plumbing.
 */
@Component
public class PublisherService implements SmartLifecycle {

    private final PublisherSettings settings;
    private final SyntheticOrderGenerator orderGenerator;
    private final List<QueuePublishLoop> publishLoops = new ArrayList<>();
    private volatile boolean running;
    private PublisherConnection connection;

    public PublisherService(PublisherSettings settings, SyntheticOrderGenerator orderGenerator) {
        this.settings = settings;
        this.orderGenerator = orderGenerator;
    }

    @Override
    public void start() {
        connection = new PublisherConnection(settings);
        connection.start();

        for (PublisherSettings.PublishTarget target : settings.targets()) {
            QueuePublishLoop loop = new QueuePublishLoop(settings.clientName(), target, connection, orderGenerator);
            publishLoops.add(loop);
            loop.start();
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        for (QueuePublishLoop loop : publishLoops) {
            loop.stop();
        }
        publishLoops.clear();
        if (connection != null) {
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
}
