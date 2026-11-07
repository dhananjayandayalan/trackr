package dev.trakr.api.board;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One board column as other modules see it; {@code boardType} is scrum or kanban. */
public record BoardColumnView(UUID id, String name, List<UUID> statusIds, @Nullable Integer wipLimit,
        String boardType) {

    public BoardColumnView {
        statusIds = List.copyOf(statusIds);
    }
}
