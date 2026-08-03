package com.bmo.amps.publisher.readable;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.exception.AMPSException;

/**
 * Publishes to one target queue at its configured rate, on its own thread. Publishing pauses
 * (without burning rate-limit tokens) whenever {@link PublisherConnection#isLoggedOn()} is false, and
 * resumes automatically the moment the connection reports {@code LoggedOn} again.
 */
final class QueuePublishLoop {

    private static final Logger log = LoggerFactory.getLogger(QueuePublishLoop.class);
    private static final long DISCONNECTED_POLL_MS = 200;
    private static final long STOP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    private final String clientName;
    private final PublisherSettings.PublishTarget target;
    private final PublisherConnection connection;
    private final SyntheticOrderGenerator orderGenerator;
    private final TokenBucketRateLimiter rateLimiter;

    private volatile boolean running;
    private volatile Thread thread;

    QueuePublishLoop(String clientName, PublisherSettings.PublishTarget target,
                      PublisherConnection connection, SyntheticOrderGenerator orderGenerator) {
        this.clientName = clientName;
        this.target = target;
        this.connection = connection;
        this.orderGenerator = orderGenerator;
        this.rateLimiter = new TokenBucketRateLimiter(target.rps(), target.burst());
    }

    void start() {
        running = true;
        thread = Thread.ofVirtual().name("amps-publisher-" + target.queue()).start(this::run);
    }

    private void run() {
        log.info("{} Publisher started [queue={}, rps={}, burst={}]",
                clientName, target.queue(), target.rps(), target.burst());

        while (running) {
            if (!connection.isLoggedOn()) {
                sleepQuietly(DISCONNECTED_POLL_MS);
                continue;
            }
            if (!waitForNextToken()) {
                break;
            }
            if (running) {
                publishOne();
            }
        }

        log.info("{} Publisher stopped [queue={}]", clientName, target.queue());
    }

    /** Returns false if the wait was interrupted (i.e. the loop should exit), true otherwise. */
    private boolean waitForNextToken() {
        try {
            rateLimiter.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void publishOne() {
        String orderPayload = orderGenerator.nextOrderFor(target.queue());
        try {
            connection.publish(target.queue(), orderPayload);
            log.info("{} Publisher published [queue={}], data: {}", clientName, target.queue(), orderPayload);
        } catch (AMPSException e) {
            log.debug("{} Publish failed [queue={}]: {}", clientName, target.queue(), e.toString());
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void stop() {
        running = false;
        Thread currentThread = thread;
        if (currentThread != null) {
            try {
                currentThread.join(STOP_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
