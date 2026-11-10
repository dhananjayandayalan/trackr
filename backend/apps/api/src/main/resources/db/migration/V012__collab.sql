-- Collaborative description editing (02-data-model §5, 06-collab, phase 16). The api owns the schema; the collab
-- app is the only writer of these two tables, the api never reads them (it gets text and HTML from Kafka).

-- BLOCK 1: collab_update — one Yjs update per row, exactly the bytes a browser sent. The server never decodes
-- them: it stores, replays to newcomers and relays. Rows only live until the next snapshot covers them.
CREATE TABLE collab_update (
  -- the order updates arrived in; a snapshot says "everything up to this id is inside me"
  id bigint GENERATED ALWAYS AS IDENTITY,
  issue_id uuid NOT NULL,
  update bytea NOT NULL,
  -- the sender's Y.Doc client id, as its awareness announced it; null when the update came before any awareness
  client_id bigint,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_collab_update PRIMARY KEY (id),
  CONSTRAINT fk_collab_update_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id),
  -- 06-collab §5: one frame is at most 1 MiB, so one update is too (the socket enforces it first)
  CONSTRAINT ck_collab_update_size CHECK (octet_length(update) <= 1048576)
);

-- "everything for this issue after the snapshot, in order": every join, every reconnect
CREATE INDEX ix_collab_update_issue_id_id ON collab_update (issue_id, id);

-- BLOCK 2: collab_snapshot — the whole document as one update (Y.encodeStateAsUpdate), one row per issue
CREATE TABLE collab_snapshot (
  issue_id uuid NOT NULL,
  state bytea NOT NULL,
  -- the last collab_update id folded into state; rows with id <= this one are deleted in the same transaction
  upto_update_id bigint NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_collab_snapshot PRIMARY KEY (issue_id),
  CONSTRAINT fk_collab_snapshot_issue_id FOREIGN KEY (issue_id) REFERENCES issue (id)
);
