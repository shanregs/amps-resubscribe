package com.bmo.amps.subscriber;

import com.crankuptheamps.client.Message;

/**
 * Model for one delivered message. Wraps the raw AMPS {@link Message} (needed for {@code ack()})
 * alongside the fields business code actually cares about, so {@link MessageProcessor} doesn't need
 * to know about the AMPS SDK directly.
 */
public record AmpsMessage(String clientName, String topic, String sowKey, String data, Message raw) {

    public static AmpsMessage from(String clientName, Message raw) {
        return new AmpsMessage(clientName, raw.getTopic(), raw.getSowKey(), raw.getData(), raw);
    }
}
