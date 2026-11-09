package dev.trakr.api.project;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * GET /projects/{key} (03-api §3.2): the project's fields at the top level (@JsonUnwrapped), plus
 * its workflow, board, members and the caller's role. Statuses and columns are read straight from
 * the workflow and board tables — a read-only view; those modules still own every write.
 */
public record ProjectDetails(@JsonUnwrapped ProjectView project, List<Status> statuses, List<Transition> transitions,
        @Nullable Board board, List<MemberView> members, ProjectRole myRole) {

    public record Status(UUID id, String name, String category, int position, String color, @Nullable Integer wipLimit) {
    }

    /** {@code fromStatusId} null = from any status. */
    public record Transition(UUID id, @Nullable UUID fromStatusId, UUID toStatusId) {
    }

    public record Board(UUID id, String name, String type, List<Column> columns) {
    }

    @Schema(name = "ProjectColumn")
    public record Column(UUID id, String name, int position, List<UUID> statusIds, @Nullable Integer wipLimit) {
    }
}
