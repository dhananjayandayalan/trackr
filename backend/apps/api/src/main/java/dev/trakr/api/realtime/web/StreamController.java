package dev.trakr.api.realtime.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.project.ProjectAccess;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.realtime.internal.Streams;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 03-api §4. The method returns at once; the connection stays open (an async request) and SseHub writes
 * to it from other threads. No transaction and no session work happens per event.
 */
@RestController
class StreamController {

    private final ProjectAccess access;
    private final Streams streams;

    StreamController(ProjectAccess access, Streams streams) {
        this.access = access;
        this.streams = streams;
    }

    @Operation(operationId = "streamProject")
    @GetMapping(path = "/api/v1/projects/{key}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> project(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestHeader(name = "Last-Event-ID", required = false) @Nullable String lastEventId) {
        UUID projectId = access.require(principal.id(), key, ProjectRole.VIEWER).projectId();
        return stream(streams.openProject(projectId, principal.id(), lastEventId));
    }

    @Operation(operationId = "streamMe")
    @GetMapping(path = "/api/v1/me/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> me(@AuthenticationPrincipal TrakrPrincipal principal) {
        return stream(streams.openUser(principal.id()));
    }

    // no-cache: never serve a stream from a cache; X-Accel-Buffering: nginx would otherwise hold events back
    private static ResponseEntity<SseEmitter> stream(SseEmitter emitter) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
