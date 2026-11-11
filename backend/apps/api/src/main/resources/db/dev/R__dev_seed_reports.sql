-- Phase 17 dev data: what the reports, the timeline and the bell need, on top of R__dev_seed.sql (Flyway runs
-- repeatable migrations in description order, so "dev seed reports" comes after "dev seed").
-- A file of its own so a database that already has the demo workspace gets it too: this one checks for its own
-- rows, not for the workspace.
--
-- The burndown can be checked by hand from these rows (phase 17 "Done when"), for MAR Sprint 2:
--   committed = the points of MAR-24 … MAR-49 when the sprint started. Points cycle 1, 2, 3, 5, 8, none by
--   n % 6, so 24–47 are four cycles of 19 = 76, plus MAR-48 (1) and MAR-49 (2) = 79.
--   Working days Mon 2 Nov – Mon 16 Nov: 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 16 = 11 days, 10 steps of 79 / 10 = 7.9:
--   ideal = 79, 71.1, 63.2, 55.3, 47.4, 39.5, 31.6, 23.7, 15.8, 7.9, 0.
--   Done so far (status n % 4 = 3, with points): MAR-27 (5) on 4 Nov, MAR-31 (2) on 5 Nov, MAR-39 (5) on 9 Nov,
--   MAR-43 (2) on 10 Nov → remaining 79, 79, 74, 72, 72, 67, 65 — the same 65 the issue table gives today.

CREATE OR REPLACE FUNCTION pg_temp.seed_id(name text) RETURNS uuid
  LANGUAGE sql IMMUTABLE AS $$ SELECT md5(name)::uuid $$;

DO $seed$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM workspace WHERE slug = 'trakr-demo')
     OR EXISTS (SELECT 1 FROM sprint_report WHERE sprint_id = pg_temp.seed_id('MAR:sprint:1')) THEN
    RETURN;
  END IF;

  -- BLOCK 1: MAR Sprint 1 (closed): one row per working day, burned down to 0; its report, as completing it wrote it.
  -- 65 points = MAR-4 (8) + three cycles of 19 (MAR-6 … MAR-22).
  INSERT INTO sprint_day_snapshot (sprint_id, day, scope_points, completed_points, remaining_points, issue_count)
  SELECT pg_temp.seed_id('MAR:sprint:1'), d.day, 65, 65 - d.remaining, d.remaining, 20
  FROM (VALUES (DATE '2026-10-19', 65), (DATE '2026-10-20', 65), (DATE '2026-10-21', 57), (DATE '2026-10-22', 52),
               (DATE '2026-10-23', 47), (DATE '2026-10-26', 39), (DATE '2026-10-27', 31), (DATE '2026-10-28', 26),
               (DATE '2026-10-29', 18), (DATE '2026-10-30', 8), (DATE '2026-11-02', 0)) AS d (day, remaining);

  INSERT INTO sprint_report (sprint_id, committed_points, completed_points, added_points, removed_points,
                             completed_issue_ids, incomplete_issue_ids, closed_at)
  SELECT pg_temp.seed_id('MAR:sprint:1'), 65, 65, 0, 0,
         array_agg(pg_temp.seed_id('MAR:issue:' || n) ORDER BY n), '{}', TIMESTAMPTZ '2026-11-02 17:00Z'
  FROM generate_series(4, 23) AS n;

  -- BLOCK 2: MAR Sprint 2 (active) up to 10 Nov — see the arithmetic at the top. The four subtasks MAR-50 … 53
  -- (no points) joined on 4 Nov: the issue count goes up, the scope in points doesn't.
  INSERT INTO sprint_day_snapshot (sprint_id, day, scope_points, completed_points, remaining_points, issue_count)
  SELECT pg_temp.seed_id('MAR:sprint:2'), d.day, 79, 79 - d.remaining, d.remaining, d.issues
  FROM (VALUES (DATE '2026-11-02', 79, 26), (DATE '2026-11-03', 79, 26), (DATE '2026-11-04', 74, 30),
               (DATE '2026-11-05', 72, 30), (DATE '2026-11-06', 72, 30), (DATE '2026-11-09', 67, 30),
               (DATE '2026-11-10', 65, 30)) AS d (day, remaining, issues)
  ON CONFLICT (sprint_id, day) DO NOTHING;

  -- BLOCK 3: the timeline — epics get start and due dates; children of the two sprints get their sprint's dates
  UPDATE issue SET start_date = d.starts, due_date = d.due
  FROM (VALUES ('MAR:issue:1', DATE '2026-10-19', DATE '2026-11-27'),
               ('MAR:issue:2', DATE '2026-11-02', DATE '2026-12-18'),
               ('MAR:issue:3', DATE '2026-11-16', DATE '2027-01-15')) AS d (name, starts, due)
  WHERE issue.id = pg_temp.seed_id(d.name);

  UPDATE issue SET start_date = DATE '2026-10-19', due_date = DATE '2026-11-02'
  WHERE sprint_id = pg_temp.seed_id('MAR:sprint:1');
  UPDATE issue SET start_date = DATE '2026-11-02'
  WHERE sprint_id = pg_temp.seed_id('MAR:sprint:2');

  -- BLOCK 4: Ada's bell — what the notifier would have written for Grace's work: three unread, two read
  INSERT INTO notification (id, user_id, kind, issue_id, actor_id, payload, created_at, read_at)
  SELECT pg_temp.seed_id('notification:' || n.no), pg_temp.seed_id('user:ada'), n.kind,
         pg_temp.seed_id('MAR:issue:' || n.issue), pg_temp.seed_id('user:grace'),
         jsonb_build_object('issueKey', 'MAR-' || n.issue, 'summary', i.summary, 'actorName', 'Grace Hopper'),
         now() - make_interval(mins => n.ago), CASE WHEN n.read THEN now() - make_interval(mins => n.ago - 5) END
  FROM (VALUES (1, 'assigned', 24, 12, false), (2, 'mentioned', 31, 45, false), (3, 'commented', 33, 180, false),
               (4, 'status_changed', 27, 1500, true), (5, 'assigned', 26, 2900, true)) AS n (no, kind, issue, ago, read)
  JOIN issue i ON i.id = pg_temp.seed_id('MAR:issue:' || n.issue);
END
$seed$;
