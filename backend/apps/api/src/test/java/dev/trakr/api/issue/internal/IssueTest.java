package dev.trakr.api.issue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.InvalidParent;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.PatchIssue;
import dev.trakr.api.issue.PatchIssue.Value;
import dev.trakr.api.issue.Priority;
import dev.trakr.api.workflow.WorkflowStatusView;
import dev.trakr.kernel.Ids;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

// no Spring, no database: the aggregate's rules are plain Java
class IssueTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID WORKSPACE = Ids.newId();
    private static final UUID PROJECT = Ids.newId();
    private static final WorkflowStatusView TODO = status("To do", "todo");
    private static final WorkflowStatusView IN_PROGRESS = status("In progress", "in_progress");
    private static final WorkflowStatusView DONE = status("Done", "done");
    private static final WorkflowStatusView CLOSED = status("Closed", "done");

    // BLOCK 1: transitions and resolvedAt
    @Test
    void movesIssueToDoneAndSetsResolvedAt() {
        Issue issue = story();

        assertThat(issue.transitionTo(DONE, CLOCK)).isTrue();

        assertThat(issue.statusCategory()).isEqualTo("done");
        assertThat(issue.resolvedAt()).isEqualTo(NOW);
    }

    @Test
    void leavingDoneClearsResolvedAt() {
        Issue issue = story();
        issue.transitionTo(DONE, CLOCK);

        issue.transitionTo(IN_PROGRESS, CLOCK);

        assertThat(issue.resolvedAt()).isNull();
    }

    @Test
    void doneToAnotherDoneStatusKeepsResolvedAt() {
        Issue issue = story();
        issue.transitionTo(DONE, CLOCK);

        issue.transitionTo(CLOSED, Clock.offset(CLOCK, Duration.ofHours(1)));

        assertThat(issue.resolvedAt()).isEqualTo(NOW);
    }

    @Test
    void transitionToTheCurrentStatusChangesNothing() {
        assertThat(story().transitionTo(TODO, CLOCK)).isFalse();
    }

    // BLOCK 2: parent rules (00-product §4.2)
    @Test
    void subtaskWithoutParentIsRejected() {
        assertThatThrownBy(() -> create(IssueType.SUBTASK, null))
                .isInstanceOf(InvalidParent.class)
                .hasMessageContaining("needs a parent");
    }

    @Test
    void subtaskUnderAStoryIsFine() {
        Issue story = story();

        Issue subtask = create(IssueType.SUBTASK, parentOf(story));

        assertThat(subtask.parentId()).isEqualTo(story.id());
    }

    @Test
    void epicCannotHaveAParent() {
        Issue epic = create(IssueType.EPIC, null);

        assertThatThrownBy(() -> create(IssueType.EPIC, parentOf(epic))).isInstanceOf(InvalidParent.class);
    }

    @Test
    void storyCanOnlyHangUnderAnEpic() {
        Issue epic = create(IssueType.EPIC, null);
        Issue story = story();

        assertThat(create(IssueType.BUG, parentOf(epic)).parentId()).isEqualTo(epic.id());
        assertThatThrownBy(() -> create(IssueType.TASK, parentOf(story))).isInstanceOf(InvalidParent.class);
    }

    @Test
    void parentMustBeInTheSameProject() {
        Parent elsewhere = new Parent(Ids.newId(), IssueType.STORY, Ids.newId());

        assertThatThrownBy(() -> create(IssueType.SUBTASK, elsewhere))
                .isInstanceOf(InvalidParent.class)
                .hasMessageContaining("same project");
    }

    @Test
    void issueWithSubtasksCannotBecomeASubtask() {
        Issue story = story();
        Issue epic = create(IssueType.EPIC, null);
        PatchIssue toSubtask = new PatchIssue(null, null, new Value<>(IssueType.SUBTASK), null, null,
                new Value<>(epic.key()), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> story.apply(toSubtask, parentOf(epic), Set.of(IssueType.SUBTASK), CLOCK))
                .isInstanceOf(InvalidParent.class);
    }

    // BLOCK 3: apply(PatchIssue) reports exactly what changed
    @Test
    void patchReturnsExactChanges() {
        Issue issue = story();
        UUID assignee = Ids.newId();
        PatchIssue patch = new PatchIssue(new Value<>("  Better summary "), null, null, new Value<>(Priority.HIGH),
                new Value<>(assignee), null, new Value<>(new BigDecimal("3")), new Value<>(List.of("ui", " ui", "api")),
                null, null, null, null, null);

        List<FieldChange> changes = issue.apply(patch, null, Set.of(), CLOCK);

        assertThat(changes).containsExactly(
                new FieldChange("summary", "First summary", "Better summary"),
                new FieldChange("priority", "medium", "high"),
                new FieldChange("assignee", null, assignee.toString()),
                new FieldChange("storyPoints", null, new BigDecimal("3.0")),
                new FieldChange("labels", List.of(), List.of("ui", "api")));
    }

    @Test
    void patchWithCurrentValuesChangesNothing() {
        Issue issue = story();
        PatchIssue same = PatchIssue.none().withSummary("First summary ").withPriority(Priority.MEDIUM);

        assertThat(issue.apply(same, null, Set.of(), CLOCK)).isEmpty();
    }

    @Test
    void nullClearsAField() {
        Issue issue = story();
        issue.apply(new PatchIssue(null, new Value<>("Some text"), null, null, null, null, null, null, null, null,
                null, null, null), null, Set.of(), CLOCK);

        List<FieldChange> changes = issue.apply(new PatchIssue(null, new Value<>(null), null, null, null, null, null,
                null, null, null, null, null, null), null, Set.of(), CLOCK);

        assertThat(changes).containsExactly(new FieldChange("description", "Some text", null));
    }

    @Test
    void descriptionBecomesEscapedHtml() {
        Issue issue = create(IssueType.TASK, null, "Use <b> & \"quotes\"\n\nsecond paragraph");

        assertThat(issue.snapshot().descriptionText()).isEqualTo("Use <b> & \"quotes\"\n\nsecond paragraph");
        assertThat(PlainTextHtml.of("Use <b> & \"quotes\"\n\nline one\nline two"))
                .isEqualTo("<p>Use &lt;b&gt; &amp; &quot;quotes&quot;</p><p>line one<br>line two</p>");
    }

    // BLOCK 4: snapshot and soft delete
    @Test
    void snapshotCarriesTheIssuesColumns() {
        Issue issue = story();

        assertThat(issue.snapshot())
                .satisfies(snapshot -> {
                    assertThat(snapshot.key()).isEqualTo("MAR-1");
                    assertThat(snapshot.projectKey()).isEqualTo("MAR");
                    assertThat(snapshot.type()).isEqualTo("story");
                    assertThat(snapshot.priority()).isEqualTo("medium");
                    assertThat(snapshot.statusCategory()).isEqualTo("todo");
                    assertThat(snapshot.version()).isZero();
                    assertThat(snapshot.deleted()).isFalse();
                });
    }

    @Test
    void softDeleteHappensOnce() {
        Issue issue = story();

        assertThat(issue.softDelete(CLOCK)).isTrue();
        assertThat(issue.softDelete(CLOCK)).isFalse();
        assertThat(issue.snapshot().deleted()).isTrue();
    }

    private static Issue story() {
        return create(IssueType.STORY, null);
    }

    private static Issue create(IssueType type, @Nullable Parent parent) {
        return create(type, parent, null);
    }

    // number and key don't matter to these rules; every issue here is MAR-1 with its own id
    private static Issue create(IssueType type, @Nullable Parent parent, @Nullable String description) {
        CreateIssue cmd = new CreateIssue("MAR", type, "First summary", description, null, null, null, null, null,
                null, List.of());
        return Issue.create(cmd, WORKSPACE, PROJECT, 1, "MAR-1", TODO, "V", Ids.newId(), parent, CLOCK);
    }

    private static Parent parentOf(Issue issue) {
        return new Parent(issue.id(), issue.type(), issue.projectId());
    }

    private static WorkflowStatusView status(String name, String category) {
        return new WorkflowStatusView(Ids.newId(), name, category, 0, "todo", null);
    }
}
