-- jOOQ codegen simulates this file in H2, which stores text and citext as CLOB and cannot index a CLOB.
-- So every unique constraint or primary key on those columns sits between jooq ignore markers:
-- jOOQ skips them, Flyway sees plain comments, and Postgres still enforces them.

-- BLOCK 1: workspace
CREATE TABLE workspace (
  id uuid NOT NULL,
  slug text NOT NULL,
  name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_workspace PRIMARY KEY (id),
  -- slugs appear in URLs, so only lowercase letters, digits and hyphens
  CONSTRAINT ck_workspace_slug CHECK (slug ~ '^[a-z0-9-]{3,40}$')
  -- [jooq ignore start]
  -- two workspaces cannot share a URL
  , CONSTRAINT uq_workspace_slug UNIQUE (slug)
  -- [jooq ignore stop]
);

-- BLOCK 2: app_user
CREATE TABLE app_user (
  id uuid NOT NULL,
  -- citext compares case-insensitively, so no lower() is needed in queries
  email citext NOT NULL,
  display_name text NOT NULL,
  initials text NOT NULL,
  avatar_color text NOT NULL,
  -- null for users who only sign in with OAuth
  password_hash text,
  email_verified_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_app_user PRIMARY KEY (id),
  CONSTRAINT ck_app_user_initials CHECK (char_length(initials) BETWEEN 1 AND 3),
  CONSTRAINT ck_app_user_avatar_color CHECK (avatar_color IN ('pink', 'cyan', 'lime', 'yellow', 'orange', 'violet'))
  -- [jooq ignore start]
  -- A@x.com and a@x.com are the same account
  , CONSTRAINT uq_app_user_email UNIQUE (email)
  -- [jooq ignore stop]
);

-- BLOCK 3: user_identity
CREATE TABLE user_identity (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  provider text NOT NULL,
  subject text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_user_identity PRIMARY KEY (id),
  CONSTRAINT fk_user_identity_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT ck_user_identity_provider CHECK (provider IN ('google', 'github'))
  -- [jooq ignore start]
  -- one provider account can link to only one user
  , CONSTRAINT uq_user_identity_provider_subject UNIQUE (provider, subject)
  -- [jooq ignore stop]
);

-- BLOCK 4: workspace_member
CREATE TABLE workspace_member (
  workspace_id uuid NOT NULL,
  user_id uuid NOT NULL,
  role text NOT NULL,
  joined_at timestamptz NOT NULL DEFAULT now(),
  -- a user can join a workspace only once
  CONSTRAINT pk_workspace_member PRIMARY KEY (workspace_id, user_id),
  CONSTRAINT fk_workspace_member_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspace (id),
  CONSTRAINT fk_workspace_member_user_id FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT ck_workspace_member_role CHECK (role IN ('owner', 'member'))
);

-- BLOCK 5: password_reset
CREATE TABLE password_reset (
  -- SHA-256 of the emailed token; the token itself is never stored
  token_hash text NOT NULL,
  user_id uuid NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  CONSTRAINT fk_password_reset_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
  -- [jooq ignore start]
  , CONSTRAINT pk_password_reset PRIMARY KEY (token_hash)
  -- [jooq ignore stop]
);
