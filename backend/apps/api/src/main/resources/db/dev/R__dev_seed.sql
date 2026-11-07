-- Dev data so the screens look like the VOLT canvas: 2 users, 3 projects (scrum, kanban, bug tracking), ~300 issues.
-- Loaded only with the dev profile (application-dev.yml adds classpath:db/dev to spring.flyway.locations), so the
-- integration tests — which run with dev as the default profile — see it too; BoardQueryIT snapshots its board.
--
-- Repeatable migration (R__): Flyway reruns it whenever this file changes. It only inserts when the demo
-- workspace doesn't exist yet, so to load a changed seed into an existing database, reset the database.
--
-- Sign in as ada@trakr.dev or grace@trakr.dev, password correct-horse-7.
-- Ids are md5 hashes of readable names ("MAR:issue:24"): the same on every machine, so snapshots can compare them.

-- BLOCK 1: helpers, temporary — they disappear when Flyway's connection closes
CREATE OR REPLACE FUNCTION pg_temp.seed_id(name text) RETURNS uuid
  LANGUAGE sql IMMUTABLE AS $$ SELECT md5(name)::uuid $$;

-- the n-th rank of a list: two base-62 digits, increasing with n, never ending in '0' (see Rank.java)
CREATE OR REPLACE FUNCTION pg_temp.seed_rank(n int) RETURNS text
  LANGUAGE sql IMMUTABLE AS $$
    SELECT substr('0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz', n / 61 + 11, 1)
        || substr('0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz', n % 61 + 2, 1)
  $$;

DO $seed$
BEGIN
  IF EXISTS (SELECT 1 FROM workspace WHERE slug = 'trakr-demo') THEN
    RETURN;
  END IF;

  -- BLOCK 2: workspace and people
  INSERT INTO workspace (id, slug, name) VALUES (pg_temp.seed_id('workspace'), 'trakr-demo', 'Trakr Demo');

  INSERT INTO app_user (id, email, display_name, initials, avatar_color, password_hash, email_verified_at) VALUES
    (pg_temp.seed_id('user:ada'), 'ada@trakr.dev', 'Ada Lovelace', 'AL', 'violet',
     '$argon2id$v=19$m=16384,t=2,p=1$j2WXDuW60WJYAExSCj+W9Q$XN86poAPvmjFMY27vqe9PsSXrGGw/fEywHvWcSKirtg', now()),
    (pg_temp.seed_id('user:grace'), 'grace@trakr.dev', 'Grace Hopper', 'GH', 'cyan',
     '$argon2id$v=19$m=16384,t=2,p=1$j2WXDuW60WJYAExSCj+W9Q$XN86poAPvmjFMY27vqe9PsSXrGGw/fEywHvWcSKirtg', now());

  INSERT INTO workspace_member (workspace_id, user_id, role) VALUES
    (pg_temp.seed_id('workspace'), pg_temp.seed_id('user:ada'), 'owner'),
    (pg_temp.seed_id('workspace'), pg_temp.seed_id('user:grace'), 'member');

  -- BLOCK 3: projects, with the statuses, transitions and boards their templates give (TemplateSeeder, BoardSeeder)
  INSERT INTO project (id, workspace_id, key, name, description, template, lead_id, access, color, issue_seq) VALUES
    (pg_temp.seed_id('project:MAR'), pg_temp.seed_id('workspace'), 'MAR', 'Marketing site',
     'The public website: landing pages, pricing, newsletter.', 'scrum', pg_temp.seed_id('user:ada'), 'limited', 'pink', 150),
    (pg_temp.seed_id('project:OPS'), pg_temp.seed_id('workspace'), 'OPS', 'Operations',
     'Infrastructure and on-call chores.', 'kanban', pg_temp.seed_id('user:grace'), 'open', 'cyan', 100),
    (pg_temp.seed_id('project:BUG'), pg_temp.seed_id('workspace'), 'BUG', 'Bug tracker',
     'Bugs reported by customers.', 'bugs', pg_temp.seed_id('user:ada'), 'private', 'orange', 50);

  INSERT INTO project_member (project_id, user_id, role)
  SELECT pg_temp.seed_id('project:' || p.key), pg_temp.seed_id('user:' || u.name), u.role
  FROM (VALUES ('MAR'), ('OPS'), ('BUG')) AS p (key)
  CROSS JOIN (VALUES ('ada', 'admin'), ('grace', 'member')) AS u (name, role);

  INSERT INTO workflow_status (id, project_id, name, category, position, color, wip_limit)
  SELECT pg_temp.seed_id(s.key || ':status:' || s.position), pg_temp.seed_id('project:' || s.key),
         s.name, s.category, s.position, s.color, s.wip_limit
  FROM (VALUES
    ('MAR', 0, 'To do', 'todo', 'todo', NULL::int),
    ('MAR', 1, 'In progress', 'in_progress', 'progress', NULL),
    ('MAR', 2, 'In review', 'in_progress', 'review', NULL),
    ('MAR', 3, 'Done', 'done', 'done', NULL),
    ('OPS', 0, 'Backlog', 'todo', 'todo', NULL),
    ('OPS', 1, 'Selected', 'todo', 'todo', NULL),
    ('OPS', 2, 'In progress', 'in_progress', 'progress', 4),
    ('OPS', 3, 'Done', 'done', 'done', NULL),
    ('BUG', 0, 'Triage', 'todo', 'todo', NULL),
    ('BUG', 1, 'Confirmed', 'todo', 'todo', NULL),
    ('BUG', 2, 'Fixing', 'in_progress', 'progress', NULL),
    ('BUG', 3, 'Verifying', 'in_progress', 'review', NULL),
    ('BUG', 4, 'Closed', 'done', 'done', NULL)) AS s (key, position, name, category, color, wip_limit);

  -- every status → every other status
  INSERT INTO workflow_transition (id, project_id, from_status_id, to_status_id)
  SELECT pg_temp.seed_id(f.id::text || '→' || t.id::text), f.project_id, f.id, t.id
  FROM workflow_status f
  JOIN workflow_status t ON t.project_id = f.project_id AND t.id <> f.id
  WHERE f.project_id IN (pg_temp.seed_id('project:MAR'), pg_temp.seed_id('project:OPS'), pg_temp.seed_id('project:BUG'));

  INSERT INTO board (id, project_id, name, type) VALUES
    (pg_temp.seed_id('MAR:board'), pg_temp.seed_id('project:MAR'), 'MAR board', 'scrum'),
    (pg_temp.seed_id('OPS:board'), pg_temp.seed_id('project:OPS'), 'OPS board', 'kanban'),
    (pg_temp.seed_id('BUG:board'), pg_temp.seed_id('project:BUG'), 'BUG board', 'kanban');

  -- one column per status, except Kanban's Backlog and Bug tracking's Triage (lists, not columns)
  INSERT INTO board_column (id, board_id, name, position, status_ids, wip_limit)
  SELECT pg_temp.seed_id(s.key || ':column:' || s.name),
         pg_temp.seed_id(s.key || ':board'),
         s.name,
         row_number() OVER (PARTITION BY s.key ORDER BY s.position) - 1,
         ARRAY[s.id],
         s.wip_limit
  FROM (SELECT ws.*, p.key FROM workflow_status ws JOIN project p ON p.id = ws.project_id
        WHERE p.workspace_id = pg_temp.seed_id('workspace')) AS s
  WHERE NOT (s.key = 'OPS' AND s.name = 'Backlog') AND NOT (s.key = 'BUG' AND s.name = 'Triage');

  -- BLOCK 4: MAR's sprints — one closed, one active, one planned. Fixed dates: the board snapshot contains them.
  INSERT INTO sprint (id, project_id, name, goal, state, starts_on, ends_on, position, started_at, completed_at) VALUES
    (pg_temp.seed_id('MAR:sprint:1'), pg_temp.seed_id('project:MAR'), 'MAR Sprint 1', 'Launch the new landing page',
     'closed', DATE '2026-10-19', DATE '2026-11-02', 0, TIMESTAMPTZ '2026-10-19 09:00Z', TIMESTAMPTZ '2026-11-02 17:00Z'),
    (pg_temp.seed_id('MAR:sprint:2'), pg_temp.seed_id('project:MAR'), 'MAR Sprint 2', 'Ship the new pricing page',
     'active', DATE '2026-11-02', DATE '2026-11-16', 1, TIMESTAMPTZ '2026-11-02 09:00Z', NULL),
    (pg_temp.seed_id('MAR:sprint:3'), pg_temp.seed_id('project:MAR'), 'MAR Sprint 3', NULL,
     'planned', NULL, NULL, 2, NULL, NULL);

  -- BLOCK 5: MAR issues. 1–3 epics · 4–23 sprint 1 (done) · 24–53 sprint 2 (50–53 subtasks of 24–27)
  -- · 54–68 sprint 3 · 69–150 backlog
  INSERT INTO issue (id, workspace_id, project_id, number, key, type, summary, description_text, description_html,
                     status_id, status_category, priority, assignee_id, reporter_id, parent_id, sprint_id,
                     story_points, rank, labels, due_date, flagged, resolved_at, created_at, updated_at)
  SELECT pg_temp.seed_id('MAR:issue:' || n),
         pg_temp.seed_id('workspace'),
         pg_temp.seed_id('project:MAR'),
         n,
         'MAR-' || n,
         i.type,
         CASE WHEN n <= 3 THEN (ARRAY['Website relaunch', 'Pricing experiments', 'Newsletter growth'])[n]
              ELSE (ARRAY['Add', 'Fix', 'Polish', 'Refactor', 'Document', 'Test', 'Redesign'])[n % 7 + 1] || ' '
                || (ARRAY['login page', 'pricing table', 'newsletter signup', 'hero banner', 'checkout flow',
                          'search box', 'footer links', 'blog layout', 'analytics events', 'sitemap',
                          'cookie banner'])[n % 11 + 1] END,
         NULL, NULL,
         pg_temp.seed_id('MAR:status:' || i.status),
         CASE i.status WHEN 0 THEN 'todo' WHEN 3 THEN 'done' ELSE 'in_progress' END,
         (ARRAY['medium', 'high', 'low', 'highest', 'medium', 'lowest'])[n % 6 + 1],
         CASE n % 3 WHEN 0 THEN pg_temp.seed_id('user:ada') WHEN 1 THEN pg_temp.seed_id('user:grace') END,
         CASE n % 2 WHEN 0 THEN pg_temp.seed_id('user:ada') ELSE pg_temp.seed_id('user:grace') END,
         CASE WHEN i.type = 'subtask' THEN pg_temp.seed_id('MAR:issue:' || (n - 26))
              WHEN i.type <> 'epic' AND n % 5 <> 0 THEN pg_temp.seed_id('MAR:issue:' || (n % 3 + 1)) END,
         CASE WHEN n BETWEEN 4 AND 23 THEN pg_temp.seed_id('MAR:sprint:1')
              WHEN n BETWEEN 24 AND 53 THEN pg_temp.seed_id('MAR:sprint:2')
              WHEN n BETWEEN 54 AND 68 THEN pg_temp.seed_id('MAR:sprint:3') END,
         CASE WHEN i.type IN ('epic', 'subtask') THEN NULL ELSE (ARRAY[1, 2, 3, 5, 8, NULL])[n % 6 + 1] END,
         pg_temp.seed_rank(n),
         ARRAY[(ARRAY['web', 'seo', 'copy', 'design'])[n % 4 + 1]],
         CASE WHEN n BETWEEN 24 AND 53 THEN DATE '2026-11-09' + n % 8 END,
         n % 17 = 0,
         CASE WHEN i.status = 3 THEN now() - make_interval(hours => 200 - n) + interval '2 hours' END,
         now() - make_interval(hours => 200 - n),
         now() - make_interval(hours => 200 - n) + interval '2 hours'
  FROM generate_series(1, 150) AS n
  CROSS JOIN LATERAL (SELECT
         CASE WHEN n <= 3 THEN 'epic'
              WHEN n BETWEEN 50 AND 53 THEN 'subtask'
              ELSE (ARRAY['story', 'story', 'bug', 'task'])[n % 4 + 1] END AS type,
         CASE WHEN n = 1 THEN 1
              WHEN n BETWEEN 4 AND 23 THEN 3
              WHEN n BETWEEN 24 AND 53 THEN n % 4
              WHEN n > 68 AND n % 9 = 0 THEN 1
              ELSE 0 END AS status) AS i
  -- parents before children: subtasks and epic children reference rows inserted earlier in this statement
  ORDER BY n;

  -- BLOCK 6: OPS (kanban): 1–40 Backlog · 41–60 Selected · 61–66 In progress (over its WIP limit of 4) · 67–100 Done
  INSERT INTO issue (id, workspace_id, project_id, number, key, type, summary, status_id, status_category, priority,
                     assignee_id, reporter_id, rank, labels, flagged, resolved_at, created_at, updated_at)
  SELECT pg_temp.seed_id('OPS:issue:' || n),
         pg_temp.seed_id('workspace'),
         pg_temp.seed_id('project:OPS'),
         n,
         'OPS-' || n,
         CASE WHEN n % 4 = 0 THEN 'bug' ELSE 'task' END,
         (ARRAY['Rotate', 'Upgrade', 'Monitor', 'Back up', 'Patch', 'Audit'])[n % 6 + 1] || ' '
           || (ARRAY['database', 'load balancer', 'TLS certificates', 'Kafka cluster', 'CI runners', 'DNS records',
                     'log storage'])[n % 7 + 1],
         pg_temp.seed_id('OPS:status:' || s.status),
         CASE s.status WHEN 2 THEN 'in_progress' WHEN 3 THEN 'done' ELSE 'todo' END,
         (ARRAY['medium', 'high', 'low', 'highest', 'medium'])[n % 5 + 1],
         CASE n % 3 WHEN 0 THEN pg_temp.seed_id('user:grace') WHEN 1 THEN pg_temp.seed_id('user:ada') END,
         pg_temp.seed_id('user:grace'),
         pg_temp.seed_rank(n),
         ARRAY[(ARRAY['infra', 'security', 'cost'])[n % 3 + 1]],
         n % 23 = 0,
         -- Done cards spread over the last month: the board shows the ones from the last 14 days
         CASE WHEN s.status = 3 THEN now() - make_interval(days => n - 67) END,
         now() - make_interval(days => 40) + make_interval(hours => n),
         now() - make_interval(days => 40) + make_interval(hours => n)
  FROM generate_series(1, 100) AS n
  CROSS JOIN LATERAL (SELECT CASE WHEN n <= 40 THEN 0 WHEN n <= 60 THEN 1 WHEN n <= 66 THEN 2 ELSE 3 END AS status) AS s;

  -- BLOCK 7: BUG (bug tracking): statuses round-robin over Triage … Closed
  INSERT INTO issue (id, workspace_id, project_id, number, key, type, summary, status_id, status_category, priority,
                     assignee_id, reporter_id, rank, labels, flagged, resolved_at, created_at, updated_at)
  SELECT pg_temp.seed_id('BUG:issue:' || n),
         pg_temp.seed_id('workspace'),
         pg_temp.seed_id('project:BUG'),
         n,
         'BUG-' || n,
         'bug',
         (ARRAY['Crash when', 'Wrong total when', 'Slow page when', 'Typo shown when', 'Error 500 when'])[n % 5 + 1]
           || ' ' || (ARRAY['exporting CSV', 'changing currency', 'opening settings', 'uploading avatars',
                            'paying by card', 'switching language'])[n % 6 + 1],
         pg_temp.seed_id('BUG:status:' || (n % 5)),
         CASE n % 5 WHEN 2 THEN 'in_progress' WHEN 3 THEN 'in_progress' WHEN 4 THEN 'done' ELSE 'todo' END,
         (ARRAY['high', 'highest', 'medium', 'low'])[n % 4 + 1],
         CASE n % 2 WHEN 0 THEN pg_temp.seed_id('user:ada') END,
         pg_temp.seed_id('user:grace'),
         pg_temp.seed_rank(n),
         ARRAY['customer'],
         n % 11 = 0,
         CASE WHEN n % 5 = 4 THEN now() - make_interval(days => 50 - n) END,
         now() - make_interval(days => 50 - n),
         now() - make_interval(days => 50 - n)
  FROM generate_series(1, 50) AS n;

  -- BLOCK 8: what the sprints committed to: sprint 1 everything it had, sprint 2 all but its last four
  -- issues (added after the start — the report shows them as "added")
  UPDATE sprint s SET committed_issue_ids = c.ids, committed_points = c.points
  FROM (SELECT i.sprint_id, array_agg(i.id ORDER BY i.number) AS ids, coalesce(sum(i.story_points), 0) AS points
        FROM issue i
        WHERE i.sprint_id IN (pg_temp.seed_id('MAR:sprint:1'), pg_temp.seed_id('MAR:sprint:2')) AND i.number <= 49
        GROUP BY i.sprint_id) AS c
  WHERE s.id = c.sprint_id;

  -- BLOCK 9: history — "created" for every issue, "transitioned" for those that left their first status
  INSERT INTO issue_event (issue_id, actor_id, occurred_at, kind, field, old_value, new_value)
  SELECT i.id, i.reporter_id, i.created_at, 'created', NULL, NULL,
         jsonb_build_object('type', i.type, 'summary', i.summary)
  FROM issue i WHERE i.workspace_id = pg_temp.seed_id('workspace')
  ORDER BY i.created_at;

  INSERT INTO issue_event (issue_id, actor_id, occurred_at, kind, field, old_value, new_value)
  SELECT i.id, coalesce(i.assignee_id, i.reporter_id), i.updated_at, 'transitioned', 'status',
         jsonb_build_object('id', first_status.id, 'name', first_status.name), jsonb_build_object('id', now_in.id, 'name', now_in.name)
  FROM issue i
  JOIN workflow_status now_in ON now_in.id = i.status_id
  JOIN workflow_status first_status ON first_status.project_id = i.project_id AND first_status.position = 0
  WHERE i.workspace_id = pg_temp.seed_id('workspace') AND now_in.position > 0
  ORDER BY i.updated_at;

  -- BLOCK 10: who watches, viewed and starred what
  INSERT INTO watcher (issue_id, user_id)
  SELECT id, reporter_id FROM issue WHERE workspace_id = pg_temp.seed_id('workspace')
  UNION
  SELECT id, assignee_id FROM issue WHERE workspace_id = pg_temp.seed_id('workspace') AND assignee_id IS NOT NULL;

  INSERT INTO issue_view (user_id, issue_id, viewed_at)
  SELECT pg_temp.seed_id('user:ada'), pg_temp.seed_id('MAR:issue:' || n), now() - make_interval(mins => n)
  FROM generate_series(24, 33) AS n;

  INSERT INTO issue_star (user_id, issue_id) VALUES
    (pg_temp.seed_id('user:ada'), pg_temp.seed_id('MAR:issue:24')),
    (pg_temp.seed_id('user:ada'), pg_temp.seed_id('MAR:issue:31')),
    (pg_temp.seed_id('user:ada'), pg_temp.seed_id('BUG:issue:1'));

  INSERT INTO project_visit (user_id, project_id)
  SELECT pg_temp.seed_id('user:ada'), pg_temp.seed_id('project:' || key) FROM (VALUES ('MAR'), ('OPS'), ('BUG')) AS p (key);
END
$seed$;
