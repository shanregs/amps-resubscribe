package com.bmo.amps.resubscribe.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.Message;

import com.bmo.amps.resubscribe.model.ClientType;

/**
 * Default {@link MessageProcessor} used when the application does not supply its own bean. Logs the
 * message so the framework is observably working end-to-end before real business logic is wired in;
 * not intended for production use as-is.
 */
public class LoggingMessageProcessor implements MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageProcessor.class);

    @Override
    public void process(ClientType source, Message message) {
        log.info("{} Message received [topic={}, sowKey={}]", source, message.getTopic(), message.getSowKey());
    }
}
