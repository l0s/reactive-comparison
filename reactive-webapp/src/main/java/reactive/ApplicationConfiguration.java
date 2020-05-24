package reactive;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import com.zaxxer.hikari.HikariConfig;

import jdbc.config.JdbcConfiguration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.Loggers;

@Configuration
@Import(value = {JdbcConfiguration.class})
@ComponentScan({"reactive"})
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
     * Create a dedicated scheduler for JDBC operations with number of
     * threads set to the maximum JDBC pool size.
     * 
     * TODO since this creates an additional thread pool, it may give the reactive implementation an unfair advantage.
     * 
     * @see https://dzone.com/articles/spring-5-webflux-and-jdbc-to-block-or-not-to-block
     * @return Scheduler to use for JDBC operations
     */
    @Bean(name = "databaseScheduler")
    public Scheduler databaseScheduler(final HikariConfig config) {
        return Schedulers.newBoundedElastic(config.getMaximumPoolSize(), Integer.MAX_VALUE, "jdbc-worker");
    }

}