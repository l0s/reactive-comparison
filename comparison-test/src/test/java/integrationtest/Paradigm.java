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
package integrationtest;

import java.util.Objects;
import java.util.function.Function;

import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Network;

import async.AsyncDemoApplication;
import blocking.BlockingDemoApplication;
import reactive.ReactiveDemoApplication;

enum Paradigm {

    /**
     * Netty + Spring WebFlux (blocking)
     */
    BLOCKING(BlockingDemoApplication::run, BlockingContainer::new),
    /**
     * Undertow + Sring Web (async servlet)
     */
    ASYNC(AsyncDemoApplication::run, AsyncContainer::new),
    /**
     * Netty + Spring WebFlux + Project Reactor
     */
    REACTIVE(ReactiveDemoApplication::run, ReactiveContainer::new),
    ;

    private final Function<String[], ConfigurableApplicationContext> mainMethod;
    private final Function<Network, ApplicationContainer> containerCreator;

    private Paradigm(final Function<String[], ConfigurableApplicationContext> mainMethod,
            final Function<Network, ApplicationContainer> containerCreator) {
        Objects.requireNonNull(mainMethod);
        Objects.requireNonNull(containerCreator);
        this.mainMethod = mainMethod;
        this.containerCreator = containerCreator;
    }

    public ConfigurableApplicationContext run(final String... arguments) {
        return mainMethod.apply(arguments);
    }

    public ApplicationContainer createContainer(final Network network) {
        return containerCreator.apply(network);
    }

}