package dev.trakr.api.board.internal;

import dev.trakr.api.project.ProjectCreated;
import dev.trakr.api.project.Template;
import dev.trakr.api.workflow.WorkflowService;
import dev.trakr.api.workflow.WorkflowStatusView;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives every new project its board: one column per status, except the statuses a template keeps
 * off the board (00-product §5). Same synchronous listener as the workflow's TemplateSeeder, and
 * after it (@Order(2)), because the columns are built from the statuses it just saved.
 */
@Component
class BoardSeeder {

    // Kanban's Backlog and Bug tracking's Triage are lists, not board columns
    private static final Map<Template, Set<String>> OFF_BOARD = Map.of(
            Template.KANBAN, Set.of("Backlog"),
            Template.BUGS, Set.of("Triage"));

    private final WorkflowService workflowService;
    private final BoardRepository boards;
    private final BoardColumnRepository columns;

    BoardSeeder(WorkflowService workflowService, BoardRepository boards, BoardColumnRepository columns) {
        this.workflowService = workflowService;
        this.boards = boards;
        this.columns = columns;
    }

    @EventListener
    @Order(2)
    void on(ProjectCreated event) {
        String type = event.template() == Template.SCRUM ? "scrum" : "kanban";
        Board board = boards.save(Board.create(event.projectId(), event.key() + " board", type));

        Set<String> offBoard = OFF_BOARD.getOrDefault(event.template(), Set.of());
        List<WorkflowStatusView> onBoard = workflowService.statusesOf(event.projectId()).stream()
                .filter(status -> !offBoard.contains(status.name()))
                .toList();
        for (int position = 0; position < onBoard.size(); position++) {
            WorkflowStatusView status = onBoard.get(position);
            columns.save(BoardColumn.create(board.id(), status.name(), position, List.of(status.id()),
                    status.wipLimit()));
        }
    }
}
