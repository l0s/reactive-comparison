package com.macasaet.messaging;

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
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.bytebuddy.utility.RandomString;
import reactive.ReactiveDemoApplication;
//import reactor.tools.agent.ReactorDebugAgent;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
public class ComparisonIT {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final int userCount = 32;
    private final int messageCount = 4096;
//    private final int userCount = 2;
//    private final int messageCount = 4;

    @BeforeAll
    public static void setUp() {
//        ReactorDebugAgent.init();
    }

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

    protected static enum TimingMetric {
        CREATE_ALL_USERS,
        CREATE_SINGLE_USER,
        PAGE_THROUGH_ALL_USERS,
        GET_PAGE_OF_USERS,
        SEND_RECEIVE_ALL_MESSAGES,
        SEND_SINGLE_MESSAGE,
        GET_MESSAGES_FOR_USER,
    }

    protected static enum Paradigm {

        REACTIVE(ReactiveDemoApplication.class),
        BLOCKING(Application.class);

        private final Map<TimingMetric, Collection<Duration>> durations = new ConcurrentHashMap<>();
        private final Class<?> mainClass;

        private Paradigm(final Class<?> mainClass) {
            Objects.requireNonNull(mainClass);
            this.mainClass = mainClass;
        }

        public Class<?> getMainClass() {
            return mainClass;
        }

        public void logDuration(final TimingMetric metric, final Duration duration) {
            final var bucket = durations.computeIfAbsent(metric, key -> new ConcurrentLinkedQueue<>());
            bucket.add(duration);
        }

        public Duration getAverageDuration(final TimingMetric metric) {
            final var bucket = durations.get(metric);
            if (bucket == null || bucket.isEmpty()) {
                return null;
            }
            var total = Duration.ZERO;
            for (final var duration : bucket) {
                total = total.plus(duration);
            }
            return total.dividedBy(bucket.size());
        }

    }

    @TestFactory
    public Stream<DynamicNode> testContainers(final TestReporter reporter) {
        System.setProperty("reactor.netty.ioWorkerCount", "" + 4);
        System.setProperty("reactor.netty.pool.maxConnections", "" + 4);
        reporter.publishEntry("generating dynamic nodes");
//        return Stream.of(Paradigm.REACTIVE)
        return Stream.of(Paradigm.REACTIVE, Paradigm.BLOCKING, Paradigm.REACTIVE,
                Paradigm.BLOCKING)
//        return Stream.of(Paradigm.BLOCKING)
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
                    dynamicTest("can paginate through users", () -> verifyPaginationAsync(paradigm, userUrls, userIds::add)),
                    dynamicTest("can send messages", () -> verifyMessages(paradigm, userIds)),
                    dynamicTest("context can be closed", context::close),
                    dynamicTest("database can be closed", database::stop)));
            });
    }

    protected final void verifyEmpty() throws IOException, InterruptedException, URISyntaxException {
        // given
        final var objectMapper = new ObjectMapper();
        final var baseUrl = "http://localhost:" + 8080 + "/users/";
        final var client = HttpClient.newHttpClient();
        final var request = HttpRequest.newBuilder(new URI(baseUrl)).GET().build();

        // when
        final var response = client.send(request, BodyHandlers.ofInputStream());

        // then
        assertEquals(200, response.statusCode());
        assertEquals(0, response.headers().allValues("Link").size());
        try (var content = response.body()) {
            final var root = objectMapper.readTree(content);
            assertTrue(root.isArray());
            assertTrue(root.isEmpty());
        }
    }

    protected final void verifyCanCreateUsers(final Paradigm paradigm, final Consumer<Collection<URL>> sink) {
        final var baseUrl = "http://localhost:" + 8080 + "/users/";
        final var client = HttpClient.newHttpClient();
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

    protected final void verifyPaginationAsync(final Paradigm paradigm, final Collection<URL> urls, final Consumer<UUID> userIdSink) throws IOException, InterruptedException {
        // given
        final var objectMapper = new ObjectMapper();
        final var baseUrl = "http://localhost:" + 8080 + "/users/";
        final var client = HttpClient.newHttpClient();
        final var startTime = Instant.now();

        // when
        var count = 0;
        var next = baseUrl + "?pageNum=0&pageSize=13"; // FIXME make optional
        while (next != null) {
            final var request = HttpRequest.newBuilder(URI.create(next)).GET().build();
            final var start = Instant.now();
            final var response = client.send(request, BodyHandlers.ofInputStream());
            paradigm.logDuration(TimingMetric.GET_PAGE_OF_USERS, Duration.between(start, Instant.now()));
            assertEquals(200, response.statusCode());
            try (var content = response.body()) {
                final var root = objectMapper.readTree(content);
                assertTrue(root.isArray());
                count += root.size();
                for (final var user : root) {
                    final var id = user.get("id").asText();
                    final var userUrl = new URL(baseUrl + id.toString());
                    assertTrue(urls.contains(userUrl));
                    userIdSink.accept(UUID.fromString(id));
                }
            }
            next = getNextUrl(response.headers().allValues("Link"));
        }

        // then
        assertEquals(urls.size(), count);
        paradigm.logDuration(TimingMetric.PAGE_THROUGH_ALL_USERS, Duration.between(startTime, Instant.now()));
    }

    protected final void verifyMessages(final Paradigm paradigm, final Queue<UUID> userIdQueue)
            throws URISyntaxException {
        // given
        final var objectMapper = new ObjectMapper();
        final var random = new Random();
        final var baseUrl = "http://localhost:" + 8080;
        final var pattern = Pattern.compile("messages/.*$");
        final var client = HttpClient.newHttpClient();
        final var userIds = new ArrayList<>(userIdQueue);

        final var startTime = Instant.now();
        final var responseBodyHandler = BodyHandlers.ofString();

        IntStream.range(0, messageCount).parallel().mapToObj(ignore -> {
            final var senderIndex = random.nextInt(userIds.size());
            var recipientIndex = senderIndex;
            while (recipientIndex == senderIndex) {
                recipientIndex = random.nextInt(userIds.size());
            }

            final var senderId = userIds.get(senderIndex);
            final var recipientId = userIds.get(recipientIndex);

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
        .forEach( response -> {
            assertEquals(200, response.statusCode());
            try {
                final var root = objectMapper.readTree(response.body());
                final var messages = root.get("messages");
                assertFalse(messages.isNull(), "messages object is null");
                assertTrue(messages.isArray(), "messages object is not an array");
                assertFalse(messages.isEmpty(), "messages array is empty");
                final var links = root.get("links");
                assertFalse(links.isNull(), "links object is null");
                assertTrue(links.isArray(), "links object is not an array");
                assertFalse(links.isEmpty(), "link array is empty");
            } catch (final JsonProcessingException e) {
                logger.error(e.getMessage(), e);
                throw new RuntimeException(e.getMessage(), e);
            }
        });
        paradigm.logDuration(TimingMetric.SEND_RECEIVE_ALL_MESSAGES, Duration.between(startTime, Instant.now()));
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