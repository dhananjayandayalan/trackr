# Phase 11 · Search and TQL (ANTLR → Elasticsearch)

**Goal:** `POST /api/v1/search` accepts TQL, returns highlighted, paginated hits from Elasticsearch, with precise syntax errors, autocomplete and saved filters.
**Spec:** 05-search §3–4, 03-api §3.8. **Time:** 4–5 days.

## Concepts you'll learn

- **Lexer → parser → parse tree → AST → target query** (a tiny compiler).
- **ANTLR grammars**, precedence via rule layering, case-insensitive keywords, error listeners.
- **Visitor pattern** to turn a parse tree into your own immutable AST (records + sealed interfaces).
- **Elasticsearch bool queries**, filter vs must (scoring), `multi_match`, highlighting.
- **Point-in-time + `search_after`** — stable deep pagination.
- **Defence in depth** — user input wrapped by a security filter the user can't influence.

## Files in this phase

1. api build: apply the Gradle `antlr` plugin; `antlr(libs.antlr.tool)`; generate into package `dev.trakr.api.search.internal.tql.grammar`; ES Java client dependency
2. `apps/api/src/main/antlr/dev/trakr/api/search/internal/tql/grammar/Tql.g4`
3. `search/internal/tql/ast/*.java` — `sealed interface Expr permits And, Or, Not, Compare, In, IsEmpty, TextMatch`; `record OrderBy(...)`; `record TqlQuery(Expr where, List<OrderBy> orderBy)`
4. `search/internal/tql/TqlParser.java` (wraps ANTLR, throws `TqlSyntaxException(position, expected)`)
5. `search/internal/tql/AstBuilder.java` (visitor)
6. `search/internal/tql/FieldRegistry.java` (TQL field → ES field, allowed operators, value type)
7. `search/internal/tql/ValueResolver.java` (names/keys → ids, functions like `currentUser()`, relative dates)
8. `search/internal/tql/EsQueryCompiler.java` (AST → `co.elastic.clients.elasticsearch._types.query_dsl.Query`)
9. `search/internal/SecurityScope.java` (workspace + visible projects filter)
10. `search/SearchService.java` (search, suggest, complete, export)
11. `search/internal/CursorCodec.java` (PIT id + sort values ↔ base64url)
12. `search/web/SearchController.java`, `search/web/FilterController.java`; `V010` (phase 10) already has `saved_filter` — add `search/internal/SavedFilter.java` + repository
13. Tests

### File 2 — `Tql.g4`

| #   | Cue                                    | What                                                                                                                                                                                      |
| --- | -------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| 1   | `// BLOCK 1: grammar header + options` | `grammar Tql;` and `options { caseInsensitive = true; }` (ANTLR 4.10+ feature — check your version)                                                                                       |
| 2   | `// BLOCK 2: entry rule`               | `query : orExpr? orderBy? EOF ;`                                                                                                                                                          |
| 3   | `// BLOCK 3: boolean precedence`       | `orExpr : andExpr (OR andExpr)_ ; andExpr : notExpr (AND notExpr)_ ; notExpr : NOT notExpr                                                                                                | atom ;` — **explain why layering gives AND higher precedence than OR** |
| 4   | `// BLOCK 4: clauses`                  | the `clause` alternatives from 05-search §3, each with a `# label` so the visitor gets one method per alternative                                                                         |
| 5   | `// BLOCK 5: values and functions`     |                                                                                                                                                                                           |
| 6   | `// BLOCK 6: order by`                 |                                                                                                                                                                                           |
| 7   | `// BLOCK 7: lexer rules`              | keywords first (AND, OR, NOT, IN, IS, EMPTY, ORDER, BY, ASC, DESC), then `ISSUEKEY`, `RELDATE`, `DATE`, `NUMBER`, `STRING` (double-quoted with escapes), `IDENT`, operators, `WS -> skip` |

**Watch out:** lexer rule **order** decides ties: `MAR-131` must lex as `ISSUEKEY`, not `IDENT` `-` `NUMBER`. Write a lexer test for it first.

### File 4 — `TqlParser`

| #   | What                                                                                                                                                                         |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Build `CharStreams.fromString` → lexer → `CommonTokenStream` → parser                                                                                                        |
| 2   | Remove default console error listeners; add one that throws `TqlSyntaxException` with `offendingToken.getStartIndex()` and `parser.getExpectedTokens().toString(vocabulary)` |
| 3   | Use `BailErrorStrategy` for normal parsing (fast fail)                                                                                                                       |
| 4   | `parse(String)` → `TqlQuery` via `AstBuilder`                                                                                                                                |

### File 8 — `EsQueryCompiler`

| #   | Cue                         | What                                                                                                                                                       |
| --- | --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: dispatch`      | `switch (expr)` with record patterns: `case And(var l, var r) ->` … (Java 21+ pattern matching — the sealed AST makes the switch exhaustive, no `default`) |
| 2   | `// BLOCK 2: boolean nodes` | flatten nested ANDs/ORs into one `bool`                                                                                                                    |
| 3   | `// BLOCK 3: comparisons`   | `term`, `terms`, `range` (dates and numbers), priority via `priorityRank`                                                                                  |
| 4   | `// BLOCK 4: text`          | `multi_match` over `summary^3, descriptionText, commentsText`, `fuzziness("AUTO")`, `operator(And)`                                                        |
| 5   | `// BLOCK 5: is empty`      | `bool.mustNot(exists(field))`                                                                                                                              |
| 6   | `// BLOCK 6: sorting`       | map ORDER BY to `SortOptions`, add tie-breaker `id`                                                                                                        |

### File 10 — `SearchService.search`

1. parse → resolve values → compile → wrap: `bool.filter(securityScope.filter(user)).must(compiled)`;
2. first page: open PIT (`keep_alive 1m`); next pages: decode cursor;
3. `search(s -> s.pit(...).query(...).sort(...).searchAfter(...).size(limit).highlight(...).source(includes IssueRow fields))`;
4. map hits to `SearchHit` records; `nextCursor` if `hits.size() == limit`.
   **Ask yourself:** why is `from/size` paging wrong for page 500, and what does PIT protect against when documents change between pages?

### `complete` (autocomplete)

Parse the text up to the caret with a recording error strategy; collect expected token types; map: IDENT expected after nothing → field names; after a field → operators; after an operator → values for that field (statuses/people from the project, labels from an ES `terms` aggregation, `currentUser()` for people fields).

### `export`

`StreamingResponseBody` writing CSV rows as you page through PIT results — never builds the whole file in memory. Header `Content-Disposition: attachment; filename="issues.csv"`.

## Tests

- `TqlLexerTest`, `TqlParserTest` — table of inputs → AST `toString()`; precedence (`a OR b AND c`), NOT, parentheses, IN lists, relative dates, error positions for broken input.
- `EsQueryCompilerTest` — compile and compare JSON (serialize the `Query` with the client's JSON mapper) against expected JSON files in `src/test/resources/tql/`.
- `SearchApiIT` (Postgres + Kafka + ES + workers' indexer running in-process or a pre-indexed fixture): queries from 05-search §3 return the expected keys; users never see issues from projects they can't access even with `project = OTHER`.
- `CursorCodecTest`.

## Done when

- `project = MAR AND text ~ "checkout" ORDER BY priority DESC` returns highlighted results in < 200 ms.
- A typo returns a 400 with the exact column of the mistake.
- You can explain the pipeline from string to ES JSON without notes.

Commit: `phase-11: tql search`.
