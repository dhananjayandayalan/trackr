package dev.trakr.api.board.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardColumnRepository extends JpaRepository<BoardColumn, UUID> {
}
