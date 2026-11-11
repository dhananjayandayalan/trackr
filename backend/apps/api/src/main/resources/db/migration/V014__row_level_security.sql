-- Phase 18: defence in depth (09-security §6, §9; 02-data-model §6–7). Three things:
--   1. each app connects as its own role, with grants that match what it really reads and writes;
--   2. tenant tables only show rows of the caller's workspaces (row-level security), whatever SQL runs;
--   3. an audit_log for changes that are not issue history (project membership, workflows).
-- Flyway keeps running as the owner (trakr): owners bypass RLS and own every table, so migrations and the
-- repeatable dev seed are unaffected. Everything but the audit_log table is Postgres-only, hence the markers.

-- [jooq ignore start]

-- BLOCK 1: the roles. infra/postgres/init creates them with passwords on a fresh volume; a database that
-- predates that script (or a managed one where an admin creates logins) still gets them here, without a
-- login until someone gives them a password. Role attributes are cluster-wide, so this is idempotent.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'trakr_api') THEN
    CREATE ROLE trakr_api NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'trakr_workers') THEN
    CREATE ROLE trakr_workers NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'trakr_collab') THEN
    CREATE ROLE trakr_collab NOLOGIN;
  END IF;
END
$$;

-- workers build cross-tenant projections (a reindex reads every workspace's issues, a digest every user's
-- notifications) and collab relays edits for whichever issue a socket opened: neither runs "as" a user, so
-- both skip RLS. What protects the data from them is the grants below — collab can read four tables, write two.
-- (Granting BYPASSRLS needs a superuser: true for the compose and Testcontainers owner, not on every managed DB.)
ALTER ROLE trakr_workers BYPASSRLS;
ALTER ROLE trakr_collab BYPASSRLS;

-- BLOCK 2: grants (02-data-model §6). Start from nothing, then add exactly what each app needs.
GRANT USAGE ON SCHEMA public TO trakr_api, trakr_workers, trakr_collab;

-- api: reads and writes the domain tables and the outbox
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO trakr_api;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO trakr_api;
-- ...but not the tables the other apps own: the api only reads them. Marking a notification read is the one
-- write it does there (the bell's "mark all read"), so notification keeps UPDATE.
REVOKE INSERT, UPDATE, DELETE ON sprint_day_snapshot, sprint_report, processed_event, collab_update, collab_snapshot
  FROM trakr_api;
REVOKE INSERT, DELETE ON notification FROM trakr_api;
-- and never Flyway's bookkeeping: only the owner migrates
REVOKE ALL ON flyway_schema_history FROM trakr_api;

-- workers: read the domain tables, write their read models and their dedupe table
GRANT SELECT ON ALL TABLES IN SCHEMA public TO trakr_workers;
REVOKE ALL ON flyway_schema_history, event_publication FROM trakr_workers;
GRANT INSERT, UPDATE, DELETE ON notification, sprint_day_snapshot, sprint_report, processed_event TO trakr_workers;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO trakr_workers;

-- collab: who may open an issue's room (IssueAccess), and the CRDT log it owns
GRANT SELECT ON issue, project, workspace_member, project_member TO trakr_collab;
GRANT SELECT, INSERT, UPDATE, DELETE ON collab_update, collab_snapshot TO trakr_collab;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO trakr_collab;

-- tables later migrations create: the api may use them, the workers may read them. A migration that adds a
-- table for workers or collab to write grants that itself, like this one does.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO trakr_api;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO trakr_api;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO trakr_workers;

-- BLOCK 3: the tenant rule (02-data-model §7). A row of a tenant table is visible when its workspace is one the
-- current user belongs to. Not one workspace id per transaction, as 09-security first sketched: a user added
-- to someone else's project joins that workspace too, and "Your work", search and the projects directory read
-- across all of them. So the api says who the user is (app.user_id) and the policy derives the workspaces.
--
-- app.rls_bypass = 'on' is for the api's own background work (Kafka consumers, schedulers, async listeners):
-- no user, all tenants. Both settings are set per transaction by TenantConnectionPreparer in the api, never
-- by a request. Nothing set at all (e.g. an anonymous request) = no rows: RLS fails closed.
--
-- STABLE + plain SQL lets the planner inline this into each policy; the subquery doesn't depend on the row,
-- so it runs once per query (a hashed subplan), not once per row. workspace_member has no RLS itself, or the
-- policy would recurse into its own table.
CREATE FUNCTION app_tenant_visible(row_workspace_id uuid) RETURNS boolean
  LANGUAGE sql STABLE
AS $$
  SELECT current_setting('app.rls_bypass', true) = 'on'
      OR row_workspace_id IN (
           SELECT m.workspace_id FROM workspace_member m
           WHERE m.user_id = nullif(current_setting('app.user_id', true), '')::uuid)
$$;

-- BLOCK 4: the tenant tables: everything with a workspace_id of its own. Their children (comments, sprints,
-- attachments, ...) are reached through them, and the services check access per project anyway — RLS is the
-- safety net under that check, not a replacement for it. One policy for all commands: USING filters what
-- SELECT/UPDATE/DELETE see, and (with no WITH CHECK) the same test applies to rows INSERT/UPDATE write.
ALTER TABLE project ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON project USING (app_tenant_visible(workspace_id));

ALTER TABLE issue ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON issue USING (app_tenant_visible(workspace_id));

ALTER TABLE saved_filter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON saved_filter USING (app_tenant_visible(workspace_id));

-- [jooq ignore stop]

-- BLOCK 5: audit_log (09-security §9) — who changed a project's members or workflow, and from what to what.
-- Append-only like issue_event; written by the audit module's listener in the same transaction as the change.
CREATE TABLE audit_log (
  id bigint GENERATED ALWAYS AS IDENTITY,
  workspace_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  -- project.member.added|project.member.changed|project.member.removed|project.workflow.replaced
  action text NOT NULL,
  -- what was acted on, e.g. project:<uuid>
  target text NOT NULL,
  before jsonb,
  after jsonb,
  at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_audit_log PRIMARY KEY (id),
  CONSTRAINT fk_audit_log_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspace (id),
  CONSTRAINT fk_audit_log_actor_id FOREIGN KEY (actor_id) REFERENCES app_user (id)
);

-- [jooq ignore start]
-- a workspace's log, newest first (the admin's "what changed here?")
CREATE INDEX ix_audit_log_workspace_id_at ON audit_log (workspace_id, at DESC);
-- the api appends; nobody updates or deletes history (the default privileges above gave it all four)
REVOKE UPDATE, DELETE ON audit_log FROM trakr_api;
-- [jooq ignore stop]
