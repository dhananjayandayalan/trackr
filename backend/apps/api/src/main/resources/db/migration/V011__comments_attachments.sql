-- Comments and attachments (02-data-model §3, phase 15). Two new modules own these tables: comment and attachment.

-- BLOCK 1: comment — the body is stored twice: sanitised HTML for display, plain text for search and emails
CREATE TABLE comment (
  id uuid NOT NULL,
  issue_id uuid NOT NULL,
  author_id uuid NOT NULL,
  body_html text NOT NULL,
  body_text text NOT NULL,
  -- the optimistic lock behind ETag/If-Match (03-api §1: every mutable resource has one); also the event sequence
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  edited_at timestamptz,
  deleted_at timestamptz,
  CONSTRAINT pk_comment PRIMARY KEY (id),
  CONSTRAINT fk_comment_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id),
  CONSTRAINT fk_comment_author_id FOREIGN KEY (author_id) REFERENCES app_user (id),
  -- 09-security §4: comments up to 20 kB (the api checks the request first; this is the last line)
  CONSTRAINT ck_comment_body_html CHECK (char_length(body_html) <= 20480)
);

-- [jooq ignore start]
-- the Comments tab: an issue's live comments, newest first (ids are UUIDv7, so id order is time order)
CREATE INDEX ix_comment_issue_id_id ON comment (issue_id, id DESC) WHERE deleted_at IS NULL;
-- [jooq ignore stop]

-- BLOCK 2: comment_mention — who a comment mentions, resolved to user ids when it was saved (00-product §4.9)
CREATE TABLE comment_mention (
  comment_id uuid NOT NULL,
  user_id uuid NOT NULL,
  CONSTRAINT pk_comment_mention PRIMARY KEY (comment_id, user_id),
  CONSTRAINT fk_comment_mention_comment_id FOREIGN KEY (comment_id) REFERENCES comment (id),
  CONSTRAINT fk_comment_mention_user_id FOREIGN KEY (user_id) REFERENCES app_user (id)
);

-- "where was I mentioned?": a user's mentions
CREATE INDEX ix_comment_mention_user_id ON comment_mention (user_id);

-- BLOCK 3: attachment — the bytes live in S3 (SeaweedFS); the row is what we know about them
CREATE TABLE attachment (
  id uuid NOT NULL,
  issue_id uuid NOT NULL,
  uploader_id uuid NOT NULL,
  -- ws/{workspaceId}/issues/{issueId}/{uuid}/{fileName}: only the file name part comes from the user
  object_key text NOT NULL,
  file_name text NOT NULL,
  content_type text NOT NULL,
  size_bytes bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  CONSTRAINT pk_attachment PRIMARY KEY (id),
  CONSTRAINT fk_attachment_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id),
  CONSTRAINT fk_attachment_uploader_id FOREIGN KEY (uploader_id) REFERENCES app_user (id),
  -- 25 MB (09-security §4)
  CONSTRAINT ck_attachment_size_bytes CHECK (size_bytes > 0 AND size_bytes <= 26214400),
  CONSTRAINT ck_attachment_file_name CHECK (char_length(file_name) BETWEEN 1 AND 120)
);

-- [jooq ignore start]
-- one row per object: confirming the same upload twice fails here instead of listing the file twice
ALTER TABLE attachment ADD CONSTRAINT uq_attachment_object_key UNIQUE (object_key);
-- [jooq ignore stop]

-- the issue page's attachment list
CREATE INDEX ix_attachment_issue_id_created_at ON attachment (issue_id, created_at);
