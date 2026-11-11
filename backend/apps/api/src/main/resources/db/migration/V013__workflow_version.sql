-- Phase 17: the workflow editor saves the whole workflow at once (PUT /projects/{key}/workflow, If-Match).
-- The statuses and transitions are many rows, so the optimistic lock can't live on one of them: it gets a
-- row of its own. The project's version isn't reused: renaming the project must not make someone's
-- unsaved workflow edit fail with 412.

-- BLOCK 1: workflow — one row per project, only the version. No row yet = version 0: projects created before
-- this migration (and the dev seed, which runs after it) get theirs on their first save.
CREATE TABLE workflow (
  project_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT pk_workflow PRIMARY KEY (project_id),
  CONSTRAINT fk_workflow_project_id FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT ck_workflow_version CHECK (version >= 0)
);
