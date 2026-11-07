-- BLOCK 1: issue — the central table (02-data-model §3)
CREATE TABLE issue (
  id uuid NOT NULL,
  -- denormalised from project: row-level security (phase 18) and search routing filter on it without a join
  workspace_id uuid NOT NULL,
  project_id uuid NOT NULL,
  -- MAR-131 → 131; allocated from project.issue_seq in the same transaction as this INSERT
  number bigint NOT NULL,
  -- stored, not computed: a lookup by key is one index hit
  key text NOT NULL,
  type text NOT NULL,
  summary text NOT NULL,
  description_html text,
  description_text text,
  status_id uuid NOT NULL,
  -- denormalised copy of the status's category: boards and reports filter on it without joining workflow_status
  status_category text NOT NULL,
  priority text NOT NULL DEFAULT 'medium',
  assignee_id uuid,
  reporter_id uuid NOT NULL,
  -- an epic (for stories, bugs, tasks) or the issue a subtask belongs to
  parent_id uuid,
  -- null = in the backlog
  sprint_id uuid,
  story_points numeric(4, 1),
  -- byte-wise order: "B" < "a" under COLLATE "C", but "a" < "B" under a language collation (see Rank.java)
  rank text COLLATE "C" NOT NULL,
  labels text[] NOT NULL DEFAULT '{}',
  due_date date,
  start_date date,
  flagged boolean NOT NULL DEFAULT false,
  original_estimate_min int,
  time_spent_min int NOT NULL DEFAULT 0,
  fix_version text,
  custom_fields jsonb NOT NULL DEFAULT '{}',
  resolved_at timestamptz,
  -- soft delete: kept 30 days (00-product §4.8)
  deleted_at timestamptz,
  -- optimistic lock, and the sequence number of the issue's events
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_issue PRIMARY KEY (id),
  CONSTRAINT fk_issue_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspace (id),
  CONSTRAINT fk_issue_project_id FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT fk_issue_status_id FOREIGN KEY (status_id) REFERENCES workflow_status (id),
  CONSTRAINT fk_issue_assignee_id FOREIGN KEY (assignee_id) REFERENCES app_user (id),
  CONSTRAINT fk_issue_reporter_id FOREIGN KEY (reporter_id) REFERENCES app_user (id),
  CONSTRAINT fk_issue_parent_id FOREIGN KEY (parent_id) REFERENCES issue (id),
  CONSTRAINT fk_issue_sprint_id FOREIGN KEY (sprint_id) REFERENCES sprint (id),
  -- BLOCK 2: CHECK constraints — the last line of defence behind Issue.java's rules
  CONSTRAINT ck_issue_type CHECK (type IN ('story', 'bug', 'task', 'epic', 'subtask')),
  CONSTRAINT ck_issue_priority CHECK (priority IN ('highest', 'high', 'medium', 'low', 'lowest')),
  CONSTRAINT ck_issue_status_category CHECK (status_category IN ('todo', 'in_progress', 'done')),
  CONSTRAINT ck_issue_summary CHECK (char_length(summary) BETWEEN 1 AND 255),
  CONSTRAINT ck_issue_number CHECK (number > 0),
  CONSTRAINT ck_issue_subtask_has_parent CHECK (type <> 'subtask' OR parent_id IS NOT NULL),
  CONSTRAINT ck_issue_epic_has_no_parent CHECK (type <> 'epic' OR parent_id IS NULL),
  CONSTRAINT ck_issue_story_points CHECK (story_points BETWEEN 0 AND 100),
  CONSTRAINT ck_issue_original_estimate_min CHECK (original_estimate_min >= 0),
  CONSTRAINT ck_issue_time_spent_min CHECK (time_spent_min >= 0),
  -- BLOCK 3: unique constraints
  -- numbers are never reused within a project (00-product §4.1)
  CONSTRAINT uq_issue_project_id_number UNIQUE (project_id, number)
  -- [jooq ignore start]
  -- GET /issues/MAR-131: the key lookup, scoped to the caller's workspace
  , CONSTRAINT uq_issue_workspace_id_key UNIQUE (workspace_id, key)
  -- [jooq ignore stop]
);

-- BLOCK 4: indexes, each serving a named query.
-- Postgres-only syntax (INCLUDE, partial WHERE, GIN, operator classes) is invisible to jOOQ's H2 codegen.

-- [jooq ignore start]
-- board columns and backlog lists: "issues of project P in sprint S with status X, in rank order".
-- INCLUDE puts the card fields in the index, so those screens are index-only scans that never touch the table.
CREATE INDEX ix_issue_board ON issue (project_id, sprint_id, status_id, rank)
  INCLUDE (key, summary, type, priority, assignee_id, story_points, parent_id, flagged, version)
  WHERE deleted_at IS NULL;

-- "Assigned to me" on the home screen: my open issues, most recently updated first
CREATE INDEX ix_issue_assignee_open ON issue (assignee_id, updated_at DESC)
  WHERE status_category <> 'done' AND deleted_at IS NULL;

-- label filters: labels @> '{frontend}' — a GIN index looks up array elements, a B-tree can't
CREATE INDEX ix_issue_labels ON issue USING gin (labels);

-- the command palette: summary ILIKE '%login%' straight from Postgres (pg_trgm), no Elasticsearch round trip
CREATE INDEX ix_issue_summary_trgm ON issue USING gin (summary gin_trgm_ops);
-- [jooq ignore stop]

-- subtasks of an issue and an epic's children (issue detail, epic progress)
CREATE INDEX ix_issue_parent ON issue (parent_id);
