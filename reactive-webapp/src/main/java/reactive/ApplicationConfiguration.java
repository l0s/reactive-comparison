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
package reactive;

import static org.springframework.hateoas.config.EnableHypermediaSupport.HypermediaType.HAL;
import static org.springframework.hateoas.support.WebStack.WEBFLUX;

import java.time.Clock;

import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.hateoas.config.EnableHypermediaSupport;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import com.zaxxer.hikari.HikariConfig;

import jdbc.config.JdbcConfiguration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.Loggers;

@Configuration
@Import(value = {JdbcConfiguration.class})
@ComponentScan({"reactive"})
@EnableHypermediaSupport(stacks = WEBFLUX, type = HAL)
public class ApplicationConfiguration {

    static {
        Loggers.useSl4jLoggers();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ForwardedHeaderTransformer forwardedHeaderTransformer() {
        return new ForwardedHeaderTransformer();
    }

    /**
     * <p>
     * Create a dedicated scheduler for JDBC operations with number of
     * threads set to the maximum JDBC pool size.
     * </p>
     * <p>
     * This creates an additional thread pool, which may give the reactive
     * implementation an unfair advantage. However, without it, the JDBC
     * connection pool gets overwhelmed.
     * </p>
     * 
     * @see https://dzone.com/articles/spring-5-webflux-and-jdbc-to-block-or-not-to-block
     * @return Scheduler to use for JDBC operations
     */
    @Bean(name = "databaseScheduler")
    public Scheduler databaseScheduler(final HikariConfig config) {
//        return Schedulers.immediate();
        return Schedulers.newParallel("jdbc-worker", config.getMaximumPoolSize());
    }

    @Bean
    public ReactiveWebServerFactory webServerFactory() {
        return new NettyReactiveWebServerFactory();
    }

}