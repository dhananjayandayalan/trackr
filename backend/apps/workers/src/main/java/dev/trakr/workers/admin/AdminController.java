package dev.trakr.workers.admin;

import dev.trakr.contracts.events.Topics;
import dev.trakr.workers.config.WorkersProperties;
import dev.trakr.workers.search.Reindexer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator endpoints, on port 8082 only. Every request needs {@code X-Admin-Token}; phase 18 puts these
 * behind the infrastructure's own access control as well.
 */
@RestController
@RequestMapping("/admin")
class AdminController {

    private static final Set<String> TOPICS = Set.of(Topics.ISSUES, Topics.PROJECTS, Topics.COMMENTS,
            Topics.NOTIFICATIONS);

    private final Reindexer reindexer;
    private final DltReplayer replayer;
    private final byte[] token;

    AdminController(Reindexer reindexer, DltReplayer replayer, WorkersProperties properties) {
        this.reindexer = reindexer;
        this.replayer = replayer;
        this.token = properties.admin().token().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/search/reindex")
    Reindexer.Result reindex(HttpServletRequest request) throws IOException {
        requireToken(request);
        return reindexer.reindex();
    }

    @PostMapping("/dlt/{topic}/replay")
    Map<String, Object> replay(HttpServletRequest request, @PathVariable String topic) {
        requireToken(request);
        if (!TOPICS.contains(topic)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such topic");
        }
        return Map.of("topic", topic, "replayed", replayer.replay(topic));
    }

    // constant-time comparison: how many leading characters matched doesn't show in the response time
    private void requireToken(HttpServletRequest request) {
        String sent = request.getHeader("X-Admin-Token");
        if (sent == null || !MessageDigest.isEqual(token, sent.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> busy(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
