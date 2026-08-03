package com.bmo.amps.publisher.readable;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Generates synthetic order-event JSON payloads for {@link QueuePublishLoop} to publish. {@code id}
 * is a process-wide monotonically increasing sequence, so every published message carries a distinct
 * key regardless of which target queue's publish loop generated it.
 */
@Component
class SyntheticOrderGenerator {

    private static final String[] SYMBOLS = {"AAPL", "MSFT", "GOOG", "AMZN", "TSLA"};
    private static final String[] SIDES = {"BUY", "SELL"};

    private final AtomicLong sequence = new AtomicLong();

    String nextOrderFor(String queue) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long id = sequence.incrementAndGet();
        String symbol = SYMBOLS[random.nextInt(SYMBOLS.length)];
        String side = SIDES[random.nextInt(SIDES.length)];
        int qty = 1 + random.nextInt(1000);
        return "{\"id\":%d,\"symbol\":\"%s\",\"side\":\"%s\",\"qty\":%d,\"queue\":\"%s\",\"ts\":%d}"
                .formatted(id, symbol, side, qty, queue, System.currentTimeMillis());
    }
}
