package com.bmo.amps.resubscribe.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bmo.amps.resubscribe.lifecycle.AmpsLifecycleManager;
import com.bmo.amps.resubscribe.manager.AmpsClientManager;
import com.bmo.amps.resubscribe.manager.DefaultAmpsClientManager;
import com.bmo.amps.resubscribe.processor.LoggingMessageProcessor;
import com.bmo.amps.resubscribe.processor.MessageProcessor;
import com.bmo.amps.resubscribe.reader.AmpsMessageReader;
import com.bmo.amps.resubscribe.reader.DefaultAmpsMessageReader;

@Configuration
@EnableConfigurationProperties(AmpsProperties.class)
public class AmpsConfiguration {

    @Bean
    public AmpsClientManager ampsClientManager() {
        return new DefaultAmpsClientManager();
    }

    /**
     * Default no-op-ish {@link MessageProcessor}. Declare your own {@code MessageProcessor} bean in
     * application code to override — Spring picks the user-defined bean over this one because this
     * one is only registered {@code @ConditionalOnMissingBean}.
     */
    @Bean
    @ConditionalOnMissingBean(MessageProcessor.class)
    public MessageProcessor messageProcessor() {
        return new LoggingMessageProcessor();
    }

    @Bean
    public AmpsMessageReader ampsMessageReader(MessageProcessor messageProcessor) {
        return new DefaultAmpsMessageReader(messageProcessor);
    }

    @Bean
    public AmpsLifecycleManager ampsLifecycleManager(
            AmpsProperties properties, AmpsClientManager manager, AmpsMessageReader reader) {
        return new AmpsLifecycleManager(properties, manager, reader);
    }
}
