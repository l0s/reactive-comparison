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
import org.testcontainers.containers.PostgreSQLContainer;

class DatabaseContainer extends PostgreSQLContainer<DatabaseContainer> {
    public DatabaseContainer(final Network network) {
        super("postgres:12.2");
        withDatabaseName("db");
        withUsername("username");
        withPassword("password");
        withNetworkAliases("database");
        withNetwork(network);
    }
}