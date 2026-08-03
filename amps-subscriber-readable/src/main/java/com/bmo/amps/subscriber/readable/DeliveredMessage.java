package com.bmo.amps.subscriber.readable;

import com.crankuptheamps.client.Message;

/**
 * One message handed from a {@link QueueSubscription}'s reader loop to {@link MessageHandler}.
 * Carries the raw AMPS {@link Message} (needed to call {@code ack()}) alongside the fields business
 * code actually reads, so {@link MessageHandler} doesn't need to know the AMPS SDK's own message type.
 */
public record DeliveredMessage(String clientName, String topic, String sowKey, String data, Message raw) {

    public static DeliveredMessage from(String clientName, Message raw) {
        return new DeliveredMessage(clientName, raw.getTopic(), raw.getSowKey(), raw.getData(), raw);
    }
}
