-- Read models written by the workers app (02-data-model §4, §6); the api reads them. The api still owns every
-- migration, so all schema history lives in one place.

-- BLOCK 1: notification — one row per person told about something
CREATE TABLE notification (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  kind text NOT NULL,
  issue_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  -- what the notification shows (issue key, summary, status names…): rendering needs no joins
  payload jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(),
  read_at timestamptz,
  CONSTRAINT pk_notification PRIMARY KEY (id),
  CONSTRAINT fk_notification_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT fk_notification_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id),
  CONSTRAINT fk_notification_actor_id FOREIGN KEY (actor_id) REFERENCES app_user (id),
  CONSTRAINT ck_notification_kind CHECK (kind IN ('assigned', 'mentioned', 'commented', 'status_changed'))
);

-- the notification panel: my latest notifications, newest first
CREATE INDEX ix_notification_user_id_created_at ON notification (user_id, created_at DESC);

-- [jooq ignore start]
-- the unread badge: count(*) of my unread ones, from a small index of only the unread rows
CREATE INDEX ix_notification_unread ON notification (user_id) WHERE read_at IS NULL;
-- [jooq ignore stop]

-- BLOCK 2: sprint_day_snapshot — one row per sprint and day, drives the burndown chart
CREATE TABLE sprint_day_snapshot (
  sprint_id uuid NOT NULL,
  day date NOT NULL,
  scope_points numeric(7, 1) NOT NULL,
  completed_points numeric(7, 1) NOT NULL,
  remaining_points numeric(7, 1) NOT NULL,
  issue_count int NOT NULL,
  -- also the upsert target of the report projector: INSERT … ON CONFLICT (sprint_id, day) DO UPDATE
  CONSTRAINT pk_sprint_day_snapshot PRIMARY KEY (sprint_id, day),
  CONSTRAINT fk_sprint_day_snapshot_sprint_id FOREIGN KEY (sprint_id) REFERENCES sprint (id)
);

-- BLOCK 3: sprint_report — written once, when a sprint completes; drives velocity
CREATE TABLE sprint_report (
  sprint_id uuid NOT NULL,
  committed_points numeric(7, 1) NOT NULL,
  completed_points numeric(7, 1) NOT NULL,
  added_points numeric(7, 1) NOT NULL,
  removed_points numeric(7, 1) NOT NULL,
  completed_issue_ids uuid[] NOT NULL,
  incomplete_issue_ids uuid[] NOT NULL,
  closed_at timestamptz NOT NULL,
  CONSTRAINT pk_sprint_report PRIMARY KEY (sprint_id),
  CONSTRAINT fk_sprint_report_sprint_id FOREIGN KEY (sprint_id) REFERENCES sprint (id)
);

-- BLOCK 4: processed_event — idempotent consumers: a second insert of the same (group, event) is a duplicate
CREATE TABLE processed_event (
  consumer_group text NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now()
  -- [jooq ignore start]
  , CONSTRAINT pk_processed_event PRIMARY KEY (consumer_group, event_id)
  -- [jooq ignore stop]
);

-- the daily purge of rows older than 7 days
CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);

-- BLOCK 5: saved_filter — TQL queries people keep (phase 11 writes them)
CREATE TABLE saved_filter (
  id uuid NOT NULL,
  workspace_id uuid NOT NULL,
  owner_id uuid NOT NULL,
  name text NOT NULL,
  tql text NOT NULL,
  starred boolean NOT NULL DEFAULT false,
  shared boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_saved_filter PRIMARY KEY (id),
  CONSTRAINT fk_saved_filter_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspace (id),
  CONSTRAINT fk_saved_filter_owner_id FOREIGN KEY (owner_id) REFERENCES app_user (id),
  CONSTRAINT ck_saved_filter_name CHECK (char_length(name) BETWEEN 1 AND 80)
);

-- "my filters" in the filter picker
CREATE INDEX ix_saved_filter_owner_id ON saved_filter (owner_id);
