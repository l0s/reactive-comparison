package com.macasaet.messaging;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import com.macasaet.jdbc.config.JdbcConfiguration;

@Configuration
@Import(value = {JdbcConfiguration.class})
@ComponentScan({"com.macasaet.messaging"})
public class SpringConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ForwardedHeaderTransformer forwardedHeaderTransformer() {
        return new ForwardedHeaderTransformer();
    }

}