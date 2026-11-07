package dev.trakr.api.board;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The board's query parameters. {@code sprintId} null = the active sprint (ignored on kanban
 * boards); {@code type} is an issue type value ("bug"); {@code recent} = updated in the last 24 h.
 */
public record BoardFilter(@Nullable UUID sprintId, List<UUID> assigneeIds, @Nullable String type, boolean recent,
        @Nullable String q) {

    public BoardFilter {
        assigneeIds = List.copyOf(assigneeIds);
    }

    public static BoardFilter none() {
        return new BoardFilter(null, List.of(), null, false, null);
    }
}
