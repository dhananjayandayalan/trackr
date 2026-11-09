package dev.trakr.flows;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.trakr.api.ApiApplication;
import dev.trakr.api.TestBrowser;
import dev.trakr.api.TestcontainersConfiguration;
import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.MoveIssue;
import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.ProjectService;
import dev.trakr.api.project.Template;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * A real HTTP connection, like a browser's EventSource: the stream is an async request on the embedded
 * server, so MockMvc can't play it. Moves go through IssueService, the stream goes through Kafka.
 */
@SpringBootTest(classes = ApiApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SseStreamIT {

    private final IssueService issues;
    private final ProjectService projects;
    private final KafkaListenerEndpointRegistry listeners;
    private final StringRedisTemplate redis;
    private final int port;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;

    @Autowired
    SseStreamIT(IssueService issues, ProjectService projects, KafkaListenerEndpointRegistry listeners,
            StringRedisTemplate redis, @LocalServerPort int port) {
        this.issues = issues;
        this.projects = projects;
        this.listeners = listeners;
        this.redis = redis;
        this.port = port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** One open stream: its lines arrive in a queue, read by the test with a timeout. */
    record Stream(CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> response,
            BlockingQueue<String> lines) implements AutoCloseable {

        /** The id and data of the next {@code event: name} frame; a frame ends at an empty line. */
        Frame await(String name, Duration timeout) throws InterruptedException {
            Instant deadline = Instant.now().plus(timeout);
            String id = null;
            String event = null;
            String data = null;
            while (Instant.now().isBefore(deadline)) {
                String line = lines.poll(100, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                if (line.startsWith("id:")) {
                    id = line.substring(3).strip();
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data = line.substring(5).strip();
                } else if (line.isEmpty()) {
                    if (name.equals(event) && data != null) {
                        return new Frame(id, data);
                    }
                    id = null;
                    event = null;
                    data = null;
                }
            }
            throw new AssertionError("no event: " + name + " within " + timeout);
        }

        @Override
        public void close() {
            response.thenAccept(r -> r.body().close());
            response.cancel(true);
        }
    }

    record Frame(@Nullable String id, String data) {
    }

    @Test
    void moveShowsUpOnTheStreamAndIsReplayedAfterAReconnect() throws Exception {
        // BLOCK 1: a signed-in user with a project and two issues
        UUID userId = signUp();
        UUID projectId = projects.create(userId, new NewProject("Live", "SSE", Template.SCRUM, null, Access.PRIVATE,
                null, "cyan", List.of())).id();
        issues.create(userId, task("One"));
        issues.create(userId, task("Two"));
        awaitRealtimeListenerAssigned();

        // BLOCK 2: open the stream, move an issue, see it arrive
        Frame first;
        try (Stream stream = open(null)) {
            stream.await("ready", Duration.ofSeconds(5));
            long start = System.nanoTime();
            issues.move(userId, "SSE-1", new MoveIssue(null, null, "SSE-2", null, 0));
            first = stream.await("issue.moved", Duration.ofSeconds(2));
            System.out.printf("issue.moved arrived after %d ms%n", (System.nanoTime() - start) / 1_000_000);
            assertThat(JsonPath.<String>read(first.data(), "$.snapshot.key")).isEqualTo("SSE-1");
            assertThat(JsonPath.<Integer>read(first.data(), "$.sequence")).isEqualTo(1);
            // envelope metadata repeated in the event is stripped at the top level; the snapshot stays whole
            assertThat(JsonPath.<Map<String, Object>>read(first.data(), "$"))
                    .doesNotContainKeys("eventId", "occurredAt", "workspaceId").containsKey("actorId");
            assertThat(first.id()).isNotBlank();
        }

        // BLOCK 3: while nobody is connected, another move; wait until it is in the replay buffer
        long buffered = bufferSize(projectId);
        issues.move(userId, "SSE-1", new MoveIssue(null, null, null, "SSE-2", 1));
        awaitBufferSize(projectId, buffered + 1);

        // BLOCK 4: reconnect with the last id we saw → the missed move is replayed
        try (Stream stream = open(first.id())) {
            Frame replayed = stream.await("issue.moved", Duration.ofSeconds(5));
            assertThat(JsonPath.<Integer>read(replayed.data(), "$.sequence")).isEqualTo(2);
        }

        // BLOCK 5: an id the buffer never had → resync (the client refetches the board)
        try (Stream stream = open(UUID.randomUUID().toString())) {
            stream.await("resync", Duration.ofSeconds(5));
        }
    }

    @Test
    void everyNewStreamIsToldWhoIsHere() throws Exception {
        UUID userId = signUp();
        projects.create(userId, new NewProject("Here", "PRS", Template.SCRUM, null, Access.PRIVATE, null, "cyan",
                List.of()));

        // the first stream changes the set (nobody → this user): a broadcast, which it receives too
        try (Stream first = open("PRS", null)) {
            assertThat(JsonPath.<List<String>>read(first.await("presence", Duration.ofSeconds(5)).data(), "$.userIds"))
                    .containsExactly(userId.toString());

            // a second tab of the same user: the set doesn't change, so nothing is broadcast — but the new
            // stream must still learn who is here, or it shows nobody until someone else comes or goes
            try (Stream second = open("PRS", null)) {
                assertThat(JsonPath.<List<String>>read(second.await("presence", Duration.ofSeconds(5)).data(),
                        "$.userIds")).containsExactly(userId.toString());
            }
        }
    }

    @Test
    void outsidersCannotOpenAProjectsStream() throws Exception {
        UUID owner = signUp();
        projects.create(owner, new NewProject("Closed", "SSX", Template.SCRUM, null, Access.PRIVATE, null, "cyan",
                List.of()));
        cookies.getCookieStore().removeAll();
        signUp();

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/v1/projects/SSX/stream"))
                .header("Accept", "text/event-stream, application/problem+json").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private Stream open(@Nullable String lastEventId) {
        return open("SSE", lastEventId);
    }

    private Stream open(String projectKey, @Nullable String lastEventId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/v1/projects/" + projectKey + "/stream"))
                .header("Accept", "text/event-stream").GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        var response = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofLines());
        response.thenAcceptAsync(r -> {
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/event-stream"));
            try (java.util.stream.Stream<String> body = r.body()) {
                body.forEach(lines::add);
            } catch (RuntimeException e) {
                // closed by the test
            }
        });
        return new Stream(response, lines);
    }

    // sign up over HTTP like the SPA: fetch the XSRF-TOKEN cookie first, echo it in the header
    private UUID signUp() throws Exception {
        http.send(HttpRequest.newBuilder(uri("/api/v1/me")).GET().build(), HttpResponse.BodyHandlers.discarding());
        String xsrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst()
                .orElseThrow();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/signup"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", xsrf)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name": "Ada", "email": "%s", "password": "%s"}"""
                        .formatted(TestBrowser.uniqueEmail(), TestBrowser.PASSWORD)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    // the realtime consumer starts at "latest": anything sent before it has partitions would never reach it
    private void awaitRealtimeListenerAssigned() throws InterruptedException {
        var container = listeners.getListenerContainer("realtime");
        Instant deadline = Instant.now().plusSeconds(30);
        while (container == null || container.getAssignedPartitions() == null
                || container.getAssignedPartitions().isEmpty()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("realtime listener got no partitions");
            }
            Thread.sleep(100);
            container = listeners.getListenerContainer("realtime");
        }
    }

    private long bufferSize(UUID projectId) {
        Long length = redis.opsForStream().size("sse:project:" + projectId);
        return length == null ? 0 : length;
    }

    private void awaitBufferSize(UUID projectId, long size) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (bufferSize(projectId) >= size) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("replay buffer did not reach " + size + " entries");
    }

    private static CreateIssue task(String summary) {
        return new CreateIssue("SSE", IssueType.TASK, summary, null, null, null, null, null, null, null, List.of());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
