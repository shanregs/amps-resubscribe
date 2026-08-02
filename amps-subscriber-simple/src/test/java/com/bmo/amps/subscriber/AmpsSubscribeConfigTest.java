package com.bmo.amps.subscriber;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class AmpsSubscribeConfigTest {

    private static final List<String> URIS =
            List.of("tcp://localhost:9107/amps/json", "tcp://localhost:9117/amps/json");

    @Test
    void nullUrisDefaultsToEmptyList() {
        AmpsSubscribeConfig config = new AmpsSubscribeConfig(null, "/queue/orders", 1);

        assertThat(config.uris()).isEmpty();
    }

    @Test
    void nonPositiveClientCountDefaultsToOne() {
        AmpsSubscribeConfig config = new AmpsSubscribeConfig(URIS, "/queue/orders", 0);

        assertThat(config.clientCount()).isEqualTo(1);
        assertThat(config.clients()).hasSize(1);
    }

    @Test
    void clientsGeneratesOneDefPerClientCountSharingUrisAndQueue() {
        AmpsSubscribeConfig config = new AmpsSubscribeConfig(URIS, "/queue/orders", 3);

        List<AmpsSubscribeConfig.ClientDef> defs = config.clients();
        assertThat(defs).hasSize(3);
        assertThat(defs).extracting(AmpsSubscribeConfig.ClientDef::name)
                .containsExactly("CLIENT_1", "CLIENT_2", "CLIENT_3");
        for (AmpsSubscribeConfig.ClientDef def : defs) {
            assertThat(def.uris()).isEqualTo(URIS);
            assertThat(def.queue()).isEqualTo("/queue/orders");
        }
    }
}
