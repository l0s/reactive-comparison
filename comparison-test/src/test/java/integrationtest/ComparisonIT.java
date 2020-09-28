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

import static org.junit.Assume.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import net.bytebuddy.utility.RandomString;

@Execution(ExecutionMode.SAME_THREAD)
public class ComparisonIT {

    private static final Logger logger = LoggerFactory.getLogger(ComparisonIT.class);

    /*
     * Reporting Output
     */ 
    private static final File latencyReport = new File("latency.tsv");
    private static final File throughputReport = new File("throughput.tsv");

    private static ExecutorService latencyQueue;
    private static PrintWriter latencyOutput;
    private static ExecutorService throughputQueue;
    private static PrintWriter throughputOutput;

    /*
     * Client settings
     */
    private static final int clientDefaultPageSize = 13; // Force (REST) page size and (DB) fetch size mismatch
    private static final ExecutorService httpExecutor = Executors.newFixedThreadPool(16);
    private final Random random = new Random();
    private final HttpClient client =
            HttpClient.newBuilder()
            .executor(httpExecutor)
            .build();

    private final Duration throughputTestDuration = Duration.ofSeconds(15);
    private final int userCount = 64; // decreasing this value increases contention
    private final int messageCount = 2048;

    @BeforeAll
    public static void setUpOutput() throws FileNotFoundException, UnsupportedEncodingException {
        latencyQueue = Executors.newSingleThreadExecutor();
        latencyOutput = new PrintWriter(latencyReport, "UTF-8");
        throughputQueue = Executors.newSingleThreadExecutor();
        throughputOutput = new PrintWriter(throughputReport, "UTF-8");
    }

    @AfterAll
    public static void closeOutput() throws InterruptedException {
        latencyQueue.shutdown();
        throughputQueue.shutdown();

        latencyQueue.awaitTermination(2, TimeUnit.SECONDS);
        latencyOutput.flush();
        latencyOutput.close();

        throughputQueue.awaitTermination(2, TimeUnit.SECONDS);
        throughputOutput.flush();
        throughputOutput.close();
    }

    @AfterAll
    public static void shutdownExecutor() throws InterruptedException {
        httpExecutor.shutdown();
        httpExecutor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @TestFactory
    public Stream<DynamicNode> paradigmContainers() {
        return Arrays.stream(Paradigm.values())
            .map(paradigm -> {

                final var network = Network.newNetwork();
                @SuppressWarnings({"resource" }) // last test closes the database
                final var database = new DatabaseContainer(network);
                database.start();

                final var application = paradigm.createContainer(network);
                application.start();
                final var baseUrl = application.getBaseUrl();

                final var userIds = new ConcurrentLinkedQueue<UUID>();
                final var userUrls = new ConcurrentLinkedQueue<URL>();

                return dynamicContainer("paradigm: " + paradigm, Stream.of(
                    /*
                     * warm up
                     */
                    dynamicTest("no users returned", () -> verifyEmpty(baseUrl)),

                    /*
                     * benchmarks
                     */
                    dynamicTest("benchmark user creation", () -> benchmarkUserCreationLatency(baseUrl, paradigm, userUrls::addAll)),
                    dynamicTest("benchmark user pagination", () -> benchmarkUserPaginationLatency(baseUrl, paradigm, userUrls, userIds::add)),
                    dynamicTest("benchmark sending and receiving latency", () -> benchmarkSendingAndReceivingLatency(baseUrl, paradigm, userIds)),
                    dynamicTest("benchmark message sending throughput", () -> benchmarkSendingThroughput(baseUrl, paradigm, userIds)),
                    dynamicTest("benchmark message retrieval throughput", () -> benchmarkRetrievalThroughput(baseUrl, paradigm, userIds)),

                    /*
                     * clean up
                     */
                    dynamicTest("stop application", application::stop),
                    dynamicTest("stop database", database::stop),
                    dynamicTest("stop network", network::close)));
            });
    }

    /**
     * Verify that given an empty database, the API returns an array of zero
     * users. This is a cursory test to ensure that all layers of the
     * application are functioning properly.
     *
     * @param baseUrl the API prefix, should include scheme, host, and port
     */
    protected void verifyEmpty(String baseUrl) throws IOException, InterruptedException, URISyntaxException {
        // given
        baseUrl = baseUrl + "/users/";
        final var request = HttpRequest.newBuilder(new URI(baseUrl)).GET().build();

        // when
        final var response = client.send(request, new JsonNodeBodyHandler());

        // then
        assertEquals(200, response.statusCode());
        assertEquals(0, response.headers().allValues("Link").size());
        final var root = response.body();
        assertTrue(root.isArray(), "Not an array: " + root);
        assertTrue(root.isEmpty(), "Not empty: " + root);
    }

    /**
     * Verify that we can create {@link #userCount} users. Concurrency for
     * the operations is determined by the JVM.
     * @param baseUrl the API prefix, should include scheme, host, and port
     * @param paradigm the type of application backing the API
     * @param sink     destination for all of the user URLs generated
     */
    protected void benchmarkUserCreationLatency(final String baseUrl,
            final Paradigm paradigm,
            final Consumer<? super Collection<? extends URL>> sink) {
        
        final var startTime = Instant.now();
        final var urls = IntStream.range(0, userCount)
            .parallel()
            .mapToObj(i -> createUser(baseUrl, paradigm, i))
            .collect(Collectors.toSet());
        logLatency(paradigm, TimingMetric.CREATE_ALL_USERS, Duration.between(startTime, Instant.now()));
        sink.accept(urls);
    }

    protected URL createUser(String baseUrl, final Paradigm paradigm, final Object usernameSuffix) {
        baseUrl = baseUrl + "/users/";
        final var uri =  URI.create(baseUrl);
        final var bodyPublisher = BodyPublishers.ofString("{\"name\":\"user_" + usernameSuffix + "\"}");
        final var request = HttpRequest.newBuilder(uri)
            .POST(bodyPublisher)
            .header("Content-Type", "application/json")
            .build();

        final var start = Instant.now();
        try {
            final var response = client.send(request, BodyHandlers.discarding());
            logLatency(paradigm, TimingMetric.CREATE_SINGLE_USER, Duration.between(start, Instant.now()));
            assertEquals(201, response.statusCode());

            final var urlString = response.headers().firstValue("Location").get();

            return new URL(urlString);
        } catch (final IOException | InterruptedException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Verify that we can paginate through all of the users.
     * @param baseUrl the API prefix, should include scheme, host, and port
     * @param paradigm the type of application backing the API
     * @param urls all of the user URLs 
     * @param userIdSink a destination for all of the user IDs
     */
    protected void benchmarkUserPaginationLatency(String baseUrl, final Paradigm paradigm,
            final Collection<? extends URL> urls, final Consumer<? super UUID> userIdSink) throws IOException, InterruptedException, URISyntaxException {
        // given
        assumeTrue(urls.size() > 0);
        baseUrl = baseUrl + "/users/";
        final var startTime = Instant.now();

        // when
        var count = 0;
        Optional<URI> next = Optional.of(new URI(baseUrl + "?pageNum=0&pageSize=" + clientDefaultPageSize));
        while (next.isPresent()) {
            final var request = HttpRequest
                .newBuilder(next.get())
                .GET()
                .build();
            final var start = Instant.now();
            final var response = client.send(request, new JsonNodeBodyHandler());
            logLatency(paradigm, TimingMetric.GET_PAGE_OF_USERS, Duration.between(start, Instant.now()));
            assertEquals(200, response.statusCode());
            final var root = response.body();
            assertTrue(root.isArray());
            count += root.size();
            for (final var user : root) {
                final var id = user.get("id").asText();
                final var userUrl = new URL(baseUrl + id.toString());
                assertTrue(urls.contains(userUrl), "unexpected url: " + userUrl);
                userIdSink.accept(UUID.fromString(id));
            }
            next = getNextUrl(response.headers().allValues("Link"));
        }

        // then
        assertEquals(urls.size(), count);
        logLatency(paradigm, TimingMetric.PAGE_THROUGH_ALL_USERS, Duration.between(startTime, Instant.now()));
    }

    /**
     * Simulate sending and receiving {@link #messageCount} messages between
     * users in the system.
     * @param baseUrl the API prefix, should include scheme, host, and port
     * @param paradigm the type of application backing the API
     * @param userIds  the user IDs in the system
     */
    protected void benchmarkSendingAndReceivingLatency(String baseUrl, final Paradigm paradigm, final Collection<? extends UUID> userIds) {
        // given
        assumeTrue(userIds.size() > 0);
        final var pattern = Pattern.compile("messages/.*$");
        final var userIdList = new ArrayList<>(userIds);

        final var startTime = Instant.now();
        final var responseBodyHandler = new JsonNodeBodyHandler();

        IntStream.range(0, messageCount).parallel().mapToObj(_index -> {
            // select a random sender and a random recipient (other than the sender)
            final var senderIndex = random.nextInt(userIdList.size());
            var recipientIndex = senderIndex;
            while (recipientIndex == senderIndex) {
                recipientIndex = random.nextInt(userIdList.size());
            }

            final var senderId = userIdList.get(senderIndex);
            final var recipientId = userIdList.get(recipientIndex);

            // prepare a message
            final var bodyPublisher = BodyPublishers.ofString(RandomString.make(8192));
            try {
                var request = HttpRequest.newBuilder(new URI(baseUrl + "/users/" + senderId + "/messages/outgoing/"
                        + recipientId))
                        .POST(bodyPublisher)
                        .build();

                // when
                var start = Instant.now();

                var response = client.send(request, responseBodyHandler);
                var end = Instant.now();
                assertEquals(201, response.statusCode());
                logLatency(paradigm, TimingMetric.SEND_SINGLE_MESSAGE, Duration.between(start, end));

                // each message prompts the recipient to open their client and load the
                // latest messages

                final var messageUrl = response.headers().firstValue("Location").get();
                final var messagesUrl = pattern.matcher(messageUrl).replaceFirst("messages");

                request = HttpRequest.newBuilder(new URI(messagesUrl))
                        .GET()
                        .build();
                start = Instant.now();
                response = client.send(request, responseBodyHandler);
                logLatency(paradigm, TimingMetric.GET_MESSAGES_FOR_USER, Duration.between(start, Instant.now()));

                return response;
            } catch( final IOException | InterruptedException | URISyntaxException e ) {
                logger.error("Error with paradigm: " + paradigm + ": " + e.getMessage(), e);
                throw new RuntimeException("Error with paradigm: " + paradigm + ": " + e.getMessage(), e);
            }
        })
        // then
        .forEach(response -> {
            assertEquals(200, response.statusCode(), response.toString());
            final var root = response.body();
            final var messages = root.get("messages");
            assertTrue(root.has("messages"), "missing messages field: " + root);
            assertFalse(messages.isNull(), "messages object is null");
            assertTrue(messages.isArray(), "messages object is not an array");
            assertFalse(messages.isEmpty(), "messages array is empty");
            assertTrue(root.has("_links"), "_links field is missing: " + root);
            final var links = root.get("_links");
            assertFalse(links.isNull(), "_links object is null");
            assertFalse(links.isEmpty(), "_links array is empty");
        });
        logLatency(paradigm, TimingMetric.SEND_RECEIVE_ALL_MESSAGES, Duration.between(startTime, Instant.now()));
    }

    /**
     * Send as many messages as possible within {@link #throughputTestDuration}.
     * @param baseUrl the API prefix, should include scheme, host, and port
     * @param paradigm receives the throughput result
     * @param userIds all of the user IDs in the system
     *
     * @throws InterruptedException 
     */
    protected void benchmarkSendingThroughput(final String baseUrl,
            final Paradigm paradigm,
            final Collection<? extends UUID> userIds) throws InterruptedException {
        // given
        assumeTrue(userIds.size() > 0);
        final var userIdList = new ArrayList<>(userIds);
        final var deadline = Instant.now().plus(throughputTestDuration);

        final var responseBodyHandler = new JsonNodeBodyHandler();
        final var executor = new ThreadPoolExecutor(16, 16, 15, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        final var counter = new LongAdder();

        // when
        while (Instant.now().isBefore(deadline)) {
            executor.execute(() -> {
                if (Instant.now().isAfter(deadline) || Thread.interrupted()) {
                    return;
                }
                // select a random sender and a random recipient (other than the sender)
                final var senderIndex = random.nextInt(userIdList.size());
                var recipientIndex = senderIndex;
                while (recipientIndex == senderIndex) {
                    recipientIndex = random.nextInt(userIdList.size());
                }

                final var senderId = userIdList.get(senderIndex);
                final var recipientId = userIdList.get(recipientIndex);

                // prepare a message
                final var bodyPublisher = BodyPublishers.ofString(RandomString.make(8192));
                try {
                    final var request = HttpRequest
                            .newBuilder(new URI(baseUrl + "/users/" + senderId + "/messages/outgoing/" + recipientId))
                            .timeout(Duration.between(Instant.now(), deadline))
                            .POST(bodyPublisher)
                            .build();
                    final var response = client.send(request, responseBodyHandler);
                    if (Instant.now().isBefore(deadline)) {
                        if (response.statusCode() == 201) {
                            counter.increment();
                        } else {
                            logger.warn("Not counting error response for paradigm: " + paradigm + ": " + response);
                        }
                    } // else exceeded time limit
                } catch (final HttpTimeoutException | InterruptedException e) {
                    // exceeded time limit
                    return;
                } catch (final IllegalArgumentException iae) {
                    if (iae.getMessage().startsWith("Invalid duration: PT-")) {
                        // exceeded time limit
                        return;
                    }
                    logger.error("Error with paradigm: " + paradigm + ": " + iae.getMessage(), iae);
                    throw iae;
                } catch (final URISyntaxException | IOException e) {
                    logger.error("Error with paradigm: " + paradigm + ": " + e.getMessage(), e);
                    throw new RuntimeException("Error with paradigm: " + paradigm + ": " + e.getMessage(), e);
                }
            });
        }
        executor.shutdown();
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // then
        logThroughput(paradigm, CountMetric.SENT_MESSAGES, counter.sum(), throughputTestDuration);
    }

    /**
     * Retrieve as many messages as possible within {@link #throughputTestDuration}. Each
     * operation is actually three HTTP calls and the operation is only
     * counted if all three calls complete before time runs out.
     * @param baseUrl the API prefix, should include scheme, host, and port
     * @param paradigm receives the throughput result
     * @param userIds  all of the user IDs in the system
     * 
     * @throws InterruptedException 
     */
    protected void benchmarkRetrievalThroughput(final String baseUrl, final Paradigm paradigm, final Collection<? extends UUID> userIds) throws InterruptedException {
        // given
        assumeTrue(userIds.size() > 0);
        final var userIdList = new ArrayList<>(userIds);
        final var deadline = Instant.now().plus(throughputTestDuration);

        final var responseBodyHandler = new JsonNodeBodyHandler();
        final var executor = new ThreadPoolExecutor(16, 16, 15, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        final var counter = new LongAdder();

        // when
        for (int recipientIndex = random.nextInt(userIdList.size());
                Instant.now().isBefore(deadline);
                recipientIndex = random.nextInt(userIdList.size())) {
            final var recipientId = userIdList.get(recipientIndex);
            executor.execute(() -> {
                if (Instant.now().isAfter(deadline) || Thread.interrupted()) {
                    return;
                }
                try {
                    // get all conversations for a user (first page)
                    final var conversationUrl = getFirstConversationUrl(baseUrl, responseBodyHandler, recipientId, deadline);

                    // get the first conversation
                    final var messagesUrl = getMessagesUrl(baseUrl, responseBodyHandler, conversationUrl, deadline);

                    // get all the messages for the first conversation (first page)
                    final var messagesRequest =
                            HttpRequest.newBuilder(new URI(messagesUrl))
                            .timeout(Duration.between(Instant.now(), deadline))
                            .GET()
                            .build();
                    final var messagesResponse = client.send(messagesRequest, responseBodyHandler);
                    final var messagesRoot = messagesResponse.body();
                    assertFalse(messagesRoot.isNull());
                    if (Instant.now().isBefore(deadline)) {
                        counter.increment();
                    }
                } catch (final HttpTimeoutException | InterruptedException e) {
                    // exceeded time limit
                    return;
                } catch (final IllegalArgumentException iae) {
                    if (iae.getMessage().startsWith("Invalid duration: PT-")) {
                        // exceeded time limit
                        return;
                    }
                    logger.error("Error with paradigm: " + paradigm + ", baseUrl: " + baseUrl + ": " + iae.getMessage(), iae);
                    throw iae;
                } catch (final URISyntaxException | IOException e) {
                    logger.error("Error with paradigm: " + paradigm + ": " + e.getMessage(), e);
                    throw new RuntimeException("Error with paradigm: " + paradigm + ", baseUrl: " + baseUrl + ": " + e.getMessage(), e);
                }
            });
        }

        // then
        executor.shutdown();
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // then
        logThroughput(paradigm, CountMetric.RETRIEVED_MESSAGES, counter.sum(), throughputTestDuration);
    }

    protected String getMessagesUrl(final String baseUrl, final JsonNodeBodyHandler responseBodyHandler, final String conversationUrl, final Temporal deadline)
            throws URISyntaxException, IOException, InterruptedException {
        final var conversationRequest =
                HttpRequest.newBuilder(new URI(conversationUrl))
                .GET()
                .timeout(Duration.between(Instant.now(), deadline))
                .build();
        final var conversationResponse = client.send(conversationRequest, responseBodyHandler);
        final var conversationRoot = conversationResponse.body();
        final var conversationLinks = conversationRoot.get("_links");
        final var messagesRef = conversationLinks.get("messages");
        var messagesPath = messagesRef.get("href").asText();
        if (!messagesPath.startsWith("http") && messagesPath.startsWith("/")) {
            messagesPath = baseUrl + messagesPath;
        }
        return messagesPath;
    }

    protected String getFirstConversationUrl(final String baseUrl, final JsonNodeBodyHandler responseBodyHandler, final UUID recipientId, final Temporal deadline)
            throws URISyntaxException, IOException, InterruptedException {
        final var conversationsRequest = HttpRequest
                .newBuilder(new URI(baseUrl + "/users/" + recipientId + "/conversations"))
                .GET()
                .timeout(Duration.between(Instant.now(), deadline))
                .build();
        final var conversationsResponse = client.send(conversationsRequest, responseBodyHandler);
        final var conversationsRoot = conversationsResponse.body();
        assertTrue(conversationsRoot.has("conversations"), "missing conversations field: " + conversationsRoot);
        final var conversationsArray = conversationsRoot.get("conversations");
        assertTrue(conversationsArray.isArray(), "not an array: " + conversationsArray);
        assertFalse(conversationsArray.isEmpty(), "empty conversations array: " + conversationsRoot);
        final var firstConversation = conversationsArray.get(0);
        assertTrue(firstConversation.has("_links"), "missing _links: " + firstConversation);
        final var links = firstConversation.get("_links");
        assertTrue(links.has("self"), "missing self link: " + links);
        final var conversationLink = links.get("self");
        var path = conversationLink.get("href").asText();
        if (!path.startsWith("http") && path.startsWith("/")) {
            path = baseUrl + path;
        }
        return path;
    }

    protected Optional<URI> getNextUrl(final Iterable<? extends String> headerValues) throws URISyntaxException {
        for (final var value : headerValues) {
            final var components = value.split("; ");
            if (components[1].contentEquals("rel=next")) {
                return Optional.of(new URI(components[0].replaceFirst("^<", "").replaceFirst(">$", "")));
            }
        }
        return Optional.empty();
    }

    protected void logLatency(final Paradigm paradigm, final TimingMetric metric, final Duration duration) {
        latencyQueue.execute(() -> {
            latencyOutput.append(paradigm.name());
            latencyOutput.append('\t');
            latencyOutput.append(metric.name());
            latencyOutput.append('\t');
            latencyOutput.print(duration.toMillis());
            latencyOutput.println();
            latencyOutput.flush();
        });
    }

    protected void logThroughput(Paradigm paradigm, CountMetric metric, long count, Duration duration) {
        throughputQueue.execute(() -> {
            throughputOutput.append(paradigm.name());
            throughputOutput.append('\t');
            throughputOutput.append(metric.name());
            throughputOutput.append('\t');
            throughputOutput.print(count);
            throughputOutput.append('\t');
            throughputOutput.print(duration.toMillis());
            throughputOutput.println();
            throughputOutput.flush();
        });
    }

}