package dev.trakr.api.search.web;

import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.search.SearchService;
import dev.trakr.api.search.SearchService.Export;
import dev.trakr.api.search.SearchViews.Completions;
import dev.trakr.api.search.SearchViews.SearchPage;
import dev.trakr.api.search.SearchViews.Suggestions;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 03-api §3.8: TQL search, export, autocomplete, quick search. */
@RestController
@RequestMapping("/api/v1/search")
class SearchController {

    private static final int DEFAULT_LIMIT = 50;
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    /** {@code tql} absent or blank = everything the caller can see; at most 2000 characters. */
    record SearchRequest(@Nullable @Size(max = 2000) String tql, @Nullable Integer limit, @Nullable String cursor) {

        String query() {
            return tql == null ? "" : tql;
        }
    }

    @Operation(operationId = "searchIssues")
    @PostMapping
    SearchPage search(@AuthenticationPrincipal TrakrPrincipal principal, @Valid @RequestBody SearchRequest request) {
        return search.search(principal.id(), request.query(), request.limit() == null ? DEFAULT_LIMIT : request.limit(),
                request.cursor());
    }

    /**
     * Streams the CSV: the query is parsed and checked first (a typo is still a 400 problem), then the rows
     * are written page by page while the client downloads — the file never sits in memory whole.
     */
    @Operation(operationId = "exportIssues")
    @PostMapping(value = "/export", produces = "text/csv")
    ResponseEntity<StreamingResponseBody> export(@AuthenticationPrincipal TrakrPrincipal principal,
            @Valid @RequestBody SearchRequest request) {
        Export export = search.export(principal.id(), request.query());
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("issues.csv").build().toString())
                .body(export::writeTo);
    }

    @Operation(operationId = "suggest")
    @GetMapping("/suggest")
    Suggestions suggest(@AuthenticationPrincipal TrakrPrincipal principal, @RequestParam(defaultValue = "") String q) {
        return search.suggest(principal.id(), q);
    }

    /** {@code cursor}: the caret's offset in {@code tql}; the end of the text when absent. */
    @Operation(operationId = "completeTql")
    @GetMapping("/tql/complete")
    Completions complete(@AuthenticationPrincipal TrakrPrincipal principal, @RequestParam(defaultValue = "") String tql,
            @RequestParam(required = false) @Nullable Integer cursor) {
        return search.complete(principal.id(), tql, cursor == null ? tql.length() : cursor);
    }
}
