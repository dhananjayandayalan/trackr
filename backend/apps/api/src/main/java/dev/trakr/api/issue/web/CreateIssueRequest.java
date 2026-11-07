package dev.trakr.api.issue.web;

import dev.trakr.api.issue.CreateIssue;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.Priority;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record CreateIssueRequest(
        @NotNull @Pattern(regexp = "^[A-Z]{2,6}$", message = "must be a project key") String projectKey,
        @NotNull IssueType type,
        @NotBlank @Size(max = 255) String summary,
        @Nullable @Size(max = 50_000) String description,
        @Nullable UUID assigneeId,
        @Nullable Priority priority,
        @Nullable UUID sprintId,
        @Nullable @Pattern(regexp = IssueRequests.KEY, message = "must be an issue key") String parentKey,
        // 00-product §4.7: 0–100, one decimal
        @Nullable @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 1) BigDecimal storyPoints,
        @Nullable LocalDate dueDate,
        @Nullable @Size(max = 20) List<@NotBlank @Size(max = 50) @Pattern(regexp = IssueRequests.LABEL,
                message = "must not contain spaces") String> labels) {

    CreateIssue toCommand() {
        return new CreateIssue(projectKey, type, summary, description, assigneeId, priority, sprintId, parentKey,
                storyPoints, dueDate, labels == null ? List.of() : labels);
    }
}
