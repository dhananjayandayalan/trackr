package dev.trakr.api.board.internal;

import static dev.trakr.jooq.Tables.APP_USER;
import static dev.trakr.jooq.Tables.BOARD;
import static dev.trakr.jooq.Tables.BOARD_COLUMN;
import static dev.trakr.jooq.Tables.ISSUE;
import static dev.trakr.jooq.Tables.PROJECT_MEMBER;
import static dev.trakr.jooq.Tables.SPRINT;

import dev.trakr.api.board.BoardColumnView;
import dev.trakr.api.board.BoardFilter;
import dev.trakr.api.board.BoardView;
import dev.trakr.api.board.BoardView.BoardRef;
import dev.trakr.api.board.BoardView.Card;
import dev.trakr.api.board.BoardView.Column;
import dev.trakr.api.board.BoardView.EpicTag;
import dev.trakr.api.board.BoardView.Person;
import dev.trakr.api.board.BoardView.SprintRef;
import dev.trakr.api.shared.web.EpicColors;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The board screen as SQL. One small query finds the board and the sprint; then one query returns
 * the columns with their cards nested (MULTISET), plus the epic and people lookup maps, so the whole
 * screen is two round trips however many columns and cards there are.
 *
 * <p>Reads issue, sprint and app_user — other modules' tables, read-only, like every *Queries class.
 */
@Component
public class BoardQueries {

    // kanban boards have no sprints, so the Done column would grow forever: show the last 14 days
    private static final Duration KANBAN_DONE_WINDOW = Duration.ofDays(14);
    private static final Duration RECENT = Duration.ofHours(24);

    private final DSLContext dsl;

    BoardQueries(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record Context(BoardRef board, @Nullable SprintRef sprint) {
    }

    /** The project's board, and the sprint it shows: the requested one, or the active one (scrum only). */
    public Optional<Context> context(UUID projectId, @Nullable UUID sprintId) {
        return dsl.select(BOARD.ID, BOARD.NAME, BOARD.TYPE)
                .from(BOARD)
                .where(BOARD.PROJECT_ID.eq(projectId))
                .fetchOptional(Records.mapping(BoardRef::new))
                .map(board -> new Context(board, "scrum".equals(board.type()) ? sprint(projectId, sprintId) : null));
    }

    private @Nullable SprintRef sprint(UUID projectId, @Nullable UUID sprintId) {
        Condition which = sprintId == null ? SPRINT.STATE.eq("active") : SPRINT.ID.eq(sprintId);
        return dsl.select(SPRINT.ID, SPRINT.NAME, SPRINT.GOAL, SPRINT.STATE, SPRINT.STARTS_ON, SPRINT.ENDS_ON)
                .from(SPRINT)
                .where(SPRINT.PROJECT_ID.eq(projectId), which)
                .fetchOptional(Records.mapping(SprintRef::new))
                .orElse(null);
    }

    public BoardView view(UUID projectId, Context context, BoardFilter filter, Instant now) {
        // BLOCK 1: which issues belong on this board at all (the column count) …
        var card = ISSUE.as("card");
        Condition onBoard = card.PROJECT_ID.eq(projectId)
                .and(card.STATUS_ID.eq(DSL.any(BOARD_COLUMN.STATUS_IDS)))
                .and(card.DELETED_AT.isNull())
                // epics are containers, not cards
                .and(card.TYPE.ne("epic"));
        if ("scrum".equals(context.board().type())) {
            // no active sprint: an empty board, not every issue of the project
            onBoard = onBoard.and(context.sprint() == null ? DSL.falseCondition()
                    : card.SPRINT_ID.eq(context.sprint().id()));
        } else {
            // resolved_at is set exactly while an issue is in a done status (Issue.transitionTo)
            onBoard = onBoard.and(card.RESOLVED_AT.isNull()
                    .or(card.RESOLVED_AT.ge(now.minus(KANBAN_DONE_WINDOW).atOffset(ZoneOffset.UTC))));
        }

        // BLOCK 2: … and which of those the filters keep on screen
        List<Condition> filters = new ArrayList<>();
        if (!filter.assigneeIds().isEmpty()) {
            filters.add(card.ASSIGNEE_ID.in(filter.assigneeIds()));
        }
        if (filter.type() != null) {
            filters.add(card.TYPE.eq(filter.type()));
        }
        if (filter.recent()) {
            filters.add(card.UPDATED_AT.ge(now.minus(RECENT).atOffset(ZoneOffset.UTC)));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            String q = filter.q().strip();
            filters.add(card.SUMMARY.containsIgnoreCase(q).or(card.KEY.equalIgnoreCase(q)));
        }

        // BLOCK 3: a subtask's epic is its parent's parent; for stories, bugs and tasks the parent is the epic.
        // CASE evaluates the subquery only for subtasks, so other cards cost no extra lookup.
        var parent = ISSUE.as("card_parent");
        Field<UUID> epicId = DSL.when(card.TYPE.eq("subtask"),
                        DSL.field(DSL.select(parent.PARENT_ID).from(parent).where(parent.ID.eq(card.PARENT_ID))))
                .else_(card.PARENT_ID);

        Field<List<Card>> cards = DSL.multiset(
                        DSL.select(card.KEY, card.ID, card.SUMMARY, card.TYPE, card.PRIORITY, card.STORY_POINTS,
                                        card.ASSIGNEE_ID, epicId, card.FLAGGED, card.RANK, card.STATUS_ID, card.VERSION)
                                .from(card)
                                .where(onBoard.and(DSL.and(filters)))
                                .orderBy(card.RANK))
                .as("cards")
                .convertFrom(rows -> rows.map(Records.mapping(Card::new)));
        Field<Integer> count = DSL.field(DSL.selectCount().from(card).where(onBoard));

        // BLOCK 4: the columns, in board order, each with its cards
        Field<List<Column>> columns = DSL.multiset(
                        DSL.select(BOARD_COLUMN.ID, BOARD_COLUMN.NAME, BOARD_COLUMN.STATUS_IDS, BOARD_COLUMN.WIP_LIMIT,
                                        count, cards)
                                .from(BOARD_COLUMN)
                                .where(BOARD_COLUMN.BOARD_ID.eq(context.board().id()))
                                .orderBy(BOARD_COLUMN.POSITION))
                .as("columns")
                .convertFrom(rows -> rows.map(r -> new Column(r.value1(), r.value2(), List.of(r.value3()), r.value4(),
                        r.value5(), r.value4() != null && r.value5() > r.value4(), r.value6())));

        // BLOCK 5: lookup maps — the project's epics, and everyone who is a member or has an issue here
        var epic = ISSUE.as("epic");
        Field<Map<UUID, EpicTag>> epics = DSL.multiset(
                        DSL.select(epic.ID, epic.KEY, epic.SUMMARY, epic.NUMBER)
                                .from(epic)
                                .where(epic.PROJECT_ID.eq(projectId), epic.TYPE.eq("epic"), epic.DELETED_AT.isNull())
                                .orderBy(epic.NUMBER))
                .as("epics")
                .convertFrom(rows -> {
                    Map<UUID, EpicTag> byId = new LinkedHashMap<>();
                    rows.forEach(r -> byId.put(r.value1(), new EpicTag(r.value2(), r.value3(),
                            EpicColors.of(r.value4()))));
                    return byId;
                });
        var assigned = ISSUE.as("assigned");
        Field<Map<UUID, Person>> people = DSL.multiset(
                        DSL.select(APP_USER.ID, APP_USER.DISPLAY_NAME, APP_USER.INITIALS, APP_USER.AVATAR_COLOR)
                                .from(APP_USER)
                                .where(APP_USER.ID.in(DSL.select(PROJECT_MEMBER.USER_ID).from(PROJECT_MEMBER)
                                                .where(PROJECT_MEMBER.PROJECT_ID.eq(projectId)))
                                        .or(APP_USER.ID.in(DSL.select(assigned.ASSIGNEE_ID).from(assigned)
                                                .where(assigned.PROJECT_ID.eq(projectId), assigned.DELETED_AT.isNull(),
                                                        assigned.ASSIGNEE_ID.isNotNull()))))
                                .orderBy(APP_USER.DISPLAY_NAME))
                .as("people")
                .convertFrom(rows -> {
                    Map<UUID, Person> byId = new LinkedHashMap<>();
                    rows.forEach(r -> byId.put(r.value1(), new Person(r.value2(), r.value3(), r.value4())));
                    return byId;
                });

        // BLOCK 6: one SELECT without FROM, three nested results — one round trip for the screen
        var result = dsl.select(columns, epics, people).fetchSingle();
        return new BoardView(context.board(), context.sprint(), result.value1(), result.value2(), result.value3());
    }

    /** The column showing {@code statusId} on the project's board, if any (Kanban's Backlog has none). */
    public Optional<BoardColumnView> columnFor(UUID projectId, UUID statusId) {
        return dsl.select(BOARD_COLUMN.ID, BOARD_COLUMN.NAME, BOARD_COLUMN.STATUS_IDS, BOARD_COLUMN.WIP_LIMIT, BOARD.TYPE)
                .from(BOARD_COLUMN)
                .join(BOARD).on(BOARD.ID.eq(BOARD_COLUMN.BOARD_ID))
                .where(BOARD.PROJECT_ID.eq(projectId), DSL.val(statusId).eq(DSL.any(BOARD_COLUMN.STATUS_IDS)))
                .fetchOptional(r -> new BoardColumnView(r.value1(), r.value2(), List.of(r.value3()), r.value4(),
                        r.value5()));
    }
}
