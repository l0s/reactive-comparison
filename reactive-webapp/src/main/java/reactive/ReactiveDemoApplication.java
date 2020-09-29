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

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import spring.StartupListener;

@SpringBootApplication(scanBasePackageClasses = ApplicationConfiguration.class)
public class ReactiveDemoApplication {

    public static void main(final String[] args) {
        final var builder =
                new SpringApplicationBuilder(ReactiveDemoApplication.class)
                .web(WebApplicationType.REACTIVE)
                .listeners(new StartupListener());
        final var application = builder.build();
        application.run(args);
    }

}