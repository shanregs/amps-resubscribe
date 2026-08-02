package com.bmo.amps.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderPayloadGeneratorTest {

    private final OrderPayloadGenerator generator = new OrderPayloadGenerator();

    @Test
    void payloadContainsRequestedQueueName() {
        String payload = generator.next("orders.queue");

        assertThat(payload).contains("\"queue\":\"orders.queue\"");
    }

    @Test
    void successivePayloadsHaveIncreasingIds() {
        String first = generator.next("orders.queue");
        String second = generator.next("orders.queue");

        long firstId = extractId(first);
        long secondId = extractId(second);

        assertThat(secondId).isGreaterThan(firstId);
    }

    private static long extractId(String payload) {
        String marker = "\"id\":";
        int start = payload.indexOf(marker) + marker.length();
        int end = payload.indexOf(',', start);
        return Long.parseLong(payload.substring(start, end));
    }
}
