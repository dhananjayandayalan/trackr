package dev.trakr.api.issue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** POST /issues, already validated for shape; IssueService checks what needs the database. */
public record CreateIssue(String projectKey, IssueType type, String summary, @Nullable String description,
        @Nullable UUID assigneeId, @Nullable Priority priority, @Nullable UUID sprintId, @Nullable String parentKey,
        @Nullable BigDecimal storyPoints, @Nullable LocalDate dueDate, List<String> labels) {

    public CreateIssue {
        labels = List.copyOf(labels);
    }
}
