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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import net.bytebuddy.utility.RandomString;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
public class ComparisonIT {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Random random = new Random();
    private final String baseUrl = "http://localhost:" + 8080;
    private final HttpClient client = HttpClient.newHttpClient();

    private final int userCount = 32;
    private final int messageCount = 4096;

    @AfterAll
    public static void reportTiming(final TestReporter reporter) {
        for (final var metric : TimingMetric.values()) {
            reporter.publishEntry(metric.name());
            for (final var paradigm : Paradigm.values()) {
                final var average = paradigm.getAverageDuration(metric);
                reporter.publishEntry(paradigm.name(), "" + average);
            }
        }
    }

    @AfterAll
    public static void reportThroughput(final TestReporter reporter) {
        for (final var metric : CountMetric.values()) {
            reporter.publishEntry(metric.name());
            for (final var paradigm : Paradigm.values()) {
                reporter.publishEntry(paradigm.name(), paradigm.getThroughput(metric) + " requests/second");
            }
        }
    }

    @TestFactory
    public Stream<DynamicNode> testContainers(final TestReporter reporter) {
        System.setProperty("reactor.netty.ioWorkerCount", "" + 4);
        System.setProperty("reactor.netty.pool.maxConnections", "" + 4);
        reporter.publishEntry("generating dynamic nodes");

        // Due to JVM optimisation, the first paradigm to execute will be at a
        // disadvantage
        return Stream.of(Paradigm.REACTIVE, Paradigm.BLOCKING, Paradigm.REACTIVE, Paradigm.BLOCKING)
            .map(paradigm -> {

                final var application = new SpringApplication(paradigm.getMainClass());

                @SuppressWarnings({ "rawtypes", "resource" }) // last test closes the database
                final var database =
                    new PostgreSQLContainer("postgres:12.2")
                    .withDatabaseName("db")
                    .withUsername("username")
                    .withPassword("password")
                    .withInitScript("schema.sql");
                database.start();

                final var context =
                    application.run(new String[] {"--database.url=" + database.getJdbcUrl(),
                    "--database.username=" + database.getUsername(),
                    "--database.password=" + database.getPassword(),
                    "--database.maximumPoolSize=8",
                    "--conversationRepository.shardConversationMutations=false",
                    "--conversationRepository.conversationThreads=4"});

                final var userIds = new ConcurrentLinkedQueue<UUID>();
                final var userUrls = new ConcurrentLinkedQueue<URL>();

                return dynamicContainer("application: " + application, Stream.of(
                    dynamicTest("context is active", () -> assertTrue(context.isActive())),
                    dynamicTest("context is running", () -> assertTrue(context.isRunning())),
                    dynamicTest("no users returned", this::verifyEmpty),
                    dynamicTest("can create users", () -> verifyCanCreateUsers(paradigm, userUrls::addAll)),
                    dynamicTest("can paginate through users", () -> verifyPagination(paradigm, userUrls, userIds::add)),
                    dynamicTest("can send messages", () -> verifyMessages(paradigm, userIds)),
                    dynamicTest("can sustain message sending", () -> testSendingThroughput(paradigm, userIds)),
                    dynamicTest("can sustain retrieving message", () -> testRetrievalThroughput(paradigm, userIds)),
                    dynamicTest("context can be closed", context::close),
                    dynamicTest("database can be closed", database::stop)));
            });
    }

    /**
     * Verify that given an empty database, the API returns an array of zero users.
     */
    protected final void verifyEmpty() throws IOException, InterruptedException, URISyntaxException {
        // given
        final var baseUrl = this.baseUrl + "/users/";
        final var request = HttpRequest.newBuilder(new URI(baseUrl)).GET().build();

        // when
        final var response = client.send(request, new JsonNodeBodyHandler());

        // then
        assertEquals(200, response.statusCode());
        assertEquals(0, response.headers().allValues("Link").size());
        final var root = response.body();
        assertTrue(root.isArray());
        assertTrue(root.isEmpty());
    }

    /**
     * Verify that we can create {@link #userCount} users. Concurrency for
     * the operations is determined by the JVM.
     *
     * @param paradigm the type of application backing the API
     * @param sink     destination for all of the user URLs generated
     */
    protected final void verifyCanCreateUsers(final Paradigm paradigm, final Consumer<? super Collection<? extends URL>> sink) {
        final var baseUrl = this.baseUrl + "/users/";
        final var startTime = Instant.now();
        final var urls = IntStream.range(0, userCount)
            .parallel()
            .mapToObj(i -> {
                final var uri =  URI.create(baseUrl);
                final var bodyPublisher = BodyPublishers.ofString("{\"name\":\"user_" + i + "\"}");
                return HttpRequest.newBuilder(uri).POST(bodyPublisher).header("Content-Type", "application/json").build();
            })
            .map(request -> {
                final var start = Instant.now();
                return client.sendAsync(request, BodyHandlers.discarding())
                    .whenComplete((response, error) -> paradigm.logDuration(TimingMetric.CREATE_SINGLE_USER,
                            Duration.between(start, Instant.now())));
            })
            .map(CompletableFuture::join)
            .map(response -> {
                assertEquals(201, response.statusCode());
                return response.headers().firstValue("Location").get();
            })
            .map(urlString -> {
                try {
                    return new URL(urlString);
                } catch (final MalformedURLException mue) {
                    logger.error(mue.getMessage(), mue);
                    throw new RuntimeException(mue.getMessage(), mue);
                }
            })
            .collect(Collectors.toSet());
        paradigm.logDuration(TimingMetric.CREATE_ALL_USERS, Duration.between(startTime, Instant.now()));
        sink.accept(urls);
    }

    /**
     * Verify that we can paginate through all of the users.
     *
     * @param paradigm the type of application backing the API
     * @param urls all of the user URLs 
     * @param userIdSink a destination for all of the user IDs
     */
    protected final void verifyPagination(final Paradigm paradigm, final Collection<? extends URL> urls,
            final Consumer<? super UUID> userIdSink) throws IOException, InterruptedException {
        // given
        final var baseUrl = this.baseUrl + "/users/";
        final var startTime = Instant.now();

        // when
        var count = 0;
        var next = baseUrl + "?pageNum=0&pageSize=13"; // FIXME make optional
        while (next != null) {
            final var request = HttpRequest.newBuilder(URI.create(next)).GET().build();
            final var start = Instant.now();
            final var response = client.send(request, new JsonNodeBodyHandler());
            paradigm.logDuration(TimingMetric.GET_PAGE_OF_USERS, Duration.between(start, Instant.now()));
            assertEquals(200, response.statusCode());
            var root = response.body();
            assertTrue(root.isArray());
            count += root.size();
            for (final var user : root) {
                final var id = user.get("id").asText();
                final var userUrl = new URL(baseUrl + id.toString());
                assertTrue(urls.contains(userUrl));
                userIdSink.accept(UUID.fromString(id));
            }
            next = getNextUrl(response.headers().allValues("Link"));
        }

        // then
        assertEquals(urls.size(), count);
        paradigm.logDuration(TimingMetric.PAGE_THROUGH_ALL_USERS, Duration.between(startTime, Instant.now()));
    }

    /**
     * Simulate sending and receiving {@link #messageCount} messages between
     * users in the system. Concurrency is determined by the JVM.
     *
     * @param paradigm the type of application backing the API
     * @param userIds  the user IDs in the system
     */
    protected final void verifyMessages(final Paradigm paradigm, final Collection<? extends UUID> userIds) {
        // given
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
                return HttpRequest.newBuilder(new URI(baseUrl + "/users/" + senderId + "/messages/outgoing/"
                        + recipientId))
                        .POST(bodyPublisher).build();
            } catch (final URISyntaxException e) {
                logger.error(e.getMessage(), e);
                throw new RuntimeException(e.getMessage(), e);
            }
        })
        // when
        .map(request -> {
            // send the message asynchronously
            final var start = Instant.now();
            return client.sendAsync(request, responseBodyHandler).whenComplete((response, error) -> paradigm
                    .logDuration(TimingMetric.SEND_SINGLE_MESSAGE, Duration.between(start, Instant.now())));
        })
        // then
        .map(future -> {
            return future.thenApplyAsync(response -> {
                assertEquals(201, response.statusCode());

                // each message prompts the recipient to open their client and load the
                // latest messages

                final var messageUrl = response.headers().firstValue("Location").get();
                final var messagesUrl = pattern.matcher(messageUrl).replaceFirst("messages");

                try {
                    final var request = HttpRequest.newBuilder(new URI(baseUrl + messagesUrl)).GET().build();
                    final var start = Instant.now();
                    return client.sendAsync(request, responseBodyHandler).whenComplete((_response, _error) -> paradigm
                            .logDuration(TimingMetric.GET_MESSAGES_FOR_USER, Duration.between(start, Instant.now())));
                } catch (final URISyntaxException e) {
                    logger.error(e.getMessage(), e);
                    throw new RuntimeException(e.getMessage(), e);
                }
            });
        })
        .map(CompletableFuture::join)
        .map(CompletableFuture::join)
        .forEach(response -> {
            assertEquals(200, response.statusCode());
            final var root = response.body();
            final var messages = root.get("messages");
            assertFalse(messages.isNull(), "messages object is null");
            assertTrue(messages.isArray(), "messages object is not an array");
            assertFalse(messages.isEmpty(), "messages array is empty");
            final var links = root.get("links");
            assertFalse(links.isNull(), "links object is null");
            assertTrue(links.isArray(), "links object is not an array");
            assertFalse(links.isEmpty(), "link array is empty");
        });
        paradigm.logDuration(TimingMetric.SEND_RECEIVE_ALL_MESSAGES, Duration.between(startTime, Instant.now()));
    }

    /**
     * Send as many messages as possible within 15 seconds.
     *
     * @param paradigm receives the throughput result
     * @param userIds all of the user IDs in the system
     */
    protected void testSendingThroughput(final Paradigm paradigm, final Collection<? extends UUID> userIds) {
        // given
        final var userIdList = new ArrayList<>(userIds);
        final var limit = Duration.ofSeconds(15);
        final var deadline = Instant.now().plus(limit);

        final var responseBodyHandler = new JsonNodeBodyHandler();
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(16, 16, 15, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        final var counter = new LongAdder();

        // when
        while (Instant.now().isBefore(deadline)) {
            executor.execute(() -> {
                if (Instant.now().isAfter(deadline)) {
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
                            .POST(bodyPublisher).build();
                    final var response = client.send(request, responseBodyHandler);
                    if (Instant.now().isBefore(deadline) && response.statusCode() >= 200
                            && response.statusCode() < 300) {
                        counter.increment();
                    }
                } catch (final InterruptedException ie) {
                    return;
                } catch (final URISyntaxException | IOException e) {
                    logger.error(e.getMessage(), e);
                    throw new RuntimeException(e.getMessage(), e);
                }
            });
        }
        executor.shutdown();
        executor.shutdownNow();

        // then
        paradigm.logThroughput(CountMetric.SENT_MESSAGES, counter.sum(), limit);
    }

    /**
     * Retrieve as many messages as possible within 15 seconds. Each
     * operation is actually three HTTP calls and the operation is only
     * counted if all three calls complete before time runs out.
     * 
     * @param paradigm receives the throughput result
     * @param userIds  all of the user IDs in the system
     */
    protected void testRetrievalThroughput(final Paradigm paradigm, final Collection<? extends UUID> userIds) {
        // given
        final var userIdList = new ArrayList<>(userIds);
        final var limit = Duration.ofSeconds(15);
        final var deadline = Instant.now().plus(limit);

        final var responseBodyHandler = new JsonNodeBodyHandler();
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(16, 16, 15, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        final var counter = new LongAdder();

        // when
        for (int recipientIndex = random.nextInt(userIdList.size()); Instant.now()
                .isBefore(deadline); recipientIndex = random.nextInt(userIdList.size())) {
            final var recipientId = userIdList.get(recipientIndex);
            executor.execute(() -> {
                if (Instant.now().isAfter(deadline)) {
                    return;
                }
                try {
                    // get all conversations for a user (first page)
                    final var conversationsRequest = HttpRequest
                            .newBuilder(new URI(baseUrl + "/users/" + recipientId + "/conversations")).GET().build();
                    final var conversationsResponse = client.send(conversationsRequest, responseBodyHandler);
                    final var conversationsRoot = conversationsResponse.body();
                    final var conversationsArray = conversationsRoot.get("conversations");
                    final var firstConversation = conversationsArray.get(0);
                    final var links = firstConversation.get("links");
                    final var conversationLink = links.get(0);
                    assertEquals("self", conversationLink.get("rel").asText());
                    final var path = conversationLink.get("href").asText();

                    // get the first conversation
                    final var conversationRequest = HttpRequest.newBuilder(new URI(baseUrl + path)).GET().build();
                    final var conversationResponse = client.send(conversationRequest, responseBodyHandler);
                    final var conversationRoot = conversationResponse.body();
                    final var conversationLinks = conversationRoot.get("links");
                    final var messagesLink = conversationLinks.get(0);
                    assertEquals("messages", messagesLink.get("rel").asText());
                    final var messagesPath = messagesLink.get("href").asText();

                    // get all the messages for the first conversation (first page)
                    final var messagesRequest = HttpRequest.newBuilder(new URI(baseUrl + messagesPath)).GET().build();
                    final var messagesResponse = client.send(messagesRequest, responseBodyHandler);
                    final var messagesRoot = messagesResponse.body();
                    assertFalse(messagesRoot.isNull());
                    if (Instant.now().isBefore(deadline)) {
                        counter.increment();
                    }
                } catch (final URISyntaxException | IOException e) {
                    logger.error(e.getMessage(), e);
                    throw new RuntimeException(e.getMessage(), e);
                } catch (final InterruptedException e) {
                    // exceeded time limit
                    return;
                }
            });
        }

        // then
        executor.shutdown();
        executor.shutdownNow();

        // then
        paradigm.logThroughput(CountMetric.RETRIEVED_MESSAGES, counter.sum(), limit);
    }

    protected String getNextUrl(final Iterable<? extends String> headerValues) {
        for (final var value : headerValues) {
            final var components = value.split("; ");
            if (components[1].contentEquals("rel=next")) {
                return components[0].replaceFirst("^<", "").replaceFirst(">$", "");
            }
        }
        return null;
    }
}