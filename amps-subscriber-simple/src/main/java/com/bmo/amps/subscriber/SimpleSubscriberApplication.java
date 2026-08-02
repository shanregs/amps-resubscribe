package com.bmo.amps.subscriber;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SimpleSubscriberApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimpleSubscriberApplication.class, args);
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println( "Interrupted");
            throw new RuntimeException(e);
        }
    }
}
