/**
 * Copyright © 2020 Carlos Macasaet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jdbc.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.vavr.control.Either;

@Configuration
public class JdbcConfiguration {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final RetryConfig retryConfig =
            RetryConfig.custom()
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(16)))
            .maxAttempts(16)
            .retryExceptions(SQLTransientConnectionException.class)
            .build();
    private final RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
    private String jdbcUrl;
    private String username;
    private String password;

    // set pool size to 2x the number of CPUs: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
    private static final int maximumPoolSize = Runtime.getRuntime().availableProcessors() * 2;
    private int defaultFetchSize = 4;

    @Bean
    public HikariConfig hikariConfig() {
        final var config = new HikariConfig();
        config.setJdbcUrl(getJdbcUrl());
        config.setUsername(getUsername());
        config.setPassword(getPassword());
        config.setDriverClassName(Driver.class.getName());
        config.setMinimumIdle(getMaximumPoolSize());
        config.setMaximumPoolSize(getMaximumPoolSize());
        config.setConnectionTimeout(2_000);
        config.addDataSourceProperty(PGProperty.DEFAULT_ROW_FETCH_SIZE.getName(), defaultFetchSize);
        return config;
    }

    @Bean
    @Autowired
    public DataSource dataSource(final HikariConfig config) {
        final Retry retry = retryRegistry.retry("jdbc.DataSource.getConnection");
        return new HikariDataSource(config) {
            public Connection getConnection() throws SQLException {
                final var either = retry.executeEitherSupplier((Supplier<Either<SQLException, Connection>>) () -> {
                    try {
                        return Either.right(super.getConnection());
                    } catch (final SQLException se) {
                        logger.warn(se.getMessage(), se);
                        return Either.left(se);
                    }
                });
                if (either.isLeft()) {
                    throw either.getLeft();
                }
                return either.get();
            }

            public Connection getConnection(String username, String password) throws SQLException {
                final var either = retry.executeEitherSupplier((Supplier<Either<SQLException, Connection>>) () -> {
                    try {
                        return Either.right(super.getConnection(username, password));
                    } catch (final SQLException se) {
                        logger.warn(se.getMessage(), se);
                        return Either.left(se);
                    }
                });
                if (either.isLeft()) {
                    throw either.getLeft();
                }
                return either.get();
            }
        };
    }

    @Bean
    @Autowired
    public Flyway databaseMigration(final DataSource dataSource) {
        final Flyway retval = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        final int result = retval.migrate();
        logger.info("Applied {} DB migrations.", result);
        return retval;
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

    @Deprecated
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    @Deprecated
    @Value("${database.maximumPoolSize:4}")
    public void setMaximumPoolSize(final int maximumPoolSize) {
        if (maximumPoolSize != JdbcConfiguration.maximumPoolSize) {
            logger.info("Ignoring requested maximumPoolSize: {}", maximumPoolSize);
        }
    }

    public int getDefaultFetchSize() {
        return defaultFetchSize;
    }

    @Value("${database.defaultFetchSize:4}")
    public void setDefaultFetchSize(final int defaultFetchSize) {
        if (defaultFetchSize < 0) {
            throw new IllegalArgumentException("fetchSize must be non-negative");
        }
        this.defaultFetchSize = defaultFetchSize;
    }

}