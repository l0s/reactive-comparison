package com.macasaet.jdbc.config;

import javax.sql.DataSource;

import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class JdbcConfiguration {

    @Bean
    public HikariConfig hikariConfig() {
        final var config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5435/db");  // FIXME parameterise host, port, database
        config.setDriverClassName(Driver.class.getName());
        config.setUsername("");
        config.setPassword("");
        config.setMaximumPoolSize(2);
        return config;
    }

    @Bean
    @Autowired
    public DataSource dataSource(final HikariConfig config) {
        return new HikariDataSource(config);
    }

}