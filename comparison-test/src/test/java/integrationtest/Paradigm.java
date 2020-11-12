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

import org.testcontainers.containers.Network;

enum Paradigm {

    /**
     * Netty + Spring WebFlux (blocking)
     */
    BLOCKING("traditional-webapp"),
    /**
     * Tomcat + Sring Web (async servlet)
     */
    ASYNC("async-webapp"),
    /**
     * Netty + Spring WebFlux + Project Reactor
     */
    REACTIVE("reactive-webapp"),
    ;

    private final String artifactId;

    private Paradigm(final String artifactId) {
        Objects.requireNonNull(artifactId);
        this.artifactId = artifactId;
    }

    public ApplicationContainer createContainer(final Network network) {
        return new ApplicationContainer(artifactId, network);
    }

}