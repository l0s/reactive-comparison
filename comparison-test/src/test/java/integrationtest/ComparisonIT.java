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