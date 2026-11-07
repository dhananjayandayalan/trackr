package dev.trakr.contracts.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a sprint delivered (02-data-model §4 sprint_report). Points are story points; issues without
 * points count as 0. committed = in the sprint when it started; added = in it at the end but not at
 * the start; removed = in it at the start but not at the end; completed = done at the end.
 */
public record SprintReport(
        UUID sprintId,
        BigDecimal committedPoints,
        BigDecimal completedPoints,
        BigDecimal addedPoints,
        BigDecimal removedPoints,
        List<UUID> completedIssueIds,
        List<UUID> incompleteIssueIds,
        Instant closedAt) {

    public SprintReport {
        completedIssueIds = List.copyOf(completedIssueIds);
        incompleteIssueIds = List.copyOf(incompleteIssueIds);
    }
}
