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

import org.testcontainers.containers.Network;

class BlockingContainer extends ApplicationContainer {
    public BlockingContainer(final Network network) {
        // TODO parameterise artifactId
        // TODO extract version from filtered file
        super("traditional-webapp:0.0.0-SNAPSHOT", network);

        withFileSystemBind("src/test/resources/application-configuration", "/etc/traditional-webapp/");
    }
}