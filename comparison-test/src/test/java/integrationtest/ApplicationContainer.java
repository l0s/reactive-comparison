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

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.OutputFrame.OutputType;

class ApplicationContainer extends GenericContainer<ApplicationContainer> {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    protected static final String version;

    static {
        final var properties = new Properties();
        try (var inputStream = new FileInputStream("target/maven-archiver/pom.properties")) {
            properties.load(inputStream);
            if (!properties.containsKey("version")) {
                throw new IllegalStateException("version not available");
            }
            version = (String) properties.get("version");
        } catch (final IOException e) {
            throw new IllegalStateException("pom.properties not available: " + e.getMessage(), e);
        }
    }

    protected ApplicationContainer(final String artifactId, final Network network) {
        super(artifactId + ":" + version);
        withNetwork(network);
        withExposedPorts(8080);
        withLogConsumer(this::logFrame);
        withCreateContainerCmdModifier(command -> {
            final var hostConfig = command.getHostConfig();
            // simulate AWS t2.micro
            hostConfig.withCpuCount(2l);
            hostConfig.withMemory(1024l * 1024 * 1024);
        });
        withFileSystemBind("src/test/resources/application-configuration", "/etc/" + artifactId + "/");
    }

    public void stop() {
        logger.info("Stopping");
        super.stop();
    }

    public String getBaseUrl() {
        return "http://" + getContainerIpAddress() + ":" + getMappedPort(8080);
    }

    protected void logFrame(final OutputFrame frame) {
        @SuppressWarnings("resource")
        final var stream = frame.getType() == OutputType.STDOUT ? System.out : System.err;
        stream.print(frame.getUtf8String());
    }

}