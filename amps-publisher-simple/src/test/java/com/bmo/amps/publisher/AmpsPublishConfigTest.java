package com.bmo.amps.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class AmpsPublishConfigTest {

    private static final List<String> URIS =
            List.of("tcp://localhost:9007/amps/json", "tcp://localhost:9107/amps/json");

    @Test
    void blankClientNameDefaultsToTimestampedName() {
        AmpsPublishConfig config = new AmpsPublishConfig(" ", URIS, null);

        assertThat(config.clientName()).matches("order-publisher-\\d{8}-\\d{9}");
    }

    @Test
    void blankClientNameDefaultsAvoidCollisionsAcrossInstances() throws InterruptedException {
        AmpsPublishConfig first = new AmpsPublishConfig(null, URIS, null);
        Thread.sleep(2);
        AmpsPublishConfig second = new AmpsPublishConfig(null, URIS, null);

        assertThat(first.clientName()).isNotEqualTo(second.clientName());
    }

    @Test
    void nullUrisDefaultsToEmptyList() {
        AmpsPublishConfig config = new AmpsPublishConfig("pub", null, null);

        assertThat(config.uris()).isEmpty();
    }

    @Test
    void emptyTargetsDefaultsToSingleOrdersQueueTarget() {
        AmpsPublishConfig config = new AmpsPublishConfig("pub", URIS, List.of());

        assertThat(config.targets()).hasSize(1);
        AmpsPublishConfig.Target target = config.targets().get(0);
        assertThat(target.queue()).isEqualTo("orders.queue");
        assertThat(target.rps()).isEqualTo(10.0);
        assertThat(target.burst()).isEqualTo(20);
    }

    @Test
    void targetNonPositiveRpsAndBurstAreDefaulted() {
        AmpsPublishConfig.Target target = new AmpsPublishConfig.Target("risk.queue", -5, 0);

        assertThat(target.rps()).isEqualTo(10.0);
        assertThat(target.burst()).isEqualTo(10);
    }

    @Test
    void targetBlankQueueDefaults() {
        AmpsPublishConfig.Target target = new AmpsPublishConfig.Target("  ", 5, 5);

        assertThat(target.queue()).isEqualTo("orders.queue");
    }

    @Test
    void explicitTargetsArePreservedAsGiven() {
        AmpsPublishConfig config = new AmpsPublishConfig(
                "pub", URIS, List.of(new AmpsPublishConfig.Target("trades.queue", 50, 100)));

        assertThat(config.targets()).hasSize(1);
        assertThat(config.targets().get(0).queue()).isEqualTo("trades.queue");
        assertThat(config.targets().get(0).rps()).isEqualTo(50);
        assertThat(config.targets().get(0).burst()).isEqualTo(100);
    }
}
