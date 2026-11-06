-- Same jOOQ rule as V002: unique constraints on text columns sit between jooq ignore markers.

-- BLOCK 1: project
CREATE TABLE project (
  id uuid NOT NULL,
  workspace_id uuid NOT NULL,
  key text NOT NULL,
  name text NOT NULL,
  description text,
  template text NOT NULL,
  lead_id uuid NOT NULL,
  access text NOT NULL,
  color text NOT NULL,
  -- last issue number used (MAR-131 → 131); phase 07 increments it with UPDATE … RETURNING
  issue_seq bigint NOT NULL DEFAULT 0,
  archived_at timestamptz,
  -- optimistic lock: every UPDATE checks and bumps it; the API shows it as ETag "v<version>"
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_project PRIMARY KEY (id),
  CONSTRAINT fk_project_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspace (id),
  CONSTRAINT fk_project_lead_id FOREIGN KEY (lead_id) REFERENCES app_user (id),
  -- keys prefix every issue key (MAR-131), so 2–6 capital letters only
  CONSTRAINT ck_project_key CHECK (key ~ '^[A-Z]{2,6}$'),
  CONSTRAINT ck_project_name CHECK (char_length(name) BETWEEN 1 AND 80),
  CONSTRAINT ck_project_template CHECK (template IN ('scrum', 'kanban', 'bugs', 'blank')),
  CONSTRAINT ck_project_access CHECK (access IN ('private', 'limited', 'open')),
  CONSTRAINT ck_project_color CHECK (color IN ('pink', 'cyan', 'lime', 'yellow', 'orange', 'violet')),
  CONSTRAINT ck_project_issue_seq CHECK (issue_seq >= 0)
  -- [jooq ignore start]
  -- two projects in one workspace cannot share a key; ProblemDetailsAdvice maps this name to PROJECT_KEY_TAKEN
  , CONSTRAINT uq_project_workspace_key UNIQUE (workspace_id, key)
  -- [jooq ignore stop]
);

-- BLOCK 2: project_member
CREATE TABLE project_member (
  project_id uuid NOT NULL,
  user_id uuid NOT NULL,
  role text NOT NULL,
  added_at timestamptz NOT NULL DEFAULT now(),
  -- a user has one role per project
  CONSTRAINT pk_project_member PRIMARY KEY (project_id, user_id),
  CONSTRAINT fk_project_member_project_id FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT fk_project_member_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT ck_project_member_role CHECK (role IN ('admin', 'member', 'viewer'))
);

-- the project list asks "which projects is this user a member of?"; the PK starts with project_id, so it can't serve that
CREATE INDEX ix_project_member_user_id ON project_member (user_id);

-- BLOCK 3: project_star
CREATE TABLE project_star (
  user_id uuid NOT NULL,
  project_id uuid NOT NULL,
  starred_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_project_star PRIMARY KEY (user_id, project_id),
  CONSTRAINT fk_project_star_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT fk_project_star_project_id FOREIGN KEY (project_id) REFERENCES project (id)
);

-- BLOCK 4: project_visit — upserted on each project open
CREATE TABLE project_visit (
  user_id uuid NOT NULL,
  project_id uuid NOT NULL,
  visited_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_project_visit PRIMARY KEY (user_id, project_id),
  CONSTRAINT fk_project_visit_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT fk_project_visit_project_id FOREIGN KEY (project_id) REFERENCES project (id)
);

-- "Recent projects" on the home screen: a user's last 5 visits, newest first, read straight from the index
CREATE INDEX ix_project_visit_user_id_visited_at ON project_visit (user_id, visited_at DESC);
