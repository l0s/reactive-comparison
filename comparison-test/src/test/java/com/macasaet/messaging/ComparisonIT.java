package com.macasaet.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.fluent.Request;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bytebuddy.utility.RandomString;

@Execution(ExecutionMode.SAME_THREAD)
public class ComparisonIT {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @TestFactory
    public Stream<DynamicNode> testContainers(final TestReporter reporter) {
        System.setProperty("reactor.netty.ioWorkerCount", "" + 4);
        System.setProperty("reactor.netty.pool.maxConnections", "" + 4);
        System.setProperty("server.tomcat.max-connections", "" + 4);
        System.setProperty("server.tomcat.max-threads", "" + 4);
        System.setProperty("server.tomcat.min-spare-threads", "" + 4);
        reporter.publishEntry("generating dynamic nodes");
//        return Stream.of(ReactiveDemoApplication.class, Application.class)
//        return Stream.of(Application.class, ReactiveDemoApplication.class, Application.class, ReactiveDemoApplication.class)
//        return Stream.of(ReactiveDemoApplication.class, Application.class, ReactiveDemoApplication.class, Application.class)
        return Stream.of(Application.class)
//        return Stream.of(ReactiveDemoApplication.class)
            .map(SpringApplication::new)
            .map(application -> {
                final var database = new DatabaseServer();
                database.start();

                final var context = application.run(new String[0]);
                final var userIds = new ConcurrentLinkedQueue<UUID>();
                final var userUrls = new ConcurrentLinkedQueue<URL>();

                return dynamicContainer("application: " + application, Stream.of(
                    dynamicTest("context is active", () -> assertTrue(context.isActive())),
                    dynamicTest("context is running", () -> assertTrue(context.isRunning())),
                    dynamicTest("no users returned", this::verifyEmpty),
                    dynamicTest("can create users", () -> verifyCanCreateUsers(userUrls::addAll)),
                    dynamicTest("can paginate through users", () -> verifyPaginationAsync(userUrls, userIds::add)),
                    dynamicTest("can send messages", () -> verifyMessages(userIds)),
                    dynamicTest("context can be closed", context::close),
                    dynamicTest("database can be stopped", database::stop)));
            });
    }

    protected final void verifyEmpty() throws ClientProtocolException, IOException {
        // given
        // when
        final var response = Request.Get("http://localhost:" + 8080 + "/users/").execute();

        // then
        final var httpResponse = response.returnResponse();
        final var statusLine = httpResponse.getStatusLine();
        assertEquals(200, statusLine.getStatusCode());
        assertEquals(0, httpResponse.getHeaders("Link").length);
        final var entity = httpResponse.getEntity();
        try (var content = entity.getContent()) {
            final var root = new ObjectMapper().readTree(content);
            assertTrue(root.isArray());
            assertTrue(root.isEmpty());
        }
    }

    protected final void verifyCanCreateUsers(final Consumer<Collection<URL>> sink) {
        final var baseUrl = "http://localhost:" + 8080 + "/users/";
        final var client = HttpClient.newHttpClient();
        final var urls = IntStream.range(0, 512)
            .parallel()
            .mapToObj(i -> {
                final var uri =  URI.create(baseUrl);
                final var bodyPublisher = BodyPublishers.ofString("{\"name\":\"user_" + i + "\"}");
                return HttpRequest.newBuilder(uri).POST(bodyPublisher).header("Content-Type", "application/json").build();
            })
            .map(request -> {
                return client.sendAsync(request, BodyHandlers.discarding());
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
        sink.accept(urls);
    }

    protected final void verifyPaginationAsync(final Collection<URL> urls, final Consumer<UUID> userIdSink) throws IOException {
        // given
        final var objectMapper = new ObjectMapper();
        final var baseUrl = "http://localhost:" + 8080 + "/users/";
        final var client = HttpClient.newHttpClient();

        // when
        var count = 0;
        var next = baseUrl + "?pageNum=0&pageSize=13"; // FIXME make optional
        while (next != null) {
            logger.debug("next page URL: {}", next);
            final var request = HttpRequest.newBuilder(URI.create(next)).GET().build();
            final var future = client.sendAsync(request, BodyHandlers.ofInputStream());
            final var response = future.join();
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
    }

    protected final void verifyMessages(final Queue<UUID> userIdQueue) throws URISyntaxException {
        // given
        final var random = new Random();
        final var baseUrl = "http://localhost:" + 8080 + "/users/";
        final var client = HttpClient.newHttpClient();
        final var userIds = new ArrayList<>(userIdQueue);

        IntStream.range(0, 8).parallel().mapToObj(ignore -> {
            final var senderIndex = random.nextInt(userIds.size());
            final var recipientIndex = random.nextInt(userIds.size());

            final var senderId = userIds.get(senderIndex);
            final var recipientId = userIds.get(recipientIndex);

            final var bodyPublisher = BodyPublishers.ofString(RandomString.make(8192));
            try {
                return HttpRequest.newBuilder(new URI(baseUrl + senderId + "/messages/outgoing/" + recipientId))
                        .POST(bodyPublisher).build();
            } catch (final URISyntaxException e) {
                logger.error(e.getMessage(), e);
                throw new RuntimeException(e.getMessage(), e);
            }
        }).map(request -> {
            final var responseBodyHandler = BodyHandlers.ofString();
            return client.sendAsync(request, responseBodyHandler);
        }).map(CompletableFuture::join)
        .map(response -> {
            assertEquals(201, response.statusCode());
            return response.headers().firstValue("Location").get();
        }).forEach( locationString -> {
            logger.info( "location: {}", locationString );
        });

        // when

        // then
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