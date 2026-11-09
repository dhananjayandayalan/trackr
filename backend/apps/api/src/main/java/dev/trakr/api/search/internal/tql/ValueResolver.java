package dev.trakr.api.search.internal.tql;

import co.elastic.clients.elasticsearch._types.FieldValue;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.Priority;
import dev.trakr.api.search.internal.tql.FieldRegistry.FieldSpec;
import dev.trakr.api.search.internal.tql.FieldRegistry.Kind;
import dev.trakr.api.search.internal.tql.ast.Value;
import dev.trakr.api.search.internal.tql.ast.Value.DateValue;
import dev.trakr.api.search.internal.tql.ast.Value.FunctionCall;
import dev.trakr.api.search.internal.tql.ast.Value.Ident;
import dev.trakr.api.search.internal.tql.ast.Value.IssueKey;
import dev.trakr.api.search.internal.tql.ast.Value.Num;
import dev.trakr.api.search.internal.tql.ast.Value.RelDate;
import dev.trakr.api.search.internal.tql.ast.Value.Str;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * What a value means for its field: {@code assignee = "Ada Lovelace"} → Ada's user id,
 * {@code sprint in openSprints()} → the ids of the active and planned sprints,
 * {@code created >= -7d} → an instant 7 days before now. The index stores ids, ranks and instants, so
 * names have to become those before Elasticsearch sees them.
 *
 * <p>Lookups go through a {@link Directory} that only sees what the caller may see: a name can't be used
 * to probe for users or sprints in other workspaces. Unit tests pass a fake one and a fixed clock.
 */
public final class ValueResolver {

    /** Name → id lookups, already scoped to the caller's workspaces and visible projects. */
    public interface Directory {

        /** Users whose display name or email is exactly this (case-insensitive). */
        List<UUID> usersNamed(String nameOrEmail);

        /** The explicit members of a project the caller can see; empty for an unknown key. */
        List<UUID> membersOf(String projectKey);

        List<UUID> sprintsNamed(String name);

        List<UUID> sprintsInStates(Set<String> states);

        /** The live issue with this key, as a list (empty or one). */
        List<UUID> issuesWithKey(String key);

        /** The spellings of statuses with this name, e.g. "done" → ["Done"]; empty if no project has one. */
        List<String> statusNames(String name);
    }

    /** A date bound for a range query. {@code wholeDay}: a calendar date, which means the whole day. */
    public record DateBound(String value, boolean wholeDay) {
    }

    private static final List<String> USER_FUNCTIONS = List.of("currentUser()", "membersOf(\"KEY\")");
    private static final List<String> SPRINT_FUNCTIONS = List.of("openSprints()", "closedSprints()");
    private static final List<String> DATE_FUNCTIONS = List.of("startOfDay()", "endOfDay()", "startOfWeek()",
            "endOfWeek()", "startOfMonth()", "endOfMonth()");
    private static final List<String> CATEGORIES = List.of("todo", "in_progress", "done");

    private final Directory directory;
    private final UUID currentUser;
    private final Clock clock;

    public ValueResolver(Directory directory, UUID currentUser, Clock clock) {
        this.directory = directory;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * The exact values a {@code =}, {@code !=} or {@code IN} compares against. Usually one; a function like
     * {@code openSprints()} can give several, or none (then the clause matches nothing).
     */
    public List<FieldValue> terms(FieldSpec field, Value value) {
        return switch (field.kind()) {
            case PROJECT, KEY -> List.of(FieldValue.of(word(field, value).toUpperCase(Locale.ROOT)));
            case TYPE -> List.of(FieldValue.of(oneOf(field, value, Arrays.stream(IssueType.values())
                    .map(IssueType::value).toList())));
            case PRIORITY -> List.of(FieldValue.of(priorityRank(field, value)));
            case STATUS -> statuses(field, value);
            case STATUS_CATEGORY -> List.of(FieldValue.of(oneOf(field,
                    new Str(word(field, value).replace(' ', '_').replace('-', '_'), value.position()), CATEGORIES)));
            case USER -> ids(users(field, value));
            case SPRINT -> ids(sprints(field, value));
            case ISSUE -> ids(issue(field, value));
            case LABEL -> List.of(FieldValue.of(word(field, value)));
            case NUMBER -> List.of(FieldValue.of(number(field, value)));
            case BOOLEAN -> List.of(FieldValue.of(bool(field, value)));
            case DATE, TEXT, SORT_ONLY -> throw new IllegalArgumentException(field.name() + " has no exact values");
        };
    }

    /** {@code storyPoints > 3}, and priorities as their rank: {@code priority >= High} is {@code priorityRank >= 4}. */
    public double number(FieldSpec field, Value value) {
        if (field.kind() == Kind.PRIORITY) {
            return priorityRank(field, value);
        }
        if (value instanceof Num num) {
            return Double.parseDouble(num.text());
        }
        throw TqlException.invalid("'" + field.name() + "' needs a number.", value.position(), List.of("number"));
    }

    /**
     * A date comparison's bound. Calendar dates mean the whole day ({@code created = 2026-11-09} is every
     * moment of that day); relative dates and functions are exact instants.
     */
    public DateBound date(FieldSpec field, Value value) {
        return switch (value) {
            case DateValue date -> {
                try {
                    yield new DateBound(LocalDate.parse(date.text()).toString(), true);
                } catch (DateTimeParseException e) {
                    throw TqlException.invalid("'" + date.text() + "' is not a date.", date.position());
                }
            }
            case RelDate relative -> new DateBound(relative(relative).toString(), false);
            case FunctionCall function -> new DateBound(dateFunction(field, function).toString(), false);
            default -> throw TqlException.invalid("'" + field.name()
                    + "' needs a date (2026-01-31), a relative date (-7d) or a date function.", value.position(),
                    DATE_FUNCTIONS);
        };
    }

    // BLOCK: per kind

    private List<FieldValue> statuses(FieldSpec field, Value value) {
        List<String> names = directory.statusNames(word(field, value));
        if (names.isEmpty()) {
            throw TqlException.invalid("No status named '" + value.text() + "'.", value.position());
        }
        return names.stream().map(FieldValue::of).toList();
    }

    private List<UUID> users(FieldSpec field, Value value) {
        if (value instanceof FunctionCall function) {
            return switch (function.name().toLowerCase(Locale.ROOT)) {
                case "currentuser" -> List.of(currentUser);
                case "membersof" -> {
                    if (function.args().size() != 1) {
                        throw TqlException.invalid("membersOf() takes one project key.", function.position());
                    }
                    yield directory.membersOf(function.args().getFirst().text().toUpperCase(Locale.ROOT));
                }
                default -> throw unknownFunction(field, function, USER_FUNCTIONS);
            };
        }
        List<UUID> users = directory.usersNamed(word(field, value));
        if (users.isEmpty()) {
            throw TqlException.invalid("No user named '" + value.text() + "'.", value.position(), USER_FUNCTIONS);
        }
        return users;
    }

    private List<UUID> sprints(FieldSpec field, Value value) {
        if (value instanceof FunctionCall function) {
            return switch (function.name().toLowerCase(Locale.ROOT)) {
                case "opensprints" -> directory.sprintsInStates(Set.of("active", "planned"));
                case "closedsprints" -> directory.sprintsInStates(Set.of("closed"));
                default -> throw unknownFunction(field, function, SPRINT_FUNCTIONS);
            };
        }
        List<UUID> sprints = directory.sprintsNamed(word(field, value));
        if (sprints.isEmpty()) {
            throw TqlException.invalid("No sprint named '" + value.text() + "'.", value.position(), SPRINT_FUNCTIONS);
        }
        return sprints;
    }

    private List<UUID> issue(FieldSpec field, Value value) {
        String key = word(field, value).toUpperCase(Locale.ROOT);
        List<UUID> issues = directory.issuesWithKey(key);
        if (issues.isEmpty()) {
            throw TqlException.invalid("No issue " + key + ".", value.position());
        }
        return issues;
    }

    private long priorityRank(FieldSpec field, Value value) {
        List<String> names = Arrays.stream(Priority.values()).map(Priority::value).toList();
        return Priority.fromValue(oneOf(field, value, names)).weight();
    }

    private boolean bool(FieldSpec field, Value value) {
        return Boolean.parseBoolean(oneOf(field, value, List.of("true", "false")));
    }

    // a fixed list of lowercase values: "Bug" and "BUG" are bug, "Bugg" is an error that lists the choices
    private static String oneOf(FieldSpec field, Value value, List<String> allowed) {
        String candidate = word(field, value).toLowerCase(Locale.ROOT);
        if (!allowed.contains(candidate)) {
            throw TqlException.invalid("'" + value.text() + "' is not a " + field.name() + ".", value.position(),
                    allowed);
        }
        return candidate;
    }

    // the text of a plain value; functions only where a field gives them a meaning
    private static String word(FieldSpec field, Value value) {
        return switch (value) {
            case Ident _, Str _, Num _, IssueKey _ -> value.text();
            case FunctionCall function -> throw unknownFunction(field, function, List.of());
            case RelDate _, DateValue _ -> throw TqlException.invalid("'" + field.name() + "' can't be compared to a date.",
                    value.position());
        };
    }

    // BLOCK: time — everything in UTC (03-api §1), weeks run Monday to Sunday

    private Instant relative(RelDate value) {
        String text = value.text();
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        // 'm' is minutes, as in Jira; weeks are 7 days (Duration has no exact length for WEEKS or MONTHS)
        Duration offset = switch (Character.toLowerCase(text.charAt(text.length() - 1))) {
            case 'd' -> Duration.ofDays(amount);
            case 'w' -> Duration.ofDays(amount * 7);
            case 'h' -> Duration.ofHours(amount);
            case 'm' -> Duration.ofMinutes(amount);
            default -> throw TqlException.invalid("Unknown time unit in '" + text + "'.", value.position());
        };
        return clock.instant().plus(offset);
    }

    private Instant dateFunction(FieldSpec field, FunctionCall function) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return switch (function.name().toLowerCase(Locale.ROOT)) {
            case "startofday" -> startOf(today);
            case "endofday" -> endOf(today);
            case "startofweek" -> startOf(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            case "endofweek" -> endOf(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)));
            case "startofmonth" -> startOf(today.with(TemporalAdjusters.firstDayOfMonth()));
            case "endofmonth" -> endOf(today.with(TemporalAdjusters.lastDayOfMonth()));
            default -> throw unknownFunction(field, function, DATE_FUNCTIONS);
        };
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    // the last millisecond of the day: Elasticsearch dates have millisecond precision
    private static Instant endOf(LocalDate day) {
        return startOf(day.plusDays(1)).minusMillis(1);
    }

    private static List<FieldValue> ids(List<UUID> ids) {
        return ids.stream().map(id -> FieldValue.of(id.toString())).toList();
    }

    private static TqlException unknownFunction(FieldSpec field, FunctionCall function, List<String> allowed) {
        return TqlException.invalid("'" + function.name() + "()' can't be used with '" + field.name() + "'.",
                function.position(), allowed);
    }
}
