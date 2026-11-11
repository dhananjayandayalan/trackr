# 02 · Data model (PostgreSQL 18)

Database `trakr`, schema `public`. Every table is written in a Flyway migration under `backend/apps/api/src/main/resources/db/migration/` (the api app owns the schema; workers and collab only touch the tables listed for them in §6).

Conventions: see `.github/instructions/sql-migrations.instructions.md`. Types: `uuid` ids (UUIDv7 from the app), `timestamptz`, `text` + `CHECK` for enums, `citext` for emails, `text COLLATE "C"` for ranks.

## 1. Entity map

```
workspace ─┬─< workspace_member >── app_user ──< user_identity
           │                          │  └──< password_reset
           └─< project ─┬─< project_member >── app_user
                        ├─< workflow_status ──< workflow_transition
                        ├─< board ──< board_column
                        ├─< sprint
                        └─< issue ─┬─< issue_event       (history, append-only)
                                   ├─< comment ──< comment_mention
                                   ├─< attachment
                                   ├─< issue_link (self-relation)
                                   ├─< watcher, worklog, issue_view, issue_star
                                   ├─< collab_update, collab_snapshot
                                   └── parent_id → issue (epic / subtask)
read models: notification, sprint_day_snapshot, sprint_report, processed_event
infra:       event_publication (Spring Modulith outbox), flyway_schema_history
```

## 2. Migration order

| File                             | Contents                                                                        | Written in phase |
| -------------------------------- | ------------------------------------------------------------------------------- | ---------------- |
| `V001__extensions.sql`           | `citext`, `pg_trgm`                                                             | 04               |
| `V002__workspace_and_users.sql`  | workspace, app_user, user_identity, workspace_member, password_reset            | 04               |
| `V003__projects.sql`             | project, project_member, project_star, project_visit                            | 06               |
| `V004__workflow.sql`             | workflow_status, workflow_transition                                            | 06               |
| `V005__boards.sql`               | board, board_column                                                             | 06               |
| `V006__sprints.sql`              | sprint                                                                          | 07               |
| `V007__issues.sql`               | issue + indexes                                                                 | 07               |
| `V008__issue_children.sql`       | issue_event, issue_link, watcher, worklog, issue_view, issue_star               | 07               |
| `V008_1__sprint_commitment.sql`  | sprint: started_at, committed_points, committed_issue_ids                       | 08               |
| `V008_2__board_index_with_id.sql` | ix_issue_board rebuilt with `id` in INCLUDE                                    | 08               |
| `V009__event_publication.sql`    | Spring Modulith event publication table                                         | 09               |
| `V010__read_models.sql`          | notification, sprint_day_snapshot, sprint_report, processed_event, saved_filter | 10               |
| `V011__comments_attachments.sql` | comment, comment_mention, attachment                                            | 15               |
| `V012__collab.sql`               | collab_update, collab_snapshot                                                  | 16               |
| `V013__workflow_version.sql`     | workflow (one row per project: the workflow editor's optimistic-lock version)   | 17               |
| `V014__row_level_security.sql`   | RLS policies                                                                    | 18               |

Version numbers follow the phase order on purpose: Flyway refuses a version lower than one already applied (unless `outOfOrder` is enabled, which we don't — learn why).

## 3. Core tables

### workspace

| Column     | Type        | Rules / why                                         |
| ---------- | ----------- | --------------------------------------------------- |
| id         | uuid        | PK                                                  |
| slug       | text        | unique, `^[a-z0-9-]{3,40}$` — appears in URLs later |
| name       | text        | not null                                            |
| created_at | timestamptz | default now()                                       |

### app_user

| Column            | Type        | Rules / why                                                                     |
| ----------------- | ----------- | ------------------------------------------------------------------------------- |
| id                | uuid        | PK                                                                              |
| email             | citext      | unique — case-insensitive match without `lower()` everywhere                    |
| display_name      | text        | not null                                                                        |
| initials          | text        | 1–3 chars, derived at signup, shown in avatars                                  |
| avatar_color      | text        | one of the VOLT avatar tokens (`pink`,`cyan`,`lime`,`yellow`,`orange`,`violet`) |
| password_hash     | text        | nullable (OAuth-only users); Argon2id encoded string                            |
| email_verified_at | timestamptz | nullable                                                                        |
| created_at        | timestamptz |                                                                                 |

### user_identity (OAuth links)

id uuid PK · user_id → app_user · provider text CHECK (`google`,`github`) · subject text (provider user id) · created_at. **Unique (provider, subject).**

### workspace_member

workspace_id → workspace · user_id → app_user · role text CHECK (`owner`,`member`) · joined_at. **PK (workspace_id, user_id).**

### password_reset

token_hash text PK (SHA-256 of the emailed token — never store the token) · user_id · expires_at · used_at.

### project

| Column                 | Type        | Rules / why                                                               |
| ---------------------- | ----------- | ------------------------------------------------------------------------- |
| id                     | uuid        | PK                                                                        |
| workspace_id           | uuid        | FK                                                                        |
| key                    | text        | `^[A-Z]{2,6}$`; **unique (workspace_id, key)**                            |
| name                   | text        | 1–80 chars                                                                |
| description            | text        | nullable                                                                  |
| template               | text        | `scrum`,`kanban`,`bugs`,`blank`                                           |
| lead_id                | uuid        | FK app_user                                                               |
| access                 | text        | `private`,`limited`,`open`                                                |
| color                  | text        | VOLT pop colour token name                                                |
| issue_seq              | bigint      | default 0 — last issue number used; incremented with `UPDATE … RETURNING` |
| archived_at            | timestamptz | nullable                                                                  |
| version                | bigint      | optimistic lock                                                           |
| created_at, updated_at | timestamptz |                                                                           |

### project_member

project_id · user_id · role (`admin`,`member`,`viewer`) · added_at. PK (project_id, user_id).

### project_star / project_visit

project_star: (user_id, project_id) PK, starred_at. project_visit: (user_id, project_id) PK, visited_at — upserted on each project open; powers "Recent projects".

### workflow_status

id · project_id · name (1–30, **unique per project**) · category (`todo`,`in_progress`,`done`) · position int · color token · wip_limit int nullable.

### workflow_transition

id · project_id · from_status_id nullable (null = any) · to_status_id. Unique (project_id, from_status_id, to_status_id) — use `NULLS NOT DISTINCT` so only one "any" row per target.

### workflow (phase 17)

project_id PK/FK · version bigint. The ETag of `GET/PUT /projects/{key}/workflow`: a save replaces many status and transition rows, so the lock can't live on one of them, and reusing `project.version` would make a project rename fail someone's workflow edit with 412. No row = version 0 (projects created before V013, and the dev seed, which runs after it); the first save upserts it.

### board / board_column

board: id · project_id (unique — one board per project in v1) · name · type (`scrum`,`kanban`).
board_column: id · board_id · name · position · status_ids uuid[] (statuses shown in this column) · wip_limit int nullable.

### sprint

| Column             | Type        | Rules / why                 |
| ------------------ | ----------- | --------------------------- |
| id                 | uuid        |                             |
| project_id         | uuid        |                             |
| name               | text        |                             |
| goal               | text        | nullable                    |
| state              | text        | `planned`,`active`,`closed` |
| starts_on, ends_on | date        | required when active        |
| position           | int         | order of planned sprints    |
| completed_at       | timestamptz |                             |
| version            | bigint      |                             |

**Partial unique index:** `uq_sprint_one_active ON sprint(project_id) WHERE state = 'active'` — the database enforces rule 00-product §4.5.

### issue (the central table)

| Column                                 | Type             | Rules / why                                                                               |
| -------------------------------------- | ---------------- | ----------------------------------------------------------------------------------------- |
| id                                     | uuid             | PK                                                                                        |
| workspace_id                           | uuid             | denormalised for RLS + ES routing                                                         |
| project_id                             | uuid             |                                                                                           |
| number                                 | bigint           | unique (project_id, number)                                                               |
| key                                    | text             | `PROJECT-number`, unique (workspace_id, key) — stored so lookups by key are one index hit |
| type                                   | text             | `story`,`bug`,`task`,`epic`,`subtask`                                                     |
| summary                                | text             | 1–255                                                                                     |
| description_html                       | text             | sanitised HTML snapshot from collab                                                       |
| description_text                       | text             | plain text for search                                                                     |
| status_id                              | uuid             | FK workflow_status                                                                        |
| status_category                        | text             | denormalised copy of the status category — avoids a join on every board/report query      |
| priority                               | text             | `highest`,`high`,`medium`,`low`,`lowest`; default `medium`                                |
| assignee_id                            | uuid             | nullable                                                                                  |
| reporter_id                            | uuid             | not null                                                                                  |
| parent_id                              | uuid             | nullable, FK issue — epic or subtask parent                                               |
| sprint_id                              | uuid             | nullable → backlog                                                                        |
| story_points                           | numeric(4,1)     | 0–100, nullable                                                                           |
| rank                                   | text COLLATE "C" | not null                                                                                  |
| labels                                 | text[]           | default `{}`, GIN index                                                                   |
| due_date                               | date             |                                                                                           |
| start_date                             | date             | timeline bars                                                                             |
| flagged                                | boolean          | default false                                                                             |
| original_estimate_min / time_spent_min | int              | time tracking                                                                             |
| fix_version                            | text             |                                                                                           |
| custom_fields                          | jsonb            | default `{}` (future-proofing; GIN `jsonb_path_ops`)                                      |
| resolved_at                            | timestamptz      | set when entering a `done` status                                                         |
| deleted_at                             | timestamptz      | soft delete                                                                               |
| version                                | bigint           | optimistic lock; also the event `sequence`                                                |
| created_at, updated_at                 | timestamptz      |                                                                                           |

Indexes (each serves a named query):

- `ix_issue_board` on `(project_id, sprint_id, status_id, rank) INCLUDE (key, summary, type, priority, assignee_id, story_points, parent_id, flagged, version) WHERE deleted_at IS NULL` → board and backlog projections (index-only scans).
- `ix_issue_assignee_open` on `(assignee_id, updated_at DESC) WHERE status_category <> 'done' AND deleted_at IS NULL` → "Assigned to me".
- `ix_issue_parent` on `(parent_id)` → subtasks, epic progress.
- `ix_issue_labels` GIN on `labels`.
- `ix_issue_summary_trgm` GIN on `summary gin_trgm_ops` → quick key/title lookup in the command palette (no ES round trip).

CHECK constraints: subtask must have parent (`type <> 'subtask' OR parent_id IS NOT NULL`); epic cannot have parent.

### issue_event (history, append-only)

id bigint identity PK · issue_id · actor_id · occurred_at · kind (`created`,`field_changed`,`transitioned`,`moved`,`commented`,`linked`,`logged_work`,`deleted`) · field text nullable · old_value jsonb · new_value jsonb. Index `(issue_id, id DESC)`. Powers the History tab, "Worked on", activity feed.

### issue_link

id · source_issue_id · target_issue_id · type (`blocks`,`relates`,`duplicates`) · created_by · created_at. Unique (source, target, type). "is blocked by" is the reverse read of `blocks`.

### watcher / worklog / issue_view / issue_star

- watcher (issue_id, user_id) PK.
- worklog: id · issue_id · user_id · started_at · minutes (>0) · note.
- issue_view (user_id, issue_id) PK, viewed_at — upsert on open; keep last 50 per user (trim job).
- issue_star (user_id, issue_id) PK.

### comment / comment_mention

comment: id · issue_id · author_id · body_html (sanitised) · body_text · created_at · edited_at · deleted_at.
comment_mention: (comment_id, user_id) PK.

### attachment

id · issue_id · uploader_id · object_key (unique; `ws/{wsId}/issues/{issueId}/{uuid}/{fileName}`) · file_name · content_type · size_bytes (≤ 25 MB) · created_at · deleted_at.

## 4. Read-model and infrastructure tables

- **event_publication** — created exactly as Spring Modulith's JDBC/JPA schema for the version in use (copy from the Modulith reference docs for that version, do not invent columns). This is the outbox.
- **processed_event** (consumer_group text, event_id uuid, processed_at) PK (consumer_group, event_id) — idempotency for workers; rows older than 7 days purged daily.
- **notification** — id · user_id · kind (`assigned`,`mentioned`,`commented`,`status_changed`) · issue_id · actor_id · payload jsonb · created_at · read_at. Index `(user_id, created_at DESC)` and partial `(user_id) WHERE read_at IS NULL`.
- **sprint_day_snapshot** — (sprint_id, day) PK · scope_points · completed_points · remaining_points · issue_count. Upserted by the report projector; drives burndown.
- **sprint_report** — sprint_id PK · committed_points · completed_points · added_points · removed_points · completed_issue_ids uuid[] · incomplete_issue_ids uuid[] · closed_at. Written on SprintCompleted; drives velocity.
- **saved_filter** — id · workspace_id · owner_id · name · tql · starred bool · shared bool · created_at.

## 5. Collaboration tables

- **collab_update** — id bigint identity PK · issue_id · update bytea (one Yjs update) · client_id bigint · created_at. Index `(issue_id, id)`.
- **collab_snapshot** — issue_id PK · state bytea (`Y.encodeStateAsUpdate`) · upto_update_id bigint · updated_at.
  On snapshot: insert/replace snapshot, then `DELETE FROM collab_update WHERE issue_id = ? AND id <= upto_update_id`.

## 6. Who may write what

| Table group                                                       | api   | workers   | collab                              |
| ----------------------------------------------------------------- | ----- | --------- | ----------------------------------- |
| Everything in §3                                                  | write | read only | read `issue`, `project_member` only |
| event_publication                                                 | write | —         | —                                   |
| notification, sprint_day_snapshot, sprint_report, processed_event | read  | **write** | —                                   |
| collab_update, collab_snapshot                                    | read  | —         | **write**                           |

Use separate database roles (`trakr_api`, `trakr_workers`, `trakr_collab`) with grants matching this table (phase 18).

## 7. Row-level security (phase 18)

Each tenant table gets `ALTER TABLE … ENABLE ROW LEVEL SECURITY` and a policy `USING (workspace_id = current_setting('app.workspace_id')::uuid)`. The api sets `SET LOCAL app.workspace_id = ?` at the start of every transaction (a Hibernate `StatementInspector` is wrong for this — use a transaction-bound hook, see 09-security §6). RLS is a safety net; authorization is still checked in services.
