package dev.trakr.api.issue;

import java.util.List;

/** {@code warnings}: WIP_LIMIT_EXCEEDED, NEIGHBOURS_CHANGED — the move happened, but look again. */
public record MoveResult(IssueDetails issue, List<String> warnings) {

    public MoveResult {
        warnings = List.copyOf(warnings);
    }
}
