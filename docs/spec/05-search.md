# 05 · Search (Elasticsearch 9 + TQL)

## 1. Roles

- **workers/search-indexer** writes the index from Kafka events.
- **api/search** reads it: parses TQL, builds an ES query, returns hits.
- Postgres stays the source of truth. The index can always be rebuilt (§6).

## 2. Index `trakr-issues-v1` (alias `trakr-issues`)

One document per issue, id = issue id, routing = `project_id` (keeps a project's issues on one shard; queries filtered by project hit one shard).

| Field                                                | ES type                                                                         | Source                                                       |
| ---------------------------------------------------- | ------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| id, workspaceId, projectId                           | keyword                                                                         |                                                              |
| projectKey                                           | keyword                                                                         |                                                              |
| key                                                  | keyword + `key.prefix` (edge n-gram)                                            | `MAR-131`; typing `MAR-13` matches                           |
| number                                               | long                                                                            | for numeric sort                                             |
| type, priority, statusCategory                       | keyword                                                                         |                                                              |
| priorityRank                                         | byte                                                                            | highest=5 … lowest=1, so `ORDER BY priority` sorts correctly |
| statusId                                             | keyword                                                                         |                                                              |
| statusName                                           | keyword + `.text` (text)                                                        |                                                              |
| summary                                              | text (`trakr_text`) + `.raw` keyword (sort) + `.suggest` (`search_as_you_type`) |                                                              |
| descriptionText                                      | text (`trakr_text`)                                                             |                                                              |
| commentsText                                         | text (`trakr_text`)                                                             | last 50 comments concatenated                                |
| labels                                               | keyword                                                                         |                                                              |
| assigneeId, reporterId                               | keyword                                                                         |                                                              |
| assigneeName                                         | keyword + `.text`                                                               |                                                              |
| parentId, epicId, epicName                           | keyword                                                                         |                                                              |
| sprintId, sprintName, sprintState                    | keyword                                                                         |                                                              |
| storyPoints                                          | scaled_float (factor 10)                                                        |                                                              |
| flagged                                              | boolean                                                                         |                                                              |
| watcherIds, mentionedUserIds                         | keyword                                                                         |                                                              |
| createdAt, updatedAt, resolvedAt, dueDate, startDate | date                                                                            |                                                              |
| rank                                                 | keyword                                                                         |                                                              |
| deleted                                              | boolean                                                                         | soft deletes stay searchable for admins only                 |

Analyzer `trakr_text`: standard tokenizer → lowercase → asciifolding → English possessive + light stemmer; search analyzer adds a synonym graph filter (empty file in v1, ready for later).

Index settings (local): 1 shard, 0 replicas, `refresh_interval: 1s`. Prod: 3 shards, 1 replica.

## 3. TQL — TRAKR Query Language

Examples:

```
project = MAR AND status != Done ORDER BY priority DESC, updated DESC
assignee = currentUser() AND sprint in openSprints()
text ~ "checkout crash" AND type in (Bug, Task) AND created >= -7d
labels in (frontend, ux) AND storyPoints > 3 AND epic = MAR-100
priority >= High AND (flagged = true OR due <= endOfWeek())
```

### Grammar (EBNF → ANTLR `Tql.g4`, case-insensitive keywords)

```
query       := orExpr? orderBy? EOF
orExpr      := andExpr ( OR andExpr )*
andExpr     := notExpr ( AND notExpr )*
notExpr     := NOT notExpr | atom
atom        := '(' orExpr ')' | clause
clause      := field op value
             | field (IN | NOT IN) '(' value (',' value)* ')'
             | field IS (NOT)? EMPTY
             | field '~' STRING                       // text contains
op          := '=' | '!=' | '>' | '>=' | '<' | '<='
value       := STRING | IDENT | NUMBER | RELDATE | DATE | ISSUEKEY | function
function    := IDENT '(' (value (',' value)*)? ')'
orderBy     := ORDER BY sortKey (',' sortKey)*
sortKey     := field (ASC | DESC)?
RELDATE     := '-'? [0-9]+ [dwmh]                     // -7d, 2w
ISSUEKEY    := [A-Z]{2,6} '-' [0-9]+
```

### Fields → ES

| TQL field                              | ES field                                                     | Operators                   |
| -------------------------------------- | ------------------------------------------------------------ | --------------------------- |
| project                                | projectKey                                                   | = != in                     |
| key                                    | key                                                          | = != in                     |
| type, priority, status, statusCategory | type / priorityRank / statusName / statusCategory            | = != in; `> <` for priority |
| assignee, reporter                     | assigneeId / reporterId (names resolved to ids by api first) | = != in, is empty           |
| sprint                                 | sprintId / sprintName                                        | = != in, is empty           |
| epic, parent                           | epicId / parentId (keys resolved to ids)                     | = != in                     |
| labels                                 | labels                                                       | = != in, is empty           |
| text                                   | summary^3, descriptionText, commentsText                     | ~                           |
| summary                                | summary                                                      | ~                           |
| storyPoints                            | storyPoints                                                  | all comparisons             |
| created, updated, resolved, due        | createdAt / updatedAt / resolvedAt / dueDate                 | comparisons, relative dates |
| flagged                                | flagged                                                      | =                           |
| watcher                                | watcherIds                                                   | =                           |

Functions: `currentUser()`, `openSprints()`, `closedSprints()`, `startOfDay()`, `endOfWeek()`, `startOfMonth()`, `membersOf("project")`.

### Translation rules

- `AND` → `bool.filter` (or `bool.must` if any child is a text clause, so scoring works), `OR` → `bool.should` + `minimum_should_match: 1`, `NOT` → `bool.must_not`.
- Exact clauses use `term`/`terms`; ranges use `range`; text uses `multi_match` (`type: best_fields`, `operator: and`, `fuzziness: AUTO`) with highlighting on summary/description.
- **Always** add a security filter: `workspaceId = me.workspace AND projectId in (projects I can see)`. The user's TQL is wrapped, never trusted.
- Default sort when no ORDER BY: `_score desc` if a text clause exists, else `updatedAt desc`. Tie-breaker: `id asc`.
- Errors: the ANTLR error listener produces `TQL_SYNTAX_ERROR` with `position` and `expected[]` (used by the editor to underline).

### Pagination

Point-in-time + `search_after`: first page opens a PIT (1 min keep-alive) and returns `nextCursor = base64(pitId + sort values)`. Max `limit` 100.

### Autocomplete (`/search/tql/complete`)

Feed the partial text to the parser with a custom error strategy; collect expected token types at the caret; map them to suggestions (field names, operators, values from ES `terms` aggregations or the project's statuses/people).

## 4. Quick search (`/search/suggest`)

One `_msearch`: issues (`key.prefix` OR `summary.suggest`, size 6), and api-side lookups for projects (trigram on name/key) and people (prefix on display name).

## 5. Indexer behaviour (workers)

- Consumes `issues`, `comments`, `projects`.
- Builds the document from the event's `IssueSnapshot` (+ cached project/sprint/user names from a small Caffeine cache refreshed by `projects` events).
- Bulk API, flushing at 500 actions or 1 s; `version_type=external` with `version = sequence`; a 409 version conflict means "already newer" → ignore.
- Comment events update only `commentsText` with a painless script (or a partial `update` doc).
- Project rename → `update_by_query` on `projectId`.

## 6. Reindexing (zero downtime)

`POST /admin/search/reindex` on workers:

1. Create `trakr-issues-v{n+1}` with the current mapping.
2. Stream all issues from Postgres (jOOQ cursor, fetch size 1000) → bulk into the new index, with external versions.
3. Meanwhile the live consumer writes to **both** indices (dual-write flag).
4. Atomically swap the alias `trakr-issues` → new index; delete the old one after 1 hour.
