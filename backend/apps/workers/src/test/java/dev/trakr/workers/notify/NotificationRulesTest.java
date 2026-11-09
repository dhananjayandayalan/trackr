package dev.trakr.workers.notify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.contracts.events.CommentEvent.CommentAdded;
import dev.trakr.contracts.events.IssueMoved;
import dev.trakr.contracts.events.IssueSnapshot;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.IssueUpdated;
import dev.trakr.contracts.events.TrakrEvent;
import dev.trakr.workers.notify.NotificationRules.Kind;
import dev.trakr.workers.notify.NotificationRules.Notice;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// table-driven: one row per rule and per exception to it
class NotificationRulesTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID CAT = UUID.fromString("00000000-0000-7000-8000-00000000000c");
    private static final UUID DAN = UUID.fromString("00000000-0000-7000-8000-00000000000d");
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");

    static Stream<Arguments> rules() {
        return Stream.of(
                Arguments.of("Ada assigns Bob → Bob is told",
                        assigned(ADA, BOB), Set.of(ADA, CAT), List.of(new Notice(BOB, Kind.ASSIGNED))),
                Arguments.of("Ada assigns herself → nobody",
                        assigned(ADA, ADA), Set.of(ADA), List.of()),
                Arguments.of("Ada unassigns → nobody",
                        assigned(ADA, null), Set.of(ADA, BOB), List.of()),
                Arguments.of("an update without an assignee change → nobody",
                        IssueUpdated.of(NOW, ADA, Map.of("summary", new IssueUpdated.Change("a", "b")), snapshot()),
                        Set.of(BOB), List.of()),
                Arguments.of("Ada moves to Done → the other watchers",
                        IssueTransitioned.of(NOW, ADA, UUID.randomUUID(), snapshot()), Set.of(ADA, BOB, CAT),
                        List.of(new Notice(BOB, Kind.STATUS_CHANGED), new Notice(CAT, Kind.STATUS_CHANGED))),
                Arguments.of("Ada comments mentioning Bob → Bob mentioned, Cat (watcher) commented, Ada nothing",
                        comment(ADA, List.of(BOB)), Set.of(ADA, BOB, CAT),
                        List.of(new Notice(BOB, Kind.MENTIONED), new Notice(CAT, Kind.COMMENTED))),
                Arguments.of("a mention of someone not watching still reaches them",
                        comment(ADA, List.of(DAN)), Set.of(ADA),
                        List.of(new Notice(DAN, Kind.MENTIONED))),
                Arguments.of("Ada mentioning herself → nobody",
                        comment(ADA, List.of(ADA)), Set.of(ADA), List.of()),
                Arguments.of("a move without a status change → nobody",
                        IssueMoved.of(NOW, ADA, null, snapshot()), Set.of(BOB), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void decidesWhoIsTold(String rule, TrakrEvent event, Set<UUID> watchers, List<Notice> expected) {
        assertThat(NotificationRules.noticesFor(event, watchers)).containsExactlyElementsOf(expected);
    }

    private static IssueUpdated assigned(UUID actor, UUID assignee) {
        return IssueUpdated.of(NOW, actor, Map.of("assignee",
                new IssueUpdated.Change(null, assignee == null ? null : assignee.toString())), snapshot());
    }

    private static CommentAdded comment(UUID author, List<UUID> mentioned) {
        return CommentAdded.of(NOW, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "MAR-1",
                author, "hello", mentioned);
    }

    private static IssueSnapshot snapshot() {
        UUID project = UUID.randomUUID();
        return new IssueSnapshot(UUID.randomUUID(), UUID.randomUUID(), project, "MAR", "MAR-1", 1, "task", "Fix it", null,
                UUID.randomUUID(), "done", "medium", null, ADA, null, null, null, "V", List.of(), null, null, false,
                null, 0, null, NOW, false, 1, NOW, NOW);
    }
}
