package dev.trakr.api.board.internal;

import static dev.trakr.jooq.Tables.BOARD;
import static dev.trakr.jooq.Tables.BOARD_COLUMN;

import dev.trakr.api.workflow.WorkflowReplaced;
import dev.trakr.api.workflow.WorkflowStatusView;
import dev.trakr.kernel.Ids;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record4;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The board follows its workflow (phase 17). Synchronous, inside the workflow save, before deleted statuses
 * are removed: a column never maps a status that doesn't exist.
 *
 * <ul>
 *   <li>a deleted status leaves every column; a column left with no status goes too</li>
 *   <li>a new status gets a column of its own at the end (move it in board settings)</li>
 *   <li>a one-status column takes the status's WIP limit, and its name when it was named after the status</li>
 * </ul>
 * Statuses that were never on the board (Kanban's Backlog) stay off it.
 */
@Component
class ColumnSync {

    private final DSLContext dsl;

    ColumnSync(DSLContext dsl) {
        this.dsl = dsl;
    }

    @EventListener
    void on(WorkflowReplaced event) {
        UUID boardId = dsl.select(BOARD.ID).from(BOARD).where(BOARD.PROJECT_ID.eq(event.projectId()))
                .fetchOptional(BOARD.ID).orElse(null);
        if (boardId == null) {
            return;
        }
        Map<UUID, WorkflowStatusView> statuses = new HashMap<>();
        event.statuses().forEach(status -> statuses.put(status.id(), status));

        // BLOCK 1: existing columns — drop deleted statuses, follow renames and WIP limits
        List<Record4<UUID, String, Integer, UUID[]>> columns = dsl
                .select(BOARD_COLUMN.ID, BOARD_COLUMN.NAME, BOARD_COLUMN.POSITION, BOARD_COLUMN.STATUS_IDS)
                .from(BOARD_COLUMN).where(BOARD_COLUMN.BOARD_ID.eq(boardId)).orderBy(BOARD_COLUMN.POSITION).fetch();
        int lastPosition = -1;
        for (var column : columns) {
            lastPosition = Math.max(lastPosition, column.value3());
            UUID[] kept = Arrays.stream(column.value4()).filter(id -> !event.deleted().contains(id)).toArray(UUID[]::new);
            if (kept.length == 0) {
                dsl.deleteFrom(BOARD_COLUMN).where(BOARD_COLUMN.ID.eq(column.value1())).execute();
                continue;
            }
            String name = column.value2();
            Integer wipLimit = null;
            boolean single = kept.length == 1 && statuses.containsKey(kept[0]);
            if (single) {
                WorkflowStatusView status = statuses.get(kept[0]);
                wipLimit = status.wipLimit();
                if (name.equals(event.previousNames().get(status.id()))) {
                    name = status.name();
                }
            }
            var update = dsl.update(BOARD_COLUMN).set(BOARD_COLUMN.STATUS_IDS, kept).set(BOARD_COLUMN.NAME, name);
            // a column over several statuses keeps its own limit: no single status's limit is "the" limit
            (single ? update.set(BOARD_COLUMN.WIP_LIMIT, wipLimit) : update)
                    .where(BOARD_COLUMN.ID.eq(column.value1())).execute();
        }

        // BLOCK 2: new statuses, in workflow order, each a new last column
        for (WorkflowStatusView status : event.statuses()) {
            if (event.added().contains(status.id())) {
                dsl.insertInto(BOARD_COLUMN)
                        .set(BOARD_COLUMN.ID, Ids.newId())
                        .set(BOARD_COLUMN.BOARD_ID, boardId)
                        .set(BOARD_COLUMN.NAME, status.name())
                        .set(BOARD_COLUMN.POSITION, ++lastPosition)
                        .set(BOARD_COLUMN.STATUS_IDS, new UUID[] {status.id()})
                        .set(BOARD_COLUMN.WIP_LIMIT, status.wipLimit())
                        .execute();
            }
        }
    }
}
