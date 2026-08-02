package com.bmo.amps.resubscribe.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code amps.clients} from application.yml. See DESIGN.md {@literal §}7 for the expected shape.
 */
@ConfigurationProperties(prefix = "amps")
public record AmpsProperties(List<AmpsClientDefinition> clients) {

    public AmpsProperties {
        if (clients == null) {
            clients = List.of();
        }
    }
}
