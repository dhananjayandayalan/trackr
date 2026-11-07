package dev.trakr.api.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.api.realtime.internal.SseHub.Frame;
import dev.trakr.api.realtime.internal.SseHub.Subscriber;
import dev.trakr.kernel.Ids;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// no server: emitters whose send() we control stand in for browsers
class SseHubTest {

    private final SseHub hub = new SseHub();
    private final UUID project = Ids.newId();

    /** Records what would go on the wire. */
    static class RecordingEmitter extends SseEmitter {

        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder text = new StringBuilder();
            builder.build().forEach(part -> text.append(part.getData()));
            sent.add(text.toString());
        }

        List<String> events() {
            return sent.stream().filter(frame -> frame.contains("event:")).toList();
        }
    }

    /** A browser on a dead connection: every write fails. */
    static class BrokenEmitter extends SseEmitter {

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("connection reset");
        }
    }

    /** A browser on a terrible connection: every write hangs until released. */
    static class StuckEmitter extends SseEmitter {

        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder builder) {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void subscriberWhoseEmitterThrowsIsRemoved() {
        hub.subscribeProject(project, Ids.newId(), new BrokenEmitter()).open(List.of());

        hub.broadcast(project, frame("1"));

        waitUntil(() -> hub.subscriberCount(project) == 0);
    }

    @Test
    void slowSubscriberDoesNotBlockOthers() {
        StuckEmitter stuck = new StuckEmitter();
        RecordingEmitter fast = new RecordingEmitter();
        hub.subscribeProject(project, Ids.newId(), stuck).open(List.of());
        hub.subscribeProject(project, Ids.newId(), fast).open(List.of());

        long start = System.nanoTime();
        IntStream.range(0, 3).forEach(i -> hub.broadcast(project, frame(String.valueOf(i))));

        // broadcast didn't wait for the stuck browser…
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
        // …and the healthy one got everything meanwhile
        waitUntil(() -> fast.events().size() == 3);
        stuck.release.countDown();
    }

    @Test
    void subscriberTooFarBehindIsCutOff() {
        StuckEmitter stuck = new StuckEmitter();
        Subscriber subscriber = hub.subscribeProject(project, Ids.newId(), stuck);
        subscriber.open(List.of());

        IntStream.rangeClosed(0, SseHub.MAX_PENDING + 1).forEach(i -> hub.broadcast(project, frame(String.valueOf(i))));

        assertThat(subscriber.isClosed()).isTrue();
        assertThat(hub.subscriberCount(project)).isZero();
        stuck.release.countDown();
    }

    @Test
    void eachBrowserGetsItsEventsInOrder() {
        RecordingEmitter emitter = new RecordingEmitter();
        hub.subscribeProject(project, Ids.newId(), emitter).open(List.of());

        IntStream.range(0, 100).forEach(i -> hub.broadcast(project, frame(String.valueOf(i))));

        waitUntil(() -> emitter.events().size() == 100);
        assertThat(emitter.events()).extracting(SseHubTest::idOf)
                .containsExactlyElementsOf(IntStream.range(0, 100).mapToObj(String::valueOf).toList());
    }

    @Test
    void liveFramesWaitForTheReplayAndAreNotSentTwice() {
        RecordingEmitter emitter = new RecordingEmitter();
        Subscriber subscriber = hub.subscribeProject(project, Ids.newId(), emitter);
        // published while the replay was being read: arrives live and is also in the replay
        hub.broadcast(project, frame("b"));
        hub.broadcast(project, frame("c"));

        subscriber.open(List.of(frame("a"), frame("b")));

        waitUntil(() -> emitter.events().size() == 3);
        assertThat(emitter.events()).extracting(SseHubTest::idOf).containsExactly("a", "b", "c");
    }

    @Test
    void framesOnlyReachTheirProject() {
        RecordingEmitter here = new RecordingEmitter();
        RecordingEmitter elsewhere = new RecordingEmitter();
        hub.subscribeProject(project, Ids.newId(), here).open(List.of());
        hub.subscribeProject(Ids.newId(), Ids.newId(), elsewhere).open(List.of());

        hub.broadcast(project, frame("1"));

        waitUntil(() -> here.events().size() == 1);
        assertThat(elsewhere.events()).isEmpty();
    }

    private static Frame frame(String id) {
        return new Frame(id, "issue.moved", "{\"n\":\"" + id + "\"}");
    }

    // the wire text is "id:3\nevent:issue.moved\ndata:{…}\n\n"
    private static String idOf(String wire) {
        return wire.lines().filter(line -> line.startsWith("id:")).findFirst().orElseThrow().substring(3);
    }

    private static void waitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 5 s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
