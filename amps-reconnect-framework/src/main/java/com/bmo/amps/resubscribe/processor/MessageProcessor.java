package com.bmo.amps.resubscribe.processor;

import com.crankuptheamps.client.Message;

import com.bmo.amps.resubscribe.model.ClientType;

/**
 * Business logic for a single message, stateless with respect to connection lifecycle
 * (DESIGN.md {@literal §}2). {@code AmpsMessageReader} catches and logs any exception this throws so a
 * processing bug can never kill a reader thread (THREADING_MODEL.md {@literal §}3).
 * <p>
 * Provide your own Spring bean of this type to replace {@link LoggingMessageProcessor}.
 */
@FunctionalInterface
public interface MessageProcessor {

    void process(ClientType source, Message message);
}
