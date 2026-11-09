package dev.trakr.api.search.internal;

import dev.trakr.api.search.SearchViews.SearchHit;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * One CSV line per issue (RFC 4180: comma-separated, fields with a comma, quote or line break in double
 * quotes, quotes doubled).
 *
 * <p>Formula injection: a spreadsheet runs a cell starting with {@code = + - @} as a formula, so a summary
 * like {@code =HYPERLINK(…)} would execute on the reader's machine. Such cells get a leading apostrophe,
 * which spreadsheets show as plain text.
 */
public final class CsvRows {

    public static final String HEADER = line(Stream.of("Key", "Summary", "Type", "Status", "Priority", "Assignee",
            "Sprint", "Story points", "Labels", "Due", "Created", "Updated"));

    private CsvRows() {
    }

    public static String row(SearchHit hit) {
        return line(Stream.of(hit.key(), hit.summary(), hit.type(), hit.statusName(), hit.priority(),
                hit.assigneeName(), hit.sprintName(), hit.storyPoints() == null ? null : hit.storyPoints().toPlainString(),
                String.join(" ", hit.labels()), Objects.toString(hit.dueDate(), null), hit.createdAt().toString(),
                hit.updatedAt().toString()));
    }

    private static String line(Stream<@Nullable String> cells) {
        return cells.map(CsvRows::cell).collect(Collectors.joining(",", "", "\r\n"));
    }

    static String cell(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = List.of('=', '+', '-', '@', '\t', '\r').contains(value.charAt(0)) ? "'" + value : value;
        boolean quote = safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r");
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }
}
