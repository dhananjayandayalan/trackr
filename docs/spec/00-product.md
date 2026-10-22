# 00 · Product

## 1. One-line pitch

TRAKR is a fast, keyboard-first issue tracker for software teams: boards, backlogs, sprints, roadmaps, reports and live collaboration.

## 2. Users and roles

| Role             | Scope     | Can                                                                       |
| ---------------- | --------- | ------------------------------------------------------------------------- |
| Workspace owner  | Workspace | Everything, incl. delete workspace, manage billing-free settings          |
| Workspace member | Workspace | Create projects, see projects they belong to or that are `open`/`limited` |
| Project admin    | Project   | Edit project details, workflow, members, archive the project              |
| Project member   | Project   | Create/edit/move/comment on issues, manage sprints                        |
| Project viewer   | Project   | Read everything in the project, comment is **not** allowed                |

A user can belong to several workspaces; the UI works in one "current" workspace at a time (MVP: one workspace per user is fine, schema supports many).

## 3. Feature list by screen (matches the VOLT design canvas)

### 3.1 Sign in / sign up (`/login`)

- Email + password sign in; create account (name, email, password ≥ 10 chars with a number and a symbol).
- Continue with Google / GitHub (OAuth2 / OIDC).
- "Keep me signed in" → 30-day session; otherwise session cookie.
- Forgot password → emails a one-time link (Mailpit locally).

### 3.2 Your work (`/`)

- Stat tiles: assigned to me (open), due this week, waiting on review (assigned issues in a review-category status… see workflow), unread mentions.
- Tabs: Assigned to me · Worked on (I changed it in the last 30 days) · Viewed (last 20 issues I opened) · Starred.
- Recent projects (last 5 visited) with open-issue counts.
- Team activity feed (latest 20 events across my projects).

### 3.3 Projects directory (`/projects`)

- Grid and list views, search by name/key, filter by type and lead, star a project.
- "New project" card.

### 3.4 Create project (`/projects/new`)

- Template: Scrum, Kanban, Bug tracking, Blank — each seeds a workflow and board (see §5).
- Name, key (2–6 uppercase letters, unique in workspace, suggested from the name), lead, access (private / limited / open), description, colour.
- Invite people by email (existing users are added; unknown emails get an invite email).

### 3.5 Sprint board (`/p/:key/board`)

- Columns = board columns mapped to workflow statuses, each with a count and optional WIP limit (over-limit column shows warning state).
- Cards: title, epic tag, type icon, key, story points, priority icon, assignee, flagged badge.
- Drag a card within a column (re-rank) and across columns (transition). Illegal transitions are rejected visually before drop.
- Quick filters: Only my issues, Recently updated (24h), Bugs only; assignee avatar filter; text search within the board; group by none/assignee/epic.
- Complete sprint (scrum projects): choose where unfinished issues go (backlog or next sprint).
- Live: other people's moves animate in within ~1 s.

### 3.6 Backlog (`/p/:key/backlog`)

- Active sprint, planned sprints, and the backlog as collapsible lists; point totals per status category.
- Drag issues between sprints and backlog, re-rank within a list.
- Create sprint, start sprint (name, goal, dates), inline create issue.
- Epics panel with per-epic progress; filter by epic and type.

### 3.7 Issue detail (`/p/:key/issues/:issueKey`, also as a side panel over the board)

- Title, description (collaborative rich text), attachments, subtasks with progress, linked issues (blocks / is blocked by / relates to / duplicates).
- Status button with allowed transitions only.
- Details: assignee, reporter, priority, sprint, story points, epic, labels, due date, fix version (free text in MVP).
- Activity tabs: Comments (with @mentions), History, Work log.
- Watch / unwatch, copy link, time tracking (estimate, logged, remaining), development panel (MVP: static placeholder fed by a later integration — out of scope §7).

### 3.8 Create issue (dialog, `c` shortcut)

- Project, parent epic, type, summary (required), description, assignee (Assign to me), priority, sprint, story points, due date, labels, attachments.
- "Create another" keeps the dialog open with project/type remembered.

### 3.9 Timeline (`/p/:key/timeline`)

- Epics as bars over weeks/months, expandable to child issues; drag bar ends to change start/due dates; today marker.

### 3.10 Reports (`/p/:key/reports`)

- Burndown (remaining points per working day vs ideal; scope changes marked).
- Velocity (committed vs completed for the last 5 closed sprints).
- Sprint report (completed / not completed / added mid-sprint).
- Status breakdown and workload per assignee.

### 3.11 Issues and filters (`/issues`)

- Default filters (My open issues, Reported by me, All, Open, Done, Created recently) and starred saved filters.
- Basic mode (pill filters) and Query mode (TQL text with autocomplete).
- Results table: sortable, selectable rows, bulk actions (transition, assign, move to sprint, delete), cursor pagination, CSV export.

### 3.12 Project settings (`/p/:key/settings/*`)

- Details, People (roles), Workflow editor (statuses, categories, transitions, WIP limits), Issue types (enable/disable), Notifications (per-event email toggles), Permissions (read-only matrix in MVP).

### 3.13 Global

- Top nav: Your work, Projects, Issues, Dashboards (= reports), Create, search box (`/` to focus), notifications (unread count), help, settings, profile.
- Command palette (`Ctrl/Cmd+K`): jump to issue by key, run transitions, navigate.
- Keyboard: `c` create, `/` search, `g b` board, `g k` backlog, `i` assign to me, `m` comment, `f` flag.
- Notifications: in-app (bell) + email digest; triggers: assigned to you, mentioned, comment on watched issue, status change on watched issue.

## 4. Business rules

1. **Issue key** = `<PROJECT_KEY>-<n>`, `n` increments per project, never reused, assigned in the same transaction as the insert.
2. **Issue types:** story, bug, task, epic, subtask. A subtask must have a non-subtask parent in the same project. An epic cannot have a parent. Stories/bugs/tasks may have an epic parent.
3. **Workflow:** each project has statuses with a category (`todo`, `in_progress`, `done`). Transitions are explicit pairs; a transition with `from = null` means "from any status". Moving into a `done`-category status sets `resolved_at`; moving out clears it.
4. **WIP limits** are advisory: a move that exceeds the limit is allowed but the column is shown in warning state and the move response includes `warnings: ["WIP_LIMIT_EXCEEDED"]`.
5. **Sprints:** at most one `active` sprint per project. Starting requires name and dates. Completing moves open issues to the backlog or a chosen planned sprint; it records the sprint report snapshot.
6. **Ranking:** order inside any list (column, sprint, backlog) is by `rank` (fractional index string). Moving an issue writes only that issue's rank.
7. **Story points:** decimal with one fraction digit, 0–100.
8. **Deleting** an issue is a soft delete (`deleted_at`) for 30 days; subtasks are deleted with the parent.
9. **Mentions** `@display-name` in comments resolve to user ids at save time and notify those users.
10. **Concurrency:** two people editing the same field → the second save gets HTTP 412 and the UI offers "reload and re-apply". The description is the exception: it merges live via CRDT.

## 5. Templates

| Template     | Statuses (category)                                                                           | Board columns                 | Sprints |
| ------------ | --------------------------------------------------------------------------------------------- | ----------------------------- | ------- |
| Scrum        | To do (todo), In progress (in_progress), In review (in_progress), Done (done)                 | one per status                | yes     |
| Kanban       | Backlog (todo), Selected (todo), In progress (in_progress, WIP 4), Done (done)                | Selected · In progress · Done | no      |
| Bug tracking | Triage (todo), Confirmed (todo), Fixing (in_progress), Verifying (in_progress), Closed (done) | one per status except Triage  | no      |
| Blank        | To do, In progress, Done                                                                      | one per status                | no      |

Transitions for all templates: every status → every other status (editable later).

## 6. Glossary

| Term              | Meaning                                                                               |
| ----------------- | ------------------------------------------------------------------------------------- |
| Workspace         | Tenant. All data belongs to exactly one workspace.                                    |
| Project           | Container of issues with a key prefix, workflow and board.                            |
| Issue             | The unit of work (story, bug, task, epic, subtask).                                   |
| Status / category | A workflow state and its bucket (todo / in progress / done).                          |
| Transition        | An allowed move between two statuses.                                                 |
| Rank              | Sortable string that orders issues inside a list.                                     |
| Sprint            | Time-boxed list of issues (scrum projects).                                           |
| TQL               | TRAKR Query Language, e.g. `project = MAR AND status != Done ORDER BY priority DESC`. |
| Read model        | A copy of data shaped for reading (Elasticsearch index, report tables).               |
| Outbox            | Table where events are stored in the same transaction as the change, then published.  |

## 7. Non-goals for v1

Git/CI integrations (the "Development" panel stays a placeholder), custom issue types, automation rules, mobile apps, billing, SAML/SCIM, public REST API tokens, dark mode (tokens are ready for it), i18n beyond English.

## 8. Quality targets (checked in phase 18)

| Metric                                     | Target                              |
| ------------------------------------------ | ----------------------------------- |
| Board API (`GET /board`) p95               | < 150 ms with 500 issues            |
| Move issue API p95                         | < 80 ms                             |
| Search API p95                             | < 200 ms with 100k issues           |
| Live update latency (move → other browser) | < 1 s p95                           |
| Largest Contentful Paint (board, cold)     | < 1.5 s on a mid laptop             |
| Interaction to Next Paint                  | < 100 ms                            |
| JS shipped on first load                   | < 180 KB gzip                       |
| Accessibility                              | No axe violations on the 12 screens |
