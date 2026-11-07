-- Version 8.2. A board card carries the issue's id (03-api §3.3 Card), and V007's ix_issue_board did not
-- include it: every card would cost a visit to the table row ("Index Scan"), not just the index
-- ("Index Only Scan"). Rebuild it with id among the INCLUDE columns.

-- [jooq ignore start]
DROP INDEX ix_issue_board;

-- board columns and backlog lists: "issues of project P in sprint S with status X, in rank order";
-- every card field is in the index, so the board query never reads the table itself
CREATE INDEX ix_issue_board ON issue (project_id, sprint_id, status_id, rank)
  INCLUDE (id, key, summary, type, priority, assignee_id, story_points, parent_id, flagged, version)
  WHERE deleted_at IS NULL;
-- [jooq ignore stop]
