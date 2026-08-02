package com.bmo.amps.subscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.crankuptheamps.client.Message;

class AmpsMessageTest {

    @Test
    void fromMapsFieldsFromTheRawMessage() {
        Message raw = mock(Message.class);
        when(raw.getTopic()).thenReturn("orders");
        when(raw.getSowKey()).thenReturn("key-1");
        when(raw.getData()).thenReturn("{\"id\":1}");

        AmpsMessage message = AmpsMessage.from("CLIENT_1", raw);

        assertThat(message.clientName()).isEqualTo("CLIENT_1");
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.sowKey()).isEqualTo("key-1");
        assertThat(message.data()).isEqualTo("{\"id\":1}");
        assertThat(message.raw()).isSameAs(raw);
    }
}
