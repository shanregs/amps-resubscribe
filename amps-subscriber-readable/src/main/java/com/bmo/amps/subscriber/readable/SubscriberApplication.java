package com.bmo.amps.subscriber.readable;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SubscriberApplication {

    public static void main(String[] args) throws InterruptedException {
        SpringApplication.run(SubscriberApplication.class, args);
        Thread.currentThread().join();
    }
}
