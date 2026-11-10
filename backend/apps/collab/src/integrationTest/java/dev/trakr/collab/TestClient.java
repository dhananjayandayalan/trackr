package dev.trakr.collab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.collab.protocol.MessageReader;
import dev.trakr.collab.protocol.Messages.Message;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * A pretend browser: a real WebSocket client (Spring's, on Tomcat's JSR-356 client) that records every frame it
 * receives, so a test can say "the next frame is a syncStep2" or "nothing arrives".
 */
public final class TestClient extends BinaryWebSocketHandler implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private @Nullable WebSocketSession session;

    public static TestClient connect(int port, String issueKey, @Nullable String cookie) throws Exception {
        return connect(port, issueKey, cookie, Fixtures.ORIGIN);
    }

    public static TestClient connect(int port, String issueKey, @Nullable String cookie, String origin) throws Exception {
        TestClient client = new TestClient();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        if (cookie != null) {
            headers.add("Cookie", "TRAKR_SESSION=" + cookie);
        }
        client.session = new StandardWebSocketClient()
                .execute(client, headers, URI.create("ws://localhost:" + port + "/collab/issues/" + issueKey))
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        return client;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        frames.add(bytes);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closed.complete(status);
    }

    public void send(byte[] frame) throws IOException {
        session().sendMessage(new BinaryMessage(frame));
    }

    /** The next frame, raw. */
    public byte[] nextFrame() throws InterruptedException {
        byte[] frame = frames.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(frame).as("a frame within " + WAIT).isNotNull();
        return frame;
    }

    /** The next frame, parsed. */
    public Message next() throws InterruptedException {
        return MessageReader.read(nextFrame());
    }

    /** Skips frames until one of this kind arrives. */
    public <T extends Message> T next(Class<T> type) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            byte[] frame = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (frame != null && type.isInstance(MessageReader.read(frame))) {
                return type.cast(MessageReader.read(frame));
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " within " + WAIT);
    }

    /** Nothing of this kind arrives for a while (frames of other kinds are skipped). */
    public void receivesNo(Class<? extends Message> type, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            byte[] frame = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (frame != null) {
                assertThat(MessageReader.read(frame)).as("unexpected frame").isNotInstanceOf(type);
            }
        }
    }

    public CloseStatus awaitClose() throws InterruptedException, ExecutionException, TimeoutException {
        return closed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    private WebSocketSession session() {
        if (session == null) {
            throw new IllegalStateException("not connected");
        }
        return session;
    }

    @Override
    public void close() throws IOException {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }
}
