package dev.trakr.api.home;

import dev.trakr.api.home.HomeViews.ActivityItem;
import dev.trakr.api.home.HomeViews.IssueRow;
import dev.trakr.api.home.HomeViews.Stats;
import dev.trakr.api.home.HomeViews.Tab;
import dev.trakr.api.home.internal.HomeQueries;
import dev.trakr.api.home.internal.HomeQueries.Position;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.TrakrException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The home module's public API: the "Your work" screen. Everything is about the caller, so no project check. */
@Service
public class HomeService {

    private static final int PAGE = 50;

    private final HomeQueries queries;
    private final Clock clock;

    HomeService(HomeQueries queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    /** The cursor is the last row's "at|id", base64url-encoded; one extra row tells whether there is a next page. */
    @Transactional(readOnly = true)
    public CursorPage<IssueRow> work(UUID userId, Tab tab, @Nullable String cursor) {
        List<IssueRow> rows = queries.tab(userId, tab, cursor == null ? null : position(cursor), PAGE + 1,
                clock.instant());
        if (rows.size() <= PAGE) {
            return new CursorPage<>(rows, null);
        }
        List<IssueRow> page = rows.subList(0, PAGE);
        IssueRow last = page.getLast();
        return new CursorPage<>(page, CursorPage.encode(last.at() + "|" + last.id()));
    }

    @Transactional(readOnly = true)
    public Stats stats(UUID userId) {
        return queries.stats(userId, LocalDate.now(clock));
    }

    @Transactional(readOnly = true)
    public List<ActivityItem> activity(UUID userId, int limit) {
        return queries.activity(userId, Math.clamp(limit, 1, 100));
    }

    private static Position position(String cursor) {
        String[] parts = CursorPage.decode(cursor).split("\\|", 2);
        try {
            return new Position(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (DateTimeParseException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The cursor is not valid.");
        }
    }
}
