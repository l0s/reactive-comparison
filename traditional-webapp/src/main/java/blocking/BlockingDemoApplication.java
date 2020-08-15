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
package blocking;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(scanBasePackageClasses = ApplicationConfiguration.class)
public class BlockingDemoApplication {

    public static void main(final String[] args) {
        run(args);
    }

    public static ConfigurableApplicationContext run(final String[] arguments) {
        final var builder =
                new SpringApplicationBuilder(BlockingDemoApplication.class)
                .web(WebApplicationType.REACTIVE);
        final var application = builder.build();
        return application.run(arguments);
    }

}