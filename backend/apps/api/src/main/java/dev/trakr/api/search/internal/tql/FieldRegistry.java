package dev.trakr.api.search.internal.tql;

import static dev.trakr.api.search.internal.tql.ast.Op.EQ;
import static dev.trakr.api.search.internal.tql.ast.Op.GE;
import static dev.trakr.api.search.internal.tql.ast.Op.GT;
import static dev.trakr.api.search.internal.tql.ast.Op.LE;
import static dev.trakr.api.search.internal.tql.ast.Op.LT;
import static dev.trakr.api.search.internal.tql.ast.Op.NE;

import dev.trakr.api.search.internal.tql.ast.And;
import dev.trakr.api.search.internal.tql.ast.Compare;
import dev.trakr.api.search.internal.tql.ast.Expr;
import dev.trakr.api.search.internal.tql.ast.Field;
import dev.trakr.api.search.internal.tql.ast.In;
import dev.trakr.api.search.internal.tql.ast.IsEmpty;
import dev.trakr.api.search.internal.tql.ast.Not;
import dev.trakr.api.search.internal.tql.ast.Op;
import dev.trakr.api.search.internal.tql.ast.Or;
import dev.trakr.api.search.internal.tql.ast.OrderBy;
import dev.trakr.api.search.internal.tql.ast.TextMatch;
import dev.trakr.api.search.internal.tql.ast.TqlQuery;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which TQL fields exist, what they are called in the index, and what may be done with them (05-search §3,
 * "Fields → ES"). The grammar accepts any name as a field; this table is where {@code stauts = Done} is
 * caught — with a message naming the field and the position, instead of a vague syntax error.
 */
public final class FieldRegistry {

    /** What kind of values a field holds; decides how ValueResolver reads a value and how it is compared. */
    public enum Kind {
        PROJECT, KEY, TYPE, PRIORITY, STATUS, STATUS_CATEGORY, USER, SPRINT, ISSUE, LABEL, TEXT, NUMBER, DATE,
        BOOLEAN, SORT_ONLY
    }

    /**
     * One field. {@code esField} is the index field queries use; {@code sortFields} what ORDER BY sorts on
     * (key sorts by project, then number, so MAR-9 comes before MAR-10); empty = not sortable.
     */
    public record FieldSpec(String name, Kind kind, String esField, Set<Op> ops, boolean in, boolean empty,
            List<String> sortFields) {

        public boolean text() {
            return kind == Kind.TEXT;
        }

        public boolean queryable() {
            return kind != Kind.SORT_ONLY;
        }

        public boolean sortable() {
            return !sortFields.isEmpty();
        }

        /** The operators as the editor offers them, in a stable order. */
        public List<String> operatorTexts() {
            List<String> texts = new ArrayList<>();
            if (text()) {
                texts.add("~");
            }
            for (Op op : Op.values()) {
                if (ops.contains(op)) {
                    texts.add(op.symbol());
                }
            }
            if (in) {
                texts.add("in");
                texts.add("not in");
            }
            if (empty) {
                texts.add("is empty");
                texts.add("is not empty");
            }
            return texts;
        }
    }

    private static final Set<Op> EQUALITY = EnumSet.of(EQ, NE);
    private static final Set<Op> ALL = EnumSet.allOf(Op.class);
    private static final Set<Op> NONE = EnumSet.noneOf(Op.class);

    // BLOCK: the table — insertion order is the order autocomplete lists them in
    private static final Map<String, FieldSpec> FIELDS = new LinkedHashMap<>();

    static {
        add("project", Kind.PROJECT, "projectKey", EQUALITY, true, false, "projectKey");
        add("key", Kind.KEY, "key", EQUALITY, true, false, "projectKey", "number");
        add("type", Kind.TYPE, "type", EQUALITY, true, false, "type");
        add("priority", Kind.PRIORITY, "priorityRank", ALL, true, false, "priorityRank");
        add("status", Kind.STATUS, "statusName", EQUALITY, true, false, "statusName");
        add("statusCategory", Kind.STATUS_CATEGORY, "statusCategory", EQUALITY, true, false, "statusCategory");
        add("assignee", Kind.USER, "assigneeId", EQUALITY, true, true, "assigneeName");
        add("reporter", Kind.USER, "reporterId", EQUALITY, true, false);
        add("watcher", Kind.USER, "watcherIds", EQUALITY, true, false);
        add("sprint", Kind.SPRINT, "sprintId", EQUALITY, true, true, "sprintName");
        add("epic", Kind.ISSUE, "epicId", EQUALITY, true, true, "epicName");
        add("parent", Kind.ISSUE, "parentId", EQUALITY, true, true);
        add("labels", Kind.LABEL, "labels", EQUALITY, true, true);
        // text = summary (weighted ×3), description and comments together; EsQueryCompiler knows the list
        add("text", Kind.TEXT, "", NONE, false, false);
        add("summary", Kind.TEXT, "summary", NONE, false, false, "summary.raw");
        add("storyPoints", Kind.NUMBER, "storyPoints", ALL, true, true, "storyPoints");
        add("created", Kind.DATE, "createdAt", ALL, false, false, "createdAt");
        add("updated", Kind.DATE, "updatedAt", ALL, false, false, "updatedAt");
        add("resolved", Kind.DATE, "resolvedAt", ALL, false, true, "resolvedAt");
        add("due", Kind.DATE, "dueDate", ALL, false, true, "dueDate");
        add("flagged", Kind.BOOLEAN, "flagged", EQUALITY, false, false);
        add("rank", Kind.SORT_ONLY, "rank", NONE, false, false, "rank");
    }

    private FieldRegistry() {
    }

    private static void add(String name, Kind kind, String esField, Set<Op> ops, boolean in, boolean empty,
            String... sortFields) {
        FIELDS.put(name.toLowerCase(Locale.ROOT),
                new FieldSpec(name, kind, esField, ops, in, empty, List.of(sortFields)));
    }

    public static Optional<FieldSpec> find(String name) {
        return Optional.ofNullable(FIELDS.get(name.toLowerCase(Locale.ROOT)));
    }

    public static List<FieldSpec> queryable() {
        return FIELDS.values().stream().filter(FieldSpec::queryable).toList();
    }

    public static List<FieldSpec> sortable() {
        return FIELDS.values().stream().filter(FieldSpec::sortable).toList();
    }

    // BLOCK: checks — each names the field, the position and what would have been allowed

    /** The field of a clause, or TQL_INVALID_QUERY naming every field there is. */
    public static FieldSpec require(Field field) {
        return find(field.key()).filter(FieldSpec::queryable).orElseThrow(() -> TqlException.invalid(
                "Unknown field '" + field.name() + "'.", field.position(),
                queryable().stream().map(FieldSpec::name).toList()));
    }

    public static FieldSpec requireSortable(Field field) {
        return find(field.key()).filter(FieldSpec::sortable).orElseThrow(() -> TqlException.invalid(
                "Can't sort by '" + field.name() + "'.", field.position(),
                sortable().stream().map(FieldSpec::name).toList()));
    }

    /** Every field and operator of a query, without resolving values: what saving a filter checks. */
    public static void validate(TqlQuery query) {
        if (query.where() != null) {
            validate(query.where());
        }
        query.orderBy().stream().map(OrderBy::field).forEach(FieldRegistry::requireSortable);
    }

    /** The spec for a clause, after checking that its operator is one the field supports. */
    public static FieldSpec check(Expr clause) {
        return switch (clause) {
            case Compare(Field field, Op op, _) -> allowed(field, op.symbol(), require(field).ops().contains(op));
            case In(Field field, boolean negated, _) ->
                    allowed(field, negated ? "not in" : "in", require(field).in());
            case IsEmpty(Field field, boolean negated) ->
                    allowed(field, negated ? "is not empty" : "is empty", require(field).empty());
            case TextMatch(Field field, _) -> allowed(field, "~", require(field).text());
            case And a -> throw new IllegalArgumentException("not a clause: " + a);
            case Or o -> throw new IllegalArgumentException("not a clause: " + o);
            case Not n -> throw new IllegalArgumentException("not a clause: " + n);
        };
    }

    private static void validate(Expr expr) {
        switch (expr) {
            case And(Expr left, Expr right) -> {
                validate(left);
                validate(right);
            }
            case Or(Expr left, Expr right) -> {
                validate(left);
                validate(right);
            }
            case Not(Expr inner) -> validate(inner);
            case Compare c -> check(c);
            case In i -> check(i);
            case IsEmpty e -> check(e);
            case TextMatch t -> check(t);
        }
    }

    private static FieldSpec allowed(Field field, String operator, boolean supported) {
        FieldSpec spec = require(field);
        if (!supported) {
            throw TqlException.invalid("'" + spec.name() + "' doesn't support '" + operator + "'.", field.position(),
                    spec.operatorTexts());
        }
        return spec;
    }
}
