package com.macasaet.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackageClasses = SpringConfiguration.class)
public class Application {

    public static void main(final String[] args) {
        final Logger logger = LoggerFactory.getLogger(Application.class);
        try (var context = SpringApplication.run(Application.class, args)) {
            logger.info("-- started context: {}", context);
        }
    }

}