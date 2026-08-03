package com.bmo.amps.publisher.readable;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PublisherApplication {

    public static void main(String[] args) throws InterruptedException {
        SpringApplication.run(PublisherApplication.class, args);
        Thread.currentThread().join();
    }
}
