package dev.trakr.api.search.internal.tql;

import dev.trakr.api.shared.web.TrakrException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A query the user has to fix: 400 with {@code position} (0-based character offset of the mistake) and
 * {@code expected} (what would have been valid there), so the query editor can underline the exact spot
 * and offer the alternatives (03-api §2).
 *
 * <p>Two codes: {@code TQL_SYNTAX_ERROR} when the text doesn't parse ({@code status = = Done}), and
 * {@code TQL_INVALID_QUERY} when it parses but means nothing ({@code stauts = Done}, {@code assignee = Nobody}).
 */
public class TqlException extends TrakrException {

    private static final long serialVersionUID = 1L;

    private final int position;
    private final transient List<String> expected;

    private TqlException(String code, String detail, int position, List<String> expected) {
        super(HttpStatus.BAD_REQUEST, code, detail);
        this.position = position;
        this.expected = List.copyOf(expected);
    }

    public static TqlException syntax(String detail, int position, List<String> expected) {
        return new TqlException("TQL_SYNTAX_ERROR", detail, position, expected);
    }

    public static TqlException invalid(String detail, int position, List<String> expected) {
        return new TqlException("TQL_INVALID_QUERY", detail, position, expected);
    }

    public static TqlException invalid(String detail, int position) {
        return invalid(detail, position, List.of());
    }

    public int position() {
        return position;
    }

    public List<String> expected() {
        return expected;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("position", position, "expected", expected);
    }
}
