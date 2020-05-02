package reactive;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.macasaet.jdbc.config.JdbcConfiguration;

import reactor.util.Loggers;

@Configuration
@Import(value = {JdbcConfiguration.class})
public class ApplicationConfiguration {

    static {
        Loggers.useSl4jLoggers();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

}