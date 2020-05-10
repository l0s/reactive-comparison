package reactive;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import jdbc.config.JdbcConfiguration;
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

}