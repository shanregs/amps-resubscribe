package com.bmo.amps.simple;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.exception.AMPSException;

class MessageProcessorTest {

    private final MessageProcessor processor = new MessageProcessor();

    @AfterEach
    void shutdown() {
        processor.shutdown();
    }

    @Test
    void processDispatchesAsynchronouslyAndAcksOnSuccess() {
        Message raw = mock(Message.class);
        AmpsMessage message = AmpsMessage.from("CLIENT_1", raw);

        processor.process(message);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(raw, times(1)).ack());
    }

    @Test
    void ackFailureIsSwallowedNotPropagated() throws Exception {
        Message raw = mock(Message.class);
        AmpsMessage message = AmpsMessage.from("CLIENT_1", raw);
        doThrow(new AMPSException("ack failed")).when(raw).ack();

        processor.process(message); // must not throw on the calling thread

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(raw, times(1)).ack());
    }

    @Test
    void manyMessagesAreAllProcessed() {
        int count = 50;
        Message[] rawMessages = new Message[count];
        for (int i = 0; i < count; i++) {
            rawMessages[i] = mock(Message.class);
            processor.process(AmpsMessage.from("CLIENT_1", rawMessages[i]));
        }

        for (Message raw : rawMessages) {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(raw, times(1)).ack());
        }
    }
}
