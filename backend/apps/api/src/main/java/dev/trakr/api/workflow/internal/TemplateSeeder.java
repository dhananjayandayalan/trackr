package dev.trakr.api.workflow.internal;

import dev.trakr.api.project.ProjectCreated;
import dev.trakr.api.project.Template;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives every new project the statuses of its template (00-product §5).
 *
 * <p>{@code @EventListener} runs synchronously, on the same thread and inside ProjectService.create's
 * transaction: if seeding fails, the project is rolled back too — no project without a workflow.
 * {@code @ApplicationModuleListener} would run after commit in its own transaction (async): right
 * for side effects like emails, wrong here, where the project must not exist half-made.
 */
@Component
class TemplateSeeder {

    record StatusSeed(String name, String category, String color, @Nullable Integer wipLimit) {
    }

    // colours are the VOLT status tokens (--status-todo, --status-progress, …)
    private static final Map<Template, List<StatusSeed>> STATUSES = Map.of(
            Template.SCRUM, List.of(todo("To do"), inProgress("In progress"), review("In review"), done("Done")),
            Template.KANBAN, List.of(todo("Backlog"), todo("Selected"),
                    new StatusSeed("In progress", "in_progress", "progress", 4), done("Done")),
            Template.BUGS, List.of(todo("Triage"), todo("Confirmed"), inProgress("Fixing"), review("Verifying"),
                    done("Closed")),
            Template.BLANK, List.of(todo("To do"), inProgress("In progress"), done("Done")));

    private final WorkflowStatusRepository statuses;
    private final WorkflowTransitionRepository transitions;

    TemplateSeeder(WorkflowStatusRepository statuses, WorkflowTransitionRepository transitions) {
        this.statuses = statuses;
        this.transitions = transitions;
    }

    // @Order(1): the board seeder listens to the same event and needs these statuses, so it goes second
    @EventListener
    @Order(1)
    void on(ProjectCreated event) {
        List<WorkflowStatus> seeded = new ArrayList<>();
        List<StatusSeed> seeds = STATUSES.get(event.template());
        for (int position = 0; position < seeds.size(); position++) {
            StatusSeed seed = seeds.get(position);
            seeded.add(WorkflowStatus.create(event.projectId(), seed.name(), seed.category(), position, seed.color(),
                    seed.wipLimit()));
        }
        statuses.saveAll(seeded);

        // every status → every other status; projects make it stricter later in workflow settings
        List<WorkflowTransition> all = new ArrayList<>();
        for (WorkflowStatus from : seeded) {
            for (WorkflowStatus to : seeded) {
                if (!from.id().equals(to.id())) {
                    all.add(WorkflowTransition.between(event.projectId(), from.id(), to.id()));
                }
            }
        }
        transitions.saveAll(all);
    }

    private static StatusSeed todo(String name) {
        return new StatusSeed(name, "todo", "todo", null);
    }

    private static StatusSeed inProgress(String name) {
        return new StatusSeed(name, "in_progress", "progress", null);
    }

    // a second in-progress step, coloured differently on the board
    private static StatusSeed review(String name) {
        return new StatusSeed(name, "in_progress", "review", null);
    }

    private static StatusSeed done(String name) {
        return new StatusSeed(name, "done", "done", null);
    }
}
