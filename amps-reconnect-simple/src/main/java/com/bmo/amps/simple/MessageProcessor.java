package com.bmo.amps.simple;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.crankuptheamps.client.exception.AMPSException;

/**
 * Business processing for one message, dispatched onto a virtual thread per message so a slow
 * handler never blocks {@link AmpsQueueClient}'s reader loop from continuing to pull off the queue.
 */
@Component
public class MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

    public void process(AmpsMessage message) {
        virtualThreads.submit(() -> handle(message));
    }

    private void handle(AmpsMessage message) {
        try {
            // Business logic goes here — kept as a log line in this simplified module.
            log.info("{} Message processed [topic={}, sowKey={}]",
                    message.clientName(), message.topic(), message.sowKey());
            message.raw().ack();
        } catch (AMPSException e) {
            log.error("{} Failed to ack message [topic={}]", message.clientName(), message.topic(), e);
        } catch (RuntimeException e) {
            // A processing bug here must never propagate back into the reader loop.
            log.error("{} Message processing failed [topic={}]", message.clientName(), message.topic(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        virtualThreads.shutdown();
    }
}
