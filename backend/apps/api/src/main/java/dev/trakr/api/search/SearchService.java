package dev.trakr.api.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.HighlighterEncoder;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.NamedValue;
import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.Priority;
import dev.trakr.api.search.SearchViews.Completion;
import dev.trakr.api.search.SearchViews.CompletionKind;
import dev.trakr.api.search.SearchViews.Completions;
import dev.trakr.api.search.SearchViews.IssueSuggestion;
import dev.trakr.api.search.SearchViews.SearchHit;
import dev.trakr.api.search.SearchViews.SearchPage;
import dev.trakr.api.search.SearchViews.Suggestions;
import dev.trakr.api.search.internal.CsvRows;
import dev.trakr.api.search.internal.CursorCodec;
import dev.trakr.api.search.internal.CursorCodec.Cursor;
import dev.trakr.api.search.internal.IssueSource;
import dev.trakr.api.search.internal.SearchQueries;
import dev.trakr.api.search.internal.SecurityScope;
import dev.trakr.api.search.internal.SecurityScope.Scope;
import dev.trakr.api.search.internal.tql.EsQueryCompiler;
import dev.trakr.api.search.internal.tql.EsQueryCompiler.Compiled;
import dev.trakr.api.search.internal.tql.FieldRegistry;
import dev.trakr.api.search.internal.tql.FieldRegistry.FieldSpec;
import dev.trakr.api.search.internal.tql.TqlCompleter;
import dev.trakr.api.search.internal.tql.TqlParser;
import dev.trakr.api.search.internal.tql.ValueResolver;
import dev.trakr.api.search.internal.tql.ast.TqlQuery;
import dev.trakr.api.shared.config.TrakrProperties;
import dev.trakr.api.shared.web.TrakrException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The search module's public API (05-search §3–4): TQL search, CSV export, autocomplete, quick search.
 *
 * <p>The pipeline, every request: parse → check fields → resolve values → compile → wrap in the
 * security filter → Elasticsearch. Nothing is cached between requests except the point-in-time a cursor
 * names, and even then the query and the filter are rebuilt.
 */
@Service
public class SearchService {

    public static final int MAX_LIMIT = 100;
    public static final int MAX_EXPORT = 10_000;
    private static final int EXPORT_PAGE = 500;
    private static final Time KEEP_ALIVE = Time.of(t -> t.time("1m"));
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    // matched words in <mark>; encoder html escapes everything else, so a summary containing <script> comes
    // back as &lt;script&gt; and the fragments are safe to render as HTML
    private static final Highlight HIGHLIGHT = Highlight.of(h -> h
            .encoder(HighlighterEncoder.Html)
            .preTags("<mark>")
            .postTags("</mark>")
            .fields(NamedValue.of("summary", HighlightField.of(f -> f.numberOfFragments(0))),
                    NamedValue.of("descriptionText", HighlightField.of(f -> f.fragmentSize(150).numberOfFragments(1)))));

    private final ElasticsearchClient elasticsearch;
    private final SecurityScope security;
    private final SearchQueries queries;
    private final Clock clock;
    private final String index;

    SearchService(ElasticsearchClient elasticsearch, SecurityScope security, SearchQueries queries, Clock clock,
            TrakrProperties properties) {
        this.elasticsearch = elasticsearch;
        this.security = security;
        this.queries = queries;
        this.clock = clock;
        this.index = properties.search().index();
    }

    /** A user's query, compiled and wrapped, ready to run page after page. */
    private record Prepared(Query query, List<SortOptions> sort) {
    }

    // BLOCK 1: parse → resolve → compile → wrap
    private Prepared prepare(UUID userId, String tql) {
        TqlQuery parsed = TqlParser.parse(tql);
        // fields and operators first: a typo is reported before any lookup runs
        FieldRegistry.validate(parsed);
        Scope scope = security.of(userId);
        ValueResolver values = new ValueResolver(security.directory(scope), userId, clock);
        Compiled compiled = new EsQueryCompiler(values).compile(parsed);
        // side by side, never merged: the user's query can only narrow what the filter allows
        Query wrapped = Query.of(q -> q.bool(b -> b.filter(scope.filter()).must(compiled.query())));
        return new Prepared(wrapped, compiled.sort());
    }

    /**
     * One page. The first opens a point-in-time — a frozen view of the index — and every later page
     * reads that same view, continuing after the last hit's sort values (search_after). So an issue that
     * is edited, created or deleted meanwhile can't shift the pages: nothing is skipped or shown twice.
     * from/size paging would instead make Elasticsearch collect and throw away from+size hits on every
     * shard for every page — page 500 costs 500 pages' work — and still shift as documents change.
     */
    public SearchPage search(UUID userId, String tql, int limit, @Nullable String cursor) {
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        Prepared prepared = prepare(userId, tql);
        Cursor position = cursor == null ? null : CursorCodec.decode(cursor);
        String pit = position == null ? openPit() : position.pitId();

        SearchResponse<IssueSource> response = page(prepared, pit, position == null ? null : position.after(), size,
                true);
        List<Hit<IssueSource>> hits = response.hits().hits();
        List<SearchHit> items = hits.stream().map(SearchService::toHit).toList();
        long total = response.hits().total() == null ? items.size() : response.hits().total().value();

        String next = null;
        if (hits.size() == size) {
            // every search returns the PIT id to use next (it can change); the cursor carries it forward
            next = CursorCodec.encode(new Cursor(response.pitId(), hits.getLast().sort()));
        } else {
            closePit(response.pitId());
        }
        return new SearchPage(total, items, next, response.took());
    }

    /** CSV of every match, up to {@link #MAX_EXPORT}. Errors in the query surface here, before any byte is written. */
    public Export export(UUID userId, String tql) {
        Prepared prepared = prepare(userId, tql);
        return out -> {
            // a page at a time straight to the response: memory stays at one page, whatever the export's size
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write(CsvRows.HEADER);
            String pit = openPit();
            try {
                List<FieldValue> after = null;
                int written = 0;
                while (written < MAX_EXPORT) {
                    int size = Math.min(EXPORT_PAGE, MAX_EXPORT - written);
                    SearchResponse<IssueSource> response = page(prepared, pit, after, size, false);
                    pit = response.pitId();
                    List<Hit<IssueSource>> hits = response.hits().hits();
                    for (Hit<IssueSource> hit : hits) {
                        writer.write(CsvRows.row(toHit(hit)));
                    }
                    written += hits.size();
                    if (hits.size() < size) {
                        break;
                    }
                    after = hits.getLast().sort();
                }
            } finally {
                closePit(pit);
            }
            writer.flush();
        };
    }

    /** Writes the CSV; called by the web layer once the response has started. */
    @FunctionalInterface
    public interface Export {
        void writeTo(OutputStream out) throws IOException;
    }

    // BLOCK 2: Elasticsearch calls
    private SearchResponse<IssueSource> page(Prepared prepared, String pit, @Nullable List<FieldValue> after, int size,
            boolean highlight) {
        SearchRequest.Builder request = new SearchRequest.Builder()
                // with a PIT the index is part of the PIT, so the request names none
                .pit(p -> p.id(pit).keepAlive(KEEP_ALIVE))
                .query(prepared.query())
                .sort(prepared.sort())
                .size(size)
                .source(s -> s.filter(f -> f.includes(IssueSource.FIELDS)));
        if (after != null) {
            request.searchAfter(after);
        }
        if (highlight) {
            request.highlight(HIGHLIGHT);
        }
        try {
            return elasticsearch.search(request.build(), IssueSource.class);
        } catch (ElasticsearchException e) {
            // a PIT expires 1 minute after its last use: the client has to start the search over
            if (after != null && e.status() == 404) {
                throw new TrakrException(HttpStatus.BAD_REQUEST, "CURSOR_EXPIRED",
                        "These results are too old to continue; run the search again.");
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String openPit() {
        try {
            return elasticsearch.openPointInTime(o -> o.index(index).keepAlive(KEEP_ALIVE)).id();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // the last page releases the PIT now instead of letting it hold resources until it expires
    private void closePit(String pit) {
        try {
            elasticsearch.closePointInTime(c -> c.id(pit));
        } catch (IOException | ElasticsearchException e) {
            log.debug("could not close point in time", e);
        }
    }

    private static SearchHit toHit(Hit<IssueSource> hit) {
        IssueSource s = hit.source();
        if (s == null) {
            throw new IllegalStateException("search hit " + hit.id() + " came back without its source");
        }
        Map<String, List<String>> highlights = hit.highlight() == null ? Map.of() : hit.highlight();
        return new SearchHit(s.id(), s.key(), s.projectKey(), s.summary(), s.type(), s.priority(), s.statusName(),
                s.statusCategory(), s.assigneeId(), s.assigneeName(), s.sprintName(), s.storyPoints(),
                s.labels() == null ? List.of() : s.labels(), s.flagged(), s.dueDate(), s.createdAt(), s.updatedAt(),
                highlights);
    }

    // BLOCK 3: quick search for the top-nav box (05-search §4)
    public Suggestions suggest(UUID userId, String q) {
        String text = q.strip();
        if (text.isEmpty()) {
            return new Suggestions(List.of(), List.of(), List.of());
        }
        Scope scope = security.of(userId);
        // "MAR-13" matches MAR-13, MAR-130…; "check" matches "Fix checkout flow" (search_as_you_type).
        // Every prefix hit scores the same, so the exact key gets its own clause: with ten or more MAR-13x
        // issues it would otherwise be a coin toss whether MAR-13 makes the six shown.
        Query typed = Query.of(q1 -> q1.bool(b -> b
                .should(s -> s.term(t -> t.field("key").value(text).caseInsensitive(true).boost(10f)))
                .should(s -> s.match(m -> m.field("key.prefix").query(text).boost(3f)))
                .should(s -> s.multiMatch(m -> m.query(text).type(TextQueryType.BoolPrefix)
                        .fields("summary.suggest", "summary.suggest._2gram", "summary.suggest._3gram")))
                .minimumShouldMatch("1")));
        List<IssueSuggestion> issues;
        try {
            issues = elasticsearch.search(s -> s.index(index)
                            .query(qq -> qq.bool(b -> b.filter(scope.filter()).must(typed)))
                            .size(6)
                            .source(src -> src.filter(f -> f.includes("key", "summary", "type", "statusCategory"))),
                    IssueSuggestion.class)
                    .hits().hits().stream().map(Hit::source).filter(Objects::nonNull).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Suggestions(issues, queries.projects(scope.projectIds(), text, 3),
                queries.people(scope.workspaceIds(), text, 3));
    }

    // BLOCK 4: autocomplete for the query editor
    public Completions complete(UUID userId, String tql, int caret) {
        // the scope (two queries) only when a value position actually needs it
        Supplier<Scope> scope = new Supplier<>() {
            private @Nullable Scope value;

            @Override
            public Scope get() {
                if (value == null) {
                    value = security.of(userId);
                }
                return value;
            }
        };
        TqlCompleter.Result result = TqlCompleter.complete(tql, caret, field -> valuesFor(field, scope.get()));
        return new Completions(result.from(), result.expected().stream()
                .map(s -> new Completion(CompletionKind.valueOf(s.kind().toUpperCase(Locale.ROOT)), s.text()))
                .toList());
    }

    private List<String> valuesFor(FieldSpec field, Scope scope) {
        return switch (field.kind()) {
            case PROJECT -> queries.projectKeys(scope.projectIds());
            case TYPE -> Arrays.stream(IssueType.values()).map(IssueType::value).toList();
            case PRIORITY -> Arrays.stream(Priority.values()).map(Priority::value).toList();
            case STATUS -> queries.statusNames(scope.projectIds());
            case STATUS_CATEGORY -> List.of("todo", "in_progress", "done");
            case USER -> {
                List<String> people = new ArrayList<>(List.of("currentUser()"));
                queries.people(scope.workspaceIds(), "", 50).forEach(user -> people.add(user.displayName()));
                queries.projectKeys(scope.projectIds()).forEach(key -> people.add("membersOf(\"" + key + "\")"));
                yield people;
            }
            case SPRINT -> {
                List<String> sprints = new ArrayList<>(List.of("openSprints()", "closedSprints()"));
                sprints.addAll(queries.sprintNames(scope.projectIds(), 30));
                yield sprints;
            }
            case ISSUE -> field.name().equals("epic") ? queries.epicKeys(scope.projectIds(), 30) : List.of();
            case LABEL -> labels(scope);
            case DATE -> List.of("startOfDay()", "endOfDay()", "startOfWeek()", "endOfWeek()", "startOfMonth()",
                    "endOfMonth()", "-1d", "-7d", "-30d");
            case BOOLEAN -> List.of("true", "false");
            case KEY, NUMBER, TEXT, SORT_ONLY -> List.of();
        };
    }

    // the labels in use, most used first: a terms aggregation over what the caller can see
    private List<String> labels(Scope scope) {
        try {
            SearchResponse<Void> response = elasticsearch.search(s -> s.index(index).size(0)
                    .query(scope.filter())
                    .aggregations("labels", a -> a.terms(t -> t.field("labels").size(100))), Void.class);
            return response.aggregations().get("labels").sterms().buckets().array().stream()
                    .map(StringTermsBucket::key).map(FieldValue::stringValue).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
