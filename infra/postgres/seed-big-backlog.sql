-- Dev only: 1 000 more issues at the bottom of MAR's backlog, to check that the backlog screen stays smooth
-- with a long list (phase 14, "Done when"). Run it as often as you like; each run adds another 1 000.
--
--   docker compose --env-file .env exec -T postgres psql -U trakr -d trakr -v ON_ERROR_STOP=1 < postgres/seed-big-backlog.sql
--
-- Straight SQL, so no events: the board and backlog show these issues (they read the tables), search doesn't
-- (Elasticsearch is fed by issue events, phase 10).
BEGIN;

WITH project AS (
  -- BLOCK 1: allocate 1 000 issue numbers the way the api does: from project.issue_seq, in this transaction.
  -- Keys are unique per workspace, not globally: another workspace may have a MAR too, so name the workspace.
  UPDATE project SET issue_seq = issue_seq + 1000
  WHERE key = 'MAR' AND workspace_id = (SELECT id FROM workspace WHERE slug = 'trakr-demo')
  RETURNING id, workspace_id, key, issue_seq - 1000 AS first_number
),
todo AS (
  SELECT s.id FROM workflow_status s JOIN project p ON s.project_id = p.id
  WHERE s.category = 'todo' ORDER BY s.position LIMIT 1
),
ada AS (
  SELECT id FROM app_user WHERE email = 'ada@trakr.dev'
),
last_rank AS (
  -- BLOCK 2: ranks after the backlog's current last one: that rank plus a two-digit base-62 counter and 'V'
  -- ("…a0V", "…a1V", …). A longer string with the same prefix sorts after it (COLLATE "C"), the counter keeps
  -- them in order, and none ends in '0' (Rank.java's rule).
  SELECT coalesce(max(i.rank), 'V') AS rank FROM issue i JOIN project p ON i.project_id = p.id
  WHERE i.sprint_id IS NULL AND i.deleted_at IS NULL
),
digits AS (
  SELECT '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'::text AS d
)
INSERT INTO issue (id, workspace_id, project_id, number, key, type, summary, status_id, status_category, priority,
                   reporter_id, assignee_id, story_points, rank, version)
SELECT
  gen_random_uuid(),
  p.workspace_id,
  p.id,
  p.first_number + n,
  p.key || '-' || (p.first_number + n),
  (ARRAY['story', 'task', 'bug'])[1 + n % 3],
  'Seeded backlog item ' || n || ': ' || (ARRAY['Tidy the pricing table', 'Audit image sizes', 'Fix footer links',
    'Translate the FAQ', 'Speed up the hero video', 'Add newsletter double opt-in'])[1 + n % 6],
  todo.id,
  'todo',
  (ARRAY['highest', 'high', 'medium', 'medium', 'low', 'lowest'])[1 + n % 6],
  ada.id,
  CASE WHEN n % 4 = 0 THEN ada.id END,
  (ARRAY[1, 2, 3, 5, 8])[1 + n % 5],
  last_rank.rank || substr(digits.d, 1 + (n / 62) % 62, 1) || substr(digits.d, 1 + n % 62, 1) || 'V',
  0
FROM project p, todo, ada, last_rank, digits, generate_series(1, 1000) AS n;

COMMIT;
