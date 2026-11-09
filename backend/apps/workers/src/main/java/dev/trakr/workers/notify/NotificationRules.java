package dev.trakr.workers.notify;

import dev.trakr.contracts.events.CommentEvent;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.IssueUpdated;
import dev.trakr.contracts.events.TrakrEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who is told about what (phase 10 table). Pure: the event and the issue's watchers in, notifications
 * out — no database, no Kafka — so every rule is a one-line test. Nobody is notified about their own
 * action, and nobody twice for one event.
 */
public final class NotificationRules {

    public enum Kind {
        ASSIGNED("assigned"), MENTIONED("mentioned"), COMMENTED("commented"), STATUS_CHANGED("status_changed");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** One notification to create. */
    public record Notice(UUID userId, Kind kind) {
    }

    private NotificationRules() {
    }

    public static List<Notice> noticesFor(TrakrEvent event, Set<UUID> watchers) {
        List<Notice> notices = new ArrayList<>();
        switch (event) {
            // the new assignee — unless they assigned it to themselves
            case IssueUpdated e when e.changes().containsKey("assignee") -> {
                Object assignee = e.changes().get("assignee").newValue();
                if (assignee != null && !UUID.fromString(assignee.toString()).equals(e.actorId())) {
                    notices.add(new Notice(UUID.fromString(assignee.toString()), Kind.ASSIGNED));
                }
            }
            // everyone watching, except whoever moved it
            case IssueTransitioned e -> watchers.stream()
                    .filter(user -> !user.equals(e.actorId()))
                    .sorted()
                    .forEach(user -> notices.add(new Notice(user, Kind.STATUS_CHANGED)));
            // the mentioned get "mentioned"; the other watchers "commented"; the author neither
            case CommentEvent.CommentAdded e -> {
                Set<UUID> mentioned = new LinkedHashSet<>(e.mentionedUserIds());
                mentioned.remove(e.authorId());
                mentioned.forEach(user -> notices.add(new Notice(user, Kind.MENTIONED)));
                watchers.stream()
                        .filter(user -> !user.equals(e.authorId()) && !mentioned.contains(user))
                        .sorted()
                        .forEach(user -> notices.add(new Notice(user, Kind.COMMENTED)));
            }
            default -> {
                // every other event tells nobody anything
            }
        }
        return List.copyOf(notices);
    }
}
