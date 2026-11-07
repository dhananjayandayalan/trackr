package dev.trakr.api.board.web;

import dev.trakr.api.board.BoardFilter;
import dev.trakr.api.board.BoardService;
import dev.trakr.api.board.BoardView;
import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BoardController {

    private static final Set<String> TYPES = Set.of("story", "bug", "task", "subtask");

    private final BoardService boards;

    BoardController(BoardService boards) {
        this.boards = boards;
    }

    /**
     * {@code sprint}: "active" (default) or a sprint id; {@code assignee} may repeat
     * (?assignee=a&amp;assignee=b); {@code recent}: updated in the last 24 hours.
     */
    @Operation(operationId = "getBoard")
    @GetMapping("/api/v1/projects/{key}/board")
    BoardView board(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestParam(defaultValue = "active") String sprint,
            @RequestParam(name = "assignee", required = false) @Nullable List<UUID> assignees,
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(defaultValue = "false") boolean recent,
            @RequestParam(required = false) @Nullable String q) {
        if (type != null && !TYPES.contains(type)) {
            throw invalid("type", "must be story, bug, task or subtask");
        }
        return boards.view(principal.id(), key, new BoardFilter(sprintId(sprint),
                assignees == null ? List.of() : assignees, type, recent, q));
    }

    private static @Nullable UUID sprintId(String sprint) {
        if ("active".equals(sprint)) {
            return null;
        }
        try {
            return UUID.fromString(sprint);
        } catch (IllegalArgumentException e) {
            throw invalid("sprint", "must be \"active\" or a sprint id");
        }
    }

    private static TrakrException invalid(String field, String message) {
        return new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid board filter.",
                List.of(new FieldMessage(field, message)));
    }
}
