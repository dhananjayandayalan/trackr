-- Version 8.1: between phase 07's V008 and phase 09's V009, which no database has applied yet.
-- What a sprint committed to when it started. The sprint report (committed / added / removed points)
-- compares the end of the sprint with this; the report projector that could recompute it from history
-- only arrives in phase 10, and a snapshot taken at the moment that matters is simpler to trust.

-- BLOCK 1: the commitment, written once by Sprint.start
ALTER TABLE sprint
  ADD COLUMN started_at timestamptz,
  ADD COLUMN committed_points numeric(6, 1),
  -- the issues in the sprint at start; an array because it is only ever read whole, with the sprint
  ADD COLUMN committed_issue_ids uuid[];

ALTER TABLE sprint
  ADD CONSTRAINT ck_sprint_committed_when_started CHECK (state = 'planned' OR started_at IS NOT NULL);
