package com.bmo.amps.publisher;

import java.lang.annotation.Target;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Binds {@code amps.publisher.*} from application.yml. {@code uris} lists every failover candidate
 * (same convention as {@code amps-reconnect-simple}'s {@code AmpsSubscribeConfig} — see
 * {@code docs/HA_AMPS_LAB_SETUP_GUIDE.md} for the lab's primary/secondary URIs), and {@code targets}
 * lists every queue this publisher feeds, each with its own sustained rate ({@code rps}) and burst
 * allowance ({@code burst}).
 *
 * <pre>
 * amps:
 *   publisher:
 *     client-name: order-publisher
 *     uris:
 *       - tcp://172.21.12.69:9007/amps/json
 *       - tcp://172.21.12.69:9107/amps/json
 *     targets:
 *       - queue: orders.queue
 *         rps: 20
 *         burst: 40
 * </pre>
 */
@ConfigurationProperties(prefix = "amps.publisher")
public record AmpsPublishConfig(String clientName, List<String> uris, List<Target> targets) {

    private static final String DEFAULT_QUEUE = "orders.queue";
    private static final double DEFAULT_RPS = 10.0;
    private static final int DEFAULT_BURST = 20;
    private static final DateTimeFormatter CLIENT_NAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    public AmpsPublishConfig {
        if (!StringUtils.hasText(clientName)) {
            // Timestamped so two default-named publisher instances started around the same time
            // never collide on AMPS client name (AMPS logs/admin UI key connections by client name).
            clientName = "order-publisher-" + LocalDateTime.now().format(CLIENT_NAME_TIMESTAMP);
        } else {
            clientName = clientName + LocalDateTime.now().format(CLIENT_NAME_TIMESTAMP);
        }
        if (uris == null) {
            uris = List.of();
        }
        if (targets == null || targets.isEmpty()) {
            targets = List.of(new Target(DEFAULT_QUEUE, DEFAULT_RPS, DEFAULT_BURST));
        }
    }

    /** One queue this publisher feeds, plus its own rate limit — {@code rps} sustained, {@code burst} peak. */
    public record Target(String queue, double rps, int burst) {

        public Target {
            if (queue == null || queue.isBlank()) {
                queue = DEFAULT_QUEUE;
            }
            if (rps <= 0) {
                rps = DEFAULT_RPS;
            }
            if (burst <= 0) {
                burst = (int) Math.max(1, Math.ceil(rps));
            }
        }
    }
}
