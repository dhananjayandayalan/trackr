package dev.trakr.api.notification.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.notification.NotificationService;
import dev.trakr.api.notification.NotificationView;
import dev.trakr.api.shared.web.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 03-api §3.10: the bell. New ones arrive live on /me/stream; this is the list, the badge and "mark read". */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** Either {@code ids} or {@code all: true}. */
    record MarkReadRequest(@Nullable List<UUID> ids, @Nullable Boolean all) {
    }

    record UnreadCount(int count) {
    }

    @Operation(operationId = "listNotifications")
    @GetMapping
    CursorPage<NotificationView> list(@AuthenticationPrincipal TrakrPrincipal principal,
            @RequestParam(defaultValue = "false") boolean unread, @RequestParam(required = false) @Nullable String cursor) {
        return notifications.list(principal.id(), unread, cursor);
    }

    @Operation(operationId = "unreadNotificationCount")
    @GetMapping("/unread-count")
    UnreadCount unreadCount(@AuthenticationPrincipal TrakrPrincipal principal) {
        return new UnreadCount(notifications.unreadCount(principal.id()));
    }

    /** Answers with the new badge number, so the client needn't ask again. */
    @Operation(operationId = "markNotificationsRead")
    @PostMapping("/read")
    UnreadCount markRead(@AuthenticationPrincipal TrakrPrincipal principal, @RequestBody MarkReadRequest body) {
        int unread = Boolean.TRUE.equals(body.all())
                ? notifications.markAllRead(principal.id())
                : notifications.markRead(principal.id(), body.ids() == null ? List.of() : body.ids());
        return new UnreadCount(unread);
    }
}
