package dev.trakr.api.board.internal;

import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "board")
public class Board {

    @Id
    private UUID id;
    private UUID projectId;
    private String name;
    private String type;

    protected Board() {
        // for JPA
    }

    public static Board create(UUID projectId, String name, String type) {
        Board board = new Board();
        board.id = Ids.newId();
        board.projectId = projectId;
        board.name = name;
        board.type = type;
        return board;
    }

    public UUID id() {
        return id;
    }
}
