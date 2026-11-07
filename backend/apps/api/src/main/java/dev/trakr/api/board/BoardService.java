package dev.trakr.api.board;

import dev.trakr.api.board.internal.BoardQueries;
import dev.trakr.api.project.ProjectAccess;
import dev.trakr.api.project.ProjectAccess.Grant;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.shared.web.TrakrException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The board module's public API: the board screen, and column lookups for WIP checks. */
@Service
public class BoardService {

    private final BoardQueries queries;
    private final ProjectAccess access;
    private final Clock clock;

    BoardService(BoardQueries queries, ProjectAccess access, Clock clock) {
        this.queries = queries;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BoardView view(UUID userId, String projectKey, BoardFilter filter) {
        Grant grant = access.require(userId, projectKey, ProjectRole.VIEWER);
        BoardQueries.Context context = queries.context(grant.projectId(), filter.sprintId())
                .orElseThrow(() -> new TrakrException.NotFound("Project " + projectKey + " has no board."));
        if (filter.sprintId() != null && context.sprint() == null && "scrum".equals(context.board().type())) {
            throw new TrakrException.NotFound("No such sprint in " + projectKey + ".");
        }
        return queries.view(grant.projectId(), context, filter, clock.instant());
    }

    /** For the issue module's WIP check; the caller has already checked access to the project. */
    @Transactional(readOnly = true)
    public Optional<BoardColumnView> columnFor(UUID projectId, UUID statusId) {
        return queries.columnFor(projectId, statusId);
    }
}
