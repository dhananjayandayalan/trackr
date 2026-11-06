package dev.trakr.api.board.internal;

import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "board_column")
public class BoardColumn {

    @Id
    private UUID id;
    private UUID boardId;
    private String name;
    private int position;
    // a Java array maps straight onto the Postgres uuid[] column
    private UUID[] statusIds;
    private @Nullable Integer wipLimit;

    protected BoardColumn() {
        // for JPA
    }

    public static BoardColumn create(UUID boardId, String name, int position, List<UUID> statusIds,
            @Nullable Integer wipLimit) {
        BoardColumn column = new BoardColumn();
        column.id = Ids.newId();
        column.boardId = boardId;
        column.name = name;
        column.position = position;
        column.statusIds = statusIds.toArray(UUID[]::new);
        column.wipLimit = wipLimit;
        return column;
    }
}
