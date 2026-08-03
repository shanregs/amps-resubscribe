package com.bmo.amps.subscriber.readable;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code amps.*} from application.yml.
 *
 * <pre>
 * amps:
 *   uris:
 *     - tcp://172.21.12.69:9107/amps/json
 *     - tcp://172.21.12.69:9117/amps/json
 *   queue: /queue/orders
 *   client-count: 3
 * </pre>
 *
 * {@code uris} lists every failover candidate (primary/secondary AMPS instance — see
 * {@code docs/HA_AMPS_LAB_SETUP_GUIDE.md}) and {@code queue} is the single queue every client
 * subscribes to. {@link #clientDefinitions()} turns {@code clientCount} into that many
 * {@link ClientDefinition}s (named {@code <queue>_subscriber_0}, {@code _1}, ...), each getting its
 * own AMPS connection and its own reader thread — all pulling off the same queue as competing
 * consumers, so raising {@code client-count} raises how many reader threads share the queue's load.
 */
@ConfigurationProperties(prefix = "amps")
public record SubscriberSettings(List<String> uris, String queue, int clientCount) {

    public SubscriberSettings {
        if (uris == null) {
            uris = List.of();
        }
        if (clientCount <= 0) {
            clientCount = 1;
        }
    }

    /** Everything one {@code QueueSubscription} needs to connect and subscribe. */
    public record ClientDefinition(String clientName, List<String> uris, String queue) {
    }

    public List<ClientDefinition> clientDefinitions() {
        List<ClientDefinition> definitions = new ArrayList<>(clientCount);
        String queuePrefix = queue.replaceFirst("/", "").replaceFirst("/", "_");
        for (int i = 0; i < clientCount; i++) {
            definitions.add(new ClientDefinition(queuePrefix + "_subscriber_" + i, uris, queue));
        }
        return definitions;
    }
}
