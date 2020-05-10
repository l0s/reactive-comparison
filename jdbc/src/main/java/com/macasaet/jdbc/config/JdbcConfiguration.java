package com.macasaet.jdbc.config;

import javax.sql.DataSource;

import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class JdbcConfiguration {

    private String jdbcUrl;
    private String username;
    private String password;
    private int maximumPoolSize = 4;

    @Bean
    public HikariConfig hikariConfig() {
        final var config = new HikariConfig();
        config.setJdbcUrl(getJdbcUrl());
        config.setUsername(getUsername());
        config.setPassword(getPassword());
        config.setDriverClassName(Driver.class.getName());
        config.setMaximumPoolSize(getMaximumPoolSize());
        return config;
    }

    @Bean
    @Autowired
    public DataSource dataSource(final HikariConfig config) {
        return new HikariDataSource(config);
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    @Value("${database.url}")
    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    @Value("${database.username}")
    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    @Value("${database.password}")
    public void setPassword(String password) {
        this.password = password;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    @Value("${database.maximumPoolSize}")
    public void setMaximumPoolSize(final int maximumPoolSize) {
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize cannot be less than 1");
        }
        this.maximumPoolSize = maximumPoolSize;
    }

}