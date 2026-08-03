package com.bmo.amps.subscriber.readable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.crankuptheamps.client.exception.AMPSException;

/**
 * Where business logic for one delivered message goes. Each message is handed off to its own
 * virtual thread, so a slow handler here never blocks a {@link QueueSubscription}'s reader loop from
 * continuing to pull the next message off the queue.
 */
@Component
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final ExecutorService perMessageThreads = Executors.newVirtualThreadPerTaskExecutor();

    public void handle(DeliveredMessage message) {
        perMessageThreads.submit(() -> process(message));
    }

    private void process(DeliveredMessage message) {
        try {
            // Business logic goes here — kept as a log line in this reference module.
            log.info("{} Message processed [topic={}, sowKey={}], data: {}",
                    message.clientName(), message.topic(), message.sowKey(), message.data());
            message.raw().ack();
        } catch (AMPSException e) {
            log.error("{} Failed to ack message [topic={}]", message.clientName(), message.topic(), e);
        } catch (RuntimeException e) {
            // A bug in business logic must never escape onto the reader loop's thread.
            log.error("{} Message processing failed [topic={}]", message.clientName(), message.topic(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        perMessageThreads.shutdown();
    }
}
