package com.bmo.amps.subscriber;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code amps.*} from application.yml. There is exactly one endpoint/queue to configure —
 * {@code uris} lists every failover candidate (e.g. a primary and a secondary AMPS instance — see
 * {@code docs/HA_AMPS_LAB_SETUP_GUIDE.md}) so {@code DefaultServerChooser} can try each one, and
 * {@code queue} is the single AMPS queue all clients subscribe to.
 *
 * <p>{@link #clients()} generates {@code clientCount} {@code ClientDef}s (named {@code CLIENT_1..N}),
 * all sharing the same {@code uris}/{@code queue} — this is the configurable number of AMPS reader
 * threads (HAClients): each generated def gets its own connection and its own reader thread, all
 * pulling off the same queue as competing consumers.
 *
 * <pre>
 * amps:
 *   uris:
 *     - tcp://172.21.12.69:9107/amps/json
 *     - tcp://172.21.12.69:9117/amps/json
 *   queue: /queue/orders
 *   client-count: 3
 * </pre>
 */
@ConfigurationProperties(prefix = "amps")
public record AmpsSubscribeConfig(List<String> uris, String queue, int clientCount) {

    public AmpsSubscribeConfig {
        if (uris == null) {
            uris = List.of();
        }
        if (clientCount <= 0) {
            clientCount = 1;
        }
    }

    public record ClientDef(String name, List<String> uris, String queue) {
    }

    /** Generates {@code clientCount} defs, all sharing {@code uris}/{@code queue}, for parallel processing. */
    public List<ClientDef> clients() {
        List<ClientDef> defs = new ArrayList<>(clientCount);
        for (int i = 0; i < clientCount; i++) {
            String cliName = queue.replaceFirst("/","").replaceFirst("/","_") + "_subscriber_" + i;
            defs.add(new ClientDef(cliName, uris, queue));
        }
        return defs;
    }
}
