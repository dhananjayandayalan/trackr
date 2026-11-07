-- BLOCK 1: issue_event — the history, append-only: rows are inserted, never updated or deleted
CREATE TABLE issue_event (
  -- bigint identity, not a uuid: an append-only log, ordered by insertion; also the history cursor
  id bigint GENERATED ALWAYS AS IDENTITY,
  issue_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  kind text NOT NULL,
  -- for field_changed: which field (summary, assignee, …)
  field text,
  -- JSON values, so one column holds a string, a number, a list or an object
  old_value jsonb,
  new_value jsonb,
  CONSTRAINT pk_issue_event PRIMARY KEY (id),
  CONSTRAINT fk_issue_event_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id),
  CONSTRAINT fk_issue_event_actor_id FOREIGN KEY (actor_id) REFERENCES app_user (id),
  CONSTRAINT ck_issue_event_kind CHECK (kind IN ('created', 'field_changed', 'transitioned', 'moved', 'commented',
    'linked', 'logged_work', 'deleted'))
);

-- the History tab: an issue's events, newest first, paged by id
CREATE INDEX ix_issue_event_issue_id_id ON issue_event (issue_id, id DESC);

-- BLOCK 2: issue_link — "MAR-1 blocks MAR-2"; "MAR-2 is blocked by MAR-1" is the same row read backwards
CREATE TABLE issue_link (
  id uuid NOT NULL,
  source_issue_id uuid NOT NULL,
  target_issue_id uuid NOT NULL,
  type text NOT NULL,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_issue_link PRIMARY KEY (id),
  CONSTRAINT fk_issue_link_source_issue_id FOREIGN KEY (source_issue_id) REFERENCES issue (id),
  CONSTRAINT fk_issue_link_target_issue_id FOREIGN KEY (target_issue_id) REFERENCES issue (id),
  CONSTRAINT fk_issue_link_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
  CONSTRAINT ck_issue_link_type CHECK (type IN ('blocks', 'relates', 'duplicates')),
  CONSTRAINT ck_issue_link_not_self CHECK (source_issue_id <> target_issue_id)
  -- [jooq ignore start]
  -- the same link twice means nothing; also serves "links where this issue is the source"
  , CONSTRAINT uq_issue_link_source_target_type UNIQUE (source_issue_id, target_issue_id, type)
  -- [jooq ignore stop]
);

-- the reverse read on the issue page: "links where this issue is the target" (is blocked by, is duplicated by)
CREATE INDEX ix_issue_link_target_issue_id ON issue_link (target_issue_id);

-- BLOCK 3: watcher — who gets notified about an issue
CREATE TABLE watcher (
  issue_id uuid NOT NULL,
  user_id uuid NOT NULL,
  -- also serves "watchers of an issue" (count, notifier fan-out)
  CONSTRAINT pk_watcher PRIMARY KEY (issue_id, user_id),
  CONSTRAINT fk_watcher_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id),
  CONSTRAINT fk_watcher_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
);

-- BLOCK 4: worklog — time spent
CREATE TABLE worklog (
  id uuid NOT NULL,
  issue_id uuid NOT NULL,
  user_id uuid NOT NULL,
  started_at timestamptz NOT NULL,
  minutes int NOT NULL,
  note text,
  CONSTRAINT pk_worklog PRIMARY KEY (id),
  CONSTRAINT fk_worklog_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id),
  CONSTRAINT fk_worklog_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT ck_worklog_minutes CHECK (minutes > 0),
  CONSTRAINT ck_worklog_note CHECK (char_length(note) <= 2000)
);

-- the Work log tab: an issue's entries, newest first
CREATE INDEX ix_worklog_issue_id_started_at ON worklog (issue_id, started_at DESC);

-- BLOCK 5: issue_view — "recently viewed", upserted on every open
CREATE TABLE issue_view (
  user_id uuid NOT NULL,
  issue_id uuid NOT NULL,
  viewed_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_issue_view PRIMARY KEY (user_id, issue_id),
  CONSTRAINT fk_issue_view_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT fk_issue_view_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id)
);

-- "Recently viewed" on the home screen and the trim job that keeps 50 per user: a user's views, newest first
CREATE INDEX ix_issue_view_user_id_viewed_at ON issue_view (user_id, viewed_at DESC);

-- BLOCK 6: issue_star
CREATE TABLE issue_star (
  user_id uuid NOT NULL,
  issue_id uuid NOT NULL,
  starred_at timestamptz NOT NULL DEFAULT now(),
  -- also serves "my starred issues"
  CONSTRAINT pk_issue_star PRIMARY KEY (user_id, issue_id),
  CONSTRAINT fk_issue_star_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT fk_issue_star_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id)
);
