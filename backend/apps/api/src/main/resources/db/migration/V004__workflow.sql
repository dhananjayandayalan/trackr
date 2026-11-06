-- BLOCK 1: workflow_status — the columns of a project's workflow (To do, In progress, …)
CREATE TABLE workflow_status (
  id uuid NOT NULL,
  project_id uuid NOT NULL,
  name text NOT NULL,
  -- what the status means for reports and "open issue" counts, whatever its name
  category text NOT NULL,
  position int NOT NULL,
  -- a VOLT colour token name
  color text NOT NULL,
  wip_limit int,
  CONSTRAINT pk_workflow_status PRIMARY KEY (id),
  CONSTRAINT fk_workflow_status_project_id FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT ck_workflow_status_name CHECK (char_length(name) BETWEEN 1 AND 30),
  CONSTRAINT ck_workflow_status_category CHECK (category IN ('todo', 'in_progress', 'done')),
  CONSTRAINT ck_workflow_status_position CHECK (position >= 0),
  CONSTRAINT ck_workflow_status_wip_limit CHECK (wip_limit > 0)
  -- [jooq ignore start]
  -- two "Done" columns in one project would be ambiguous; also serves "statuses of a project" lookups
  , CONSTRAINT uq_workflow_status_project_id_name UNIQUE (project_id, name)
  -- [jooq ignore stop]
);

-- BLOCK 2: workflow_transition — which status an issue may move to from which
CREATE TABLE workflow_transition (
  id uuid NOT NULL,
  project_id uuid NOT NULL,
  -- null = from any status
  from_status_id uuid,
  to_status_id uuid NOT NULL,
  CONSTRAINT pk_workflow_transition PRIMARY KEY (id),
  CONSTRAINT fk_workflow_transition_project_id FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT fk_workflow_transition_from_status_id FOREIGN KEY (from_status_id) REFERENCES workflow_status (id),
  CONSTRAINT fk_workflow_transition_to_status_id FOREIGN KEY (to_status_id) REFERENCES workflow_status (id)
  -- [jooq ignore start]
  -- NULLS NOT DISTINCT (Postgres 15+): two "any → Done" rows count as duplicates; plain UNIQUE treats nulls as all different.
  -- jOOQ's parser doesn't know the clause, hence the markers. Also serves "transitions of a project" lookups.
  , CONSTRAINT uq_workflow_transition_project_id_from_status_id_to_status_id
      UNIQUE NULLS NOT DISTINCT (project_id, from_status_id, to_status_id)
  -- [jooq ignore stop]
);
