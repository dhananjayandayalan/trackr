package dev.trakr.api.board;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * GET /projects/{key}/board (03-api §3.3): everything the board screen draws. {@code epics} and
 * {@code people} are lookup maps, so a card carries only ids and the same name isn't repeated on
 * every card.
 */
public record BoardView(BoardRef board, @Nullable SprintRef sprint, List<Column> columns, Map<UUID, EpicTag> epics,
        Map<UUID, Person> people) {

    /** {@code type}: scrum (cards of one sprint) or kanban (all cards, done ones for 14 days). */
    public record BoardRef(UUID id, String name, String type) {
    }

    public record SprintRef(UUID id, String name, @Nullable String goal, String state, @Nullable LocalDate startsOn,
            @Nullable LocalDate endsOn) {
    }

    /** {@code count} ignores the filters: the WIP limit is about the real column, not what's on screen. */
    public record Column(UUID id, String name, List<UUID> statusIds, @Nullable Integer wipLimit, int count,
            boolean overLimit, List<Card> cards) {
    }

    /** Exactly the columns of ix_issue_board, so the card query never touches the table. */
    public record Card(String key, UUID id, String summary, String type, String priority,
            @Nullable BigDecimal storyPoints, @Nullable UUID assigneeId, @Nullable UUID epicId, boolean flagged,
            String rank, UUID statusId, long version) {
    }

    public record EpicTag(String key, String name, String color) {
    }

    public record Person(String name, String initials, String color) {
    }
}
