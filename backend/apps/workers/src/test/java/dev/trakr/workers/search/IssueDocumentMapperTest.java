package dev.trakr.workers.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.trakr.contracts.events.IssueSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IssueDocumentMapperTest {

    private static final Instant CREATED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void copiesTheSnapshotAndAddsTheNames() {
        UUID epic = UUID.randomUUID();
        UUID watcher = UUID.randomUUID();
        IssueSnapshot s = snapshot("high");
        IssueContext context = new IssueContext("In review", "Grace Hopper", epic, "Website relaunch", "MAR Sprint 2",
                "active", List.of(watcher));

        IssueDocument doc = IssueDocumentMapper.toDocument(s, context);

        assertThat(doc.id()).isEqualTo(s.id());
        assertThat(doc.key()).isEqualTo("MAR-131");
        assertThat(doc.projectKey()).isEqualTo("MAR");
        assertThat(doc.number()).isEqualTo(131);
        assertThat(doc.priority()).isEqualTo("high");
        assertThat(doc.priorityRank()).isEqualTo(4);
        assertThat(doc.statusName()).isEqualTo("In review");
        assertThat(doc.assigneeName()).isEqualTo("Grace Hopper");
        assertThat(doc.epicId()).isEqualTo(epic);
        assertThat(doc.epicName()).isEqualTo("Website relaunch");
        assertThat(doc.sprintName()).isEqualTo("MAR Sprint 2");
        assertThat(doc.sprintState()).isEqualTo("active");
        assertThat(doc.storyPoints()).isEqualByComparingTo("3");
        assertThat(doc.labels()).containsExactly("web");
        assertThat(doc.watcherIds()).containsExactly(watcher);
        assertThat(doc.dueDate()).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(doc.createdAt()).isEqualTo(CREATED);
        assertThat(doc.updatedAt()).isEqualTo(UPDATED);
        assertThat(doc.commentsText()).isEmpty();
        assertThat(doc.mentionedUserIds()).isEmpty();
        assertThat(doc.deleted()).isFalse();
    }

    @Test
    void namesThatCouldNotBeFoundStayEmpty() {
        IssueDocument doc = IssueDocumentMapper.toDocument(snapshot("medium"), IssueContext.empty());

        assertThat(doc.statusName()).isNull();
        assertThat(doc.epicId()).isNull();
        assertThat(doc.watcherIds()).isEmpty();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"highest, 5", "high, 4", "medium, 3", "low, 2", "lowest, 1"})
    void priorityRankSortsByImportance(String priority, int rank) {
        assertThat(IssueDocumentMapper.priorityRank(priority)).isEqualTo(rank);
    }

    @Test
    void unknownPriorityIsAnError() {
        assertThatIllegalArgumentException().isThrownBy(() -> IssueDocumentMapper.priorityRank("urgent"));
    }

    private static IssueSnapshot snapshot(String priority) {
        return new IssueSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "MAR", "MAR-131", 131, "story",
                "Pricing page", "Text", UUID.randomUUID(), "in_progress", priority, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("3.0"), "AV", List.of("web"),
                LocalDate.parse("2026-11-20"), null, true, 120, 30, "1.2", null, false, 4, CREATED, UPDATED);
    }
}
