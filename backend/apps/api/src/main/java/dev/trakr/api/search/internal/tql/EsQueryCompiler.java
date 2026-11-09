package dev.trakr.api.search.internal.tql;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import dev.trakr.api.search.internal.tql.FieldRegistry.FieldSpec;
import dev.trakr.api.search.internal.tql.FieldRegistry.Kind;
import dev.trakr.api.search.internal.tql.ValueResolver.DateBound;
import dev.trakr.api.search.internal.tql.ast.And;
import dev.trakr.api.search.internal.tql.ast.Compare;
import dev.trakr.api.search.internal.tql.ast.Expr;
import dev.trakr.api.search.internal.tql.ast.In;
import dev.trakr.api.search.internal.tql.ast.IsEmpty;
import dev.trakr.api.search.internal.tql.ast.Not;
import dev.trakr.api.search.internal.tql.ast.Op;
import dev.trakr.api.search.internal.tql.ast.Or;
import dev.trakr.api.search.internal.tql.ast.OrderBy;
import dev.trakr.api.search.internal.tql.ast.TextMatch;
import dev.trakr.api.search.internal.tql.ast.TqlQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AST → Elasticsearch query, the back end of the pipeline. Built with the typed Java client, never by
 * gluing strings: a value can't break out of its place in the JSON (09-security §2).
 *
 * <p>The query is only what the user asked for. SearchService wraps it in the security filter, which
 * this class never sees and the user can't influence.
 */
public final class EsQueryCompiler {

    /** {@code scored}: the query has a text clause, so results can be ranked by relevance. */
    public record Compiled(Query query, List<SortOptions> sort, boolean scored) {
    }

    // text ~ "…" searches these; a hit in the summary counts three times as much as one in the description
    static final List<String> TEXT_FIELDS = List.of("summary^3", "descriptionText", "commentsText");

    private final ValueResolver values;

    public EsQueryCompiler(ValueResolver values) {
        this.values = values;
    }

    public Compiled compile(TqlQuery tql) {
        Expr where = tql.where();
        Query query = where == null ? Query.of(q -> q.matchAll(m -> m)) : compile(where);
        boolean scored = where != null && scores(where);
        return new Compiled(query, sort(tql.orderBy(), scored), scored);
    }

    // BLOCK 1: dispatch — the sealed interface makes this switch exhaustive: no default, and a new Expr
    // record that isn't handled here is a compile error
    Query compile(Expr expr) {
        return switch (expr) {
            case And a -> and(a);
            case Or o -> or(o);
            case Not(Expr inner) -> Query.of(q -> q.bool(b -> b.mustNot(compile(inner))));
            case Compare c -> compare(c);
            case In in -> in(in);
            case IsEmpty empty -> isEmpty(empty);
            case TextMatch text -> text(text);
        };
    }

    // BLOCK 2: boolean nodes. (a AND b) AND c is one bool with three clauses, not two nested ones.
    // filter vs must: both mean "has to match", but filter doesn't score (and Elasticsearch can cache it).
    // A child that contains a text clause goes into must, so its relevance score counts.
    private Query and(And and) {
        List<Expr> children = new ArrayList<>();
        flatten(and, children, true);
        BoolQuery.Builder bool = new BoolQuery.Builder();
        for (Expr child : children) {
            if (scores(child)) {
                bool.must(compile(child));
            } else {
                bool.filter(compile(child));
            }
        }
        return bool.build()._toQuery();
    }

    private Query or(Or or) {
        List<Expr> children = new ArrayList<>();
        flatten(or, children, false);
        return Query.of(q -> q.bool(b -> b.should(children.stream().map(this::compile).toList())
                .minimumShouldMatch("1")));
    }

    private static void flatten(Expr expr, List<Expr> into, boolean ands) {
        switch (expr) {
            case And(Expr left, Expr right) when ands -> {
                flatten(left, into, true);
                flatten(right, into, true);
            }
            case Or(Expr left, Expr right) when !ands -> {
                flatten(left, into, false);
                flatten(right, into, false);
            }
            default -> into.add(expr);
        }
    }

    // a NOT never scores: must_not only excludes
    private static boolean scores(Expr expr) {
        return switch (expr) {
            case TextMatch _ -> true;
            case And(Expr left, Expr right) -> scores(left) || scores(right);
            case Or(Expr left, Expr right) -> scores(left) || scores(right);
            case Not _, Compare _, In _, IsEmpty _ -> false;
        };
    }

    // BLOCK 3: comparisons — term/terms for exact values, range for dates and numbers
    private Query compare(Compare compare) {
        FieldSpec field = FieldRegistry.check(compare);
        Op op = compare.op();
        if (field.kind() == Kind.DATE) {
            DateBound bound = values.date(field, compare.value());
            Query range = dateRange(field.esField(), op, bound);
            return op == Op.NE ? not(range) : range;
        }
        if (op.isRange()) {
            return numberRange(field.esField(), op, values.number(field, compare.value()));
        }
        Query equal = terms(field.esField(), values.terms(field, compare.value()));
        return op == Op.NE ? not(equal) : equal;
    }

    private Query in(In in) {
        FieldSpec field = FieldRegistry.check(in);
        // ("Ada Lovelace", membersOf("MAR")) can name Ada twice: send each value once
        Map<Object, FieldValue> unique = new LinkedHashMap<>();
        in.values().stream().flatMap(value -> values.terms(field, value).stream())
                .forEach(value -> unique.putIfAbsent(value._get(), value));
        Query any = terms(field.esField(), List.copyOf(unique.values()));
        return in.negated() ? not(any) : any;
    }

    private static Query terms(String field, List<FieldValue> values) {
        if (values.size() == 1) {
            return Query.of(q -> q.term(t -> t.field(field).value(values.getFirst())));
        }
        // also for zero values (openSprints() when no sprint is open): an empty terms list matches nothing
        return Query.of(q -> q.terms(t -> t.field(field).terms(v -> v.value(values))));
    }

    /**
     * Dates: {@code ||/d} is Elasticsearch date math for "rounded to the day", and a range rounds it the
     * way the operator needs — gte/lt down to 00:00, gt/lte up to 23:59:59.999. So a calendar date means
     * the whole day: {@code created > 2026-11-09} starts on the 10th, {@code created = 2026-11-09} is all
     * of the 9th. Equality on an exact instant (-7d) also means its whole day.
     */
    private static Query dateRange(String field, Op op, DateBound bound) {
        String day = bound.value() + "||/d";
        String value = bound.wholeDay() ? day : bound.value();
        return Query.of(q -> q.range(r -> r.date(d -> {
            d.field(field);
            return switch (op) {
                case EQ, NE -> d.gte(day).lte(day);
                case GT -> d.gt(value);
                case GE -> d.gte(value);
                case LT -> d.lt(value);
                case LE -> d.lte(value);
            };
        })));
    }

    private static Query numberRange(String field, Op op, double value) {
        return Query.of(q -> q.range(r -> r.number(n -> {
            n.field(field);
            return switch (op) {
                case GT -> n.gt(value);
                case GE -> n.gte(value);
                case LT -> n.lt(value);
                case LE -> n.lte(value);
                case EQ, NE -> throw new IllegalArgumentException("not a range: " + op);
            };
        })));
    }

    // BLOCK 4: text — analysed and fuzzy: "chekout" still finds "checkout", every word has to appear
    private static Query text(TextMatch text) {
        FieldSpec field = FieldRegistry.check(text);
        if (field.esField().isEmpty()) {
            return Query.of(q -> q.multiMatch(m -> m.query(text.text()).fields(TEXT_FIELDS)
                    .type(TextQueryType.BestFields).operator(Operator.And).fuzziness("AUTO")));
        }
        return Query.of(q -> q.match(m -> m.field(field.esField()).query(text.text())
                .operator(Operator.And).fuzziness("AUTO")));
    }

    // BLOCK 5: is empty — "has no value" is "doesn't have the field", so it's the negation of exists
    private static Query isEmpty(IsEmpty empty) {
        FieldSpec field = FieldRegistry.check(empty);
        Query exists = Query.of(q -> q.exists(e -> e.field(field.esField())));
        return empty.negated() ? exists : not(exists);
    }

    private static Query not(Query query) {
        return Query.of(q -> q.bool(b -> b.mustNot(query)));
    }

    // BLOCK 6: sorting. Without ORDER BY: best match first when there is text to rank by, else most
    // recently updated. Always last: id, so issues with equal sort values still have one fixed order —
    // search_after needs that, or a page boundary could skip or repeat an issue.
    static List<SortOptions> sort(List<OrderBy> orderBy, boolean scored) {
        List<SortOptions> sort = new ArrayList<>();
        if (orderBy.isEmpty()) {
            sort.add(scored ? SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc)))
                    : field("updatedAt", SortOrder.Desc));
        }
        for (OrderBy key : orderBy) {
            FieldSpec field = FieldRegistry.requireSortable(key.field());
            SortOrder order = key.descending() ? SortOrder.Desc : SortOrder.Asc;
            field.sortFields().forEach(name -> sort.add(field(name, order)));
        }
        sort.add(field("id", SortOrder.Asc));
        return sort;
    }

    private static SortOptions field(String name, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(name).order(order)));
    }
}
