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

import com.google.common.util.concurrent.RateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.Network;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

@Execution(ExecutionMode.SAME_THREAD)
public class LatencyVsThroughputIT {

    /*
     * Reporting Output
     */
    private static final File latencyReport = new File("latency_vs_throughput.tsv");
    private static ExecutorService latencyQueue;
    private static PrintWriter latencyOutput;

    /*
     * Client settings
     */
    private final Random random = new Random();
    private final RandomStringGenerator stringGenerator = new RandomStringGenerator(random);

    private final Duration testDuration = Duration.ofSeconds(15);
    private final Duration warmupTime = Duration.ofSeconds(5);
    private final int userCount = 32; // decreasing this value increases contention

    @BeforeAll
    public static void setUpOutput() throws IOException {
        latencyQueue = Executors.newSingleThreadExecutor();
        latencyOutput = new PrintWriter(latencyReport, StandardCharsets.UTF_8);
    }

    @AfterAll
    public static void closeOutput() throws InterruptedException {
        latencyQueue.shutdown();

        latencyQueue.awaitTermination(2, TimeUnit.SECONDS);
        latencyOutput.flush();
        latencyOutput.close();
    }

    @TestFactory
    Stream<DynamicNode> loadFactors() {
        return IntStream.of(10, 50, 100, 200, 300, 400, 500, 600, 700, 800)
                .mapToObj(throughput -> dynamicContainer(throughput + " requests per second",
                        Arrays.stream(Paradigm.values())
                                .map(paradigm -> {
                                    final var network = Network.newNetwork();
                                    final var database = new DatabaseContainer(network);
                                    database.start();

                                    final var application = paradigm.createContainer(network);
                                    application.start();

                                    final var baseUrl = application.getBaseUrl();
                                    final var userIds = new Vector<UUID>();

                                    random.setSeed(1966); // ensure the same sequence of events regardless of the test running

                                    return dynamicContainer("Paradigm: " + paradigm + " at " + throughput + "rps",
                                            Stream.of(dynamicTest("create users", () -> createUsers(baseUrl, userIds::add)),
                                                    dynamicTest("benchmark", () -> benchmarkLatencyAgainstThroughput(paradigm, baseUrl, throughput, userIds)),
                                                    dynamicTest("stop application", application::stop),
                                                    dynamicTest("stop database", database::stop),
                                                    dynamicTest("stop network", network::close)));
                                })));
    }

    protected void benchmarkLatencyAgainstThroughput(final Paradigm paradigm, final String baseUrl,
                                                     final int requestsPerSecond,
                                                     final List<? extends UUID> userIds) throws InterruptedException {
        final var limiter = RateLimiter.create(requestsPerSecond, warmupTime.toSeconds(), TimeUnit.SECONDS);
        BiConsumer<String, Duration> logger = (String operation, Duration duration) -> {
        };
        sendAndReceiveMessages(baseUrl, userIds, limiter, warmupTime, logger);
        logger = (String operation, Duration duration) -> {
            logLatency(paradigm, operation, duration, requestsPerSecond);
        };
        sendAndReceiveMessages(baseUrl, userIds, limiter, testDuration, logger);
    }

    protected void sendAndReceiveMessages(final String baseUrl,
                                          final List<? extends UUID> userIds, final RateLimiter limiter,
                                          final Duration duration,
                                          final BiConsumer<String, Duration> logger) throws InterruptedException {
        final var executor = new ThreadPoolExecutor(32, 32, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        final var clientExecutor = new ThreadPoolExecutor(32, 32, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        final var client = HttpClient.newBuilder().executor(clientExecutor).build();

        final var deadline = Instant.now().plus(duration);
        while (Instant.now().isBefore(deadline)) {
            executor.execute(() -> sendAndReceiveMessage(baseUrl, userIds, limiter, deadline, client, logger));
        }
        executor.shutdown();
        clientExecutor.shutdown();
        executor.shutdownNow();
        clientExecutor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        clientExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }

    protected void sendAndReceiveMessage(final String baseUrl,
                                         final List<? extends UUID> userIds, final RateLimiter limiter,
                                         final Instant deadline,
                                         final HttpClient client, final BiConsumer<String, Duration> logger) {
        limiter.acquire(); // TODO don't have to wait past the deadline

        final int senderIndex = random.nextInt(userIds.size());
        int recipientIndex = senderIndex;
        while (recipientIndex == senderIndex) {
            recipientIndex = random.nextInt(userIds.size());
        }
        final var sender = userIds.get(senderIndex);
        final var recipient = userIds.get(recipientIndex);

        if (Instant.now().isAfter(deadline)) {
            return;
        }
        final var bodyPublisher = BodyPublishers.ofString(stringGenerator.generateString(8192));
        try {
            final var sendRequest = HttpRequest.newBuilder(new URI(baseUrl + "/users/" + sender.toString() + "/messages/outgoing/"
                    + recipient.toString()))
                    .POST(bodyPublisher)
                    .build();
            final var sendStartTime = Instant.now();
            client.sendAsync(sendRequest, new JsonNodeBodyHandler()).handleAsync((response, throwable) -> {
                if (throwable != null) {
                    return CompletableFuture.failedStage(throwable);
                } else if (Instant.now().isAfter(deadline)) {
                    return CompletableFuture.failedStage(new TimeoutException());
                }
                if (response.statusCode() != 201) {
                    logger.accept("SND", Duration.ofSeconds(6));
                    return CompletableFuture.failedStage(new RuntimeException("Unable to send message"));
                }
                logger.accept("SND", Duration.between(sendStartTime, Instant.now()));
                final var messageUrl = response.headers().firstValue("Location").orElseThrow();
                final var messagesUrl = messageUrl.replaceFirst("messages/.*$", "messages");
                return CompletableFuture.completedFuture(messagesUrl);
            }).thenCompose(urlStage -> urlStage.handleAsync((messagesUrl, t) -> {
                if (Instant.now().isAfter(deadline)) {
                    return CompletableFuture.failedStage(new TimeoutException());
                }
                if (t != null) {
                    return CompletableFuture.failedStage(t);
                }
                try {
                    final var retrieveRequest = HttpRequest.newBuilder(new URI((String) messagesUrl)) // FIXME why is cast needed here?
                            .GET()
                            .build();
                    final var retrieveStart = Instant.now();
                    return client.sendAsync(retrieveRequest, new JsonNodeBodyHandler()).thenAccept(result -> {
                        if (Instant.now().isAfter(deadline)) {
                            return;
                        }
                        if (result.statusCode() != 200) {
                            logger.accept("RET", Duration.ofSeconds(6));
                            return;
                        }
                        logger.accept("RET", Duration.between(retrieveStart, Instant.now()));
                    });
                } catch (final URISyntaxException use) {
                    return CompletableFuture.failedStage(use);
                }
            })).join();
        } catch (final URISyntaxException use) {
            use.printStackTrace();
        }
    }

    protected void createUsers(String baseUrl, final Consumer<? super UUID> userIdSink) throws IOException, InterruptedException {
        baseUrl = baseUrl + "/users/";
        final var uri = URI.create(baseUrl);
        final var client = HttpClient.newHttpClient();
        for (int i = userCount; --i >= 0; ) {
            final var bodyPublisher = BodyPublishers.ofString("{\"name\":\"user_" + i + "\"}");
            final var request = HttpRequest.newBuilder(uri)
                    .POST(bodyPublisher)
                    .header("Content-Type", "application/json")
                    .build();
            final var response = client.send(request, BodyHandlers.discarding());
            final var urlString = response.headers().firstValue("Location").orElseThrow();
            final var idString = urlString.replaceAll("^.*/users/", ""); // TODO pre-compile
            userIdSink.accept(UUID.fromString(idString));
        }
    }

    protected static void logLatency(final Paradigm paradigm, final String operation, final Duration latency, final int offeredRps) {
        latencyQueue.execute(() -> {
            latencyOutput.append(paradigm.name());
            latencyOutput.append('\t');
            latencyOutput.append(operation);
            latencyOutput.append('\t');
            latencyOutput.print(offeredRps);
            latencyOutput.append('\t');
            latencyOutput.print(latency.toMillis());
            latencyOutput.println();
            latencyOutput.flush();
        });
    }
}
