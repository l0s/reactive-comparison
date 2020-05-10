package blocking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackageClasses = ApplicationConfiguration.class)
public class BlockingDemoApplication {

    public static void main(final String[] args) {
        SpringApplication.run(BlockingDemoApplication.class, args);
    }

}