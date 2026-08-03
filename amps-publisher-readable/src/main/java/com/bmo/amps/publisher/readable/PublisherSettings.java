package com.bmo.amps.publisher.readable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Binds {@code amps.publisher.*} from application.yml.
 *
 * <pre>
 * amps:
 *   publisher:
 *     client-name: order-publisher
 *     uris:
 *       - tcp://172.21.12.69:9107/amps/json
 *       - tcp://172.21.12.69:9117/amps/json
 *     targets:
 *       - queue: orders.queue
 *         rps: 20
 *         burst: 40
 * </pre>
 *
 * {@code uris} lists every failover candidate (same convention as {@code amps-subscriber-readable}'s
 * {@code SubscriberSettings}), and {@code targets} lists every queue this publisher feeds, each with
 * its own sustained rate ({@code rps}) and burst allowance ({@code burst}).
 */
@ConfigurationProperties(prefix = "amps.publisher")
public record PublisherSettings(String clientName, List<String> uris, List<PublishTarget> targets) {

    private static final String DEFAULT_QUEUE = "orders.queue";
    private static final double DEFAULT_RPS = 10.0;
    private static final int DEFAULT_BURST = 20;
    private static final DateTimeFormatter CLIENT_NAME_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    public PublisherSettings {
        String baseName = StringUtils.hasText(clientName) ? clientName : "order-publisher";
        // Timestamped so two publisher instances started around the same time never collide on AMPS
        // client name (AMPS logs/admin UI key connections by client name).
        clientName = baseName + "-" + LocalDateTime.now().format(CLIENT_NAME_SUFFIX);

        if (uris == null) {
            uris = List.of();
        }
        if (targets == null || targets.isEmpty()) {
            targets = List.of(new PublishTarget(DEFAULT_QUEUE, DEFAULT_RPS, DEFAULT_BURST));
        }
    }

    /** One queue this publisher feeds, plus its own rate limit — {@code rps} sustained, {@code burst} peak. */
    public record PublishTarget(String queue, double rps, int burst) {

        public PublishTarget {
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
