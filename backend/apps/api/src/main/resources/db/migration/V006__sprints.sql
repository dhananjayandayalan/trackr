-- The table only: issue.sprint_id needs it now; starting, completing and planning sprints arrive in phase 08.

-- BLOCK 1: sprint
CREATE TABLE sprint (
  id uuid NOT NULL,
  project_id uuid NOT NULL,
  name text NOT NULL,
  goal text,
  state text NOT NULL DEFAULT 'planned',
  starts_on date,
  ends_on date,
  -- order of the planned sprints in the backlog
  position int NOT NULL DEFAULT 0,
  completed_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_sprint PRIMARY KEY (id),
  CONSTRAINT fk_sprint_project_id FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT ck_sprint_name CHECK (char_length(name) BETWEEN 1 AND 80),
  CONSTRAINT ck_sprint_state CHECK (state IN ('planned', 'active', 'closed')),
  -- 00-product §4.5: starting a sprint needs its dates
  CONSTRAINT ck_sprint_dates_when_active CHECK (state <> 'active' OR (starts_on IS NOT NULL AND ends_on IS NOT NULL)),
  CONSTRAINT ck_sprint_ends_after_start CHECK (ends_on >= starts_on),
  CONSTRAINT ck_sprint_position CHECK (position >= 0)
);

-- BLOCK 2: indexes
-- the backlog screen lists a project's sprints in order
CREATE INDEX ix_sprint_project_id_position ON sprint (project_id, position);

-- [jooq ignore start]
-- at most one active sprint per project (00-product §4.5), enforced by the database: a partial unique index
-- covers only the rows matching WHERE, so any number of planned and closed sprints can share a project.
-- H2 (jOOQ's codegen) has no partial indexes, hence the markers.
CREATE UNIQUE INDEX uq_sprint_one_active ON sprint (project_id) WHERE state = 'active';
-- [jooq ignore stop]
