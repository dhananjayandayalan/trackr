package dev.trakr.workers.search;

import dev.trakr.contracts.events.IssueSnapshot;
import java.util.List;

/** Snapshot + context → document. Pure: no lookups, no I/O, so it can be tested field by field. */
public final class IssueDocumentMapper {

    private IssueDocumentMapper() {
    }

    public static IssueDocument toDocument(IssueSnapshot s, IssueContext context) {
        return new IssueDocument(
                s.id(), s.workspaceId(), s.projectId(), s.projectKey(), s.key(), s.number(), s.type(), s.priority(),
                priorityRank(s.priority()), s.statusCategory(), s.statusId(), context.statusName(), s.summary(),
                s.descriptionText(),
                // comments arrive in phase 15; until then there is nothing to index
                "",
                s.labels(), s.assigneeId(), s.reporterId(), context.assigneeName(), s.parentId(), context.epicId(),
                context.epicName(), s.sprintId(), context.sprintName(), context.sprintState(), s.storyPoints(),
                s.flagged(), context.watcherIds(), List.of(), s.createdAt(), s.updatedAt(), s.resolvedAt(),
                s.dueDate(), s.startDate(), s.rank(), s.deleted());
    }

    /** highest = 5 … lowest = 1, so "ORDER BY priority" sorts by importance, not alphabetically. */
    static int priorityRank(String priority) {
        return switch (priority) {
            case "highest" -> 5;
            case "high" -> 4;
            case "medium" -> 3;
            case "low" -> 2;
            case "lowest" -> 1;
            default -> throw new IllegalArgumentException("unknown priority: " + priority);
        };
    }
}
