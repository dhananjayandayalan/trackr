-- BLOCK 1: board — one per project in v1
CREATE TABLE board (
  id uuid NOT NULL,
  project_id uuid NOT NULL,
  name text NOT NULL,
  type text NOT NULL,
  CONSTRAINT pk_board PRIMARY KEY (id),
  CONSTRAINT fk_board_project_id FOREIGN KEY (project_id) REFERENCES project (id),
  -- one board per project; also the index for "the board of project X"
  CONSTRAINT uq_board_project_id UNIQUE (project_id),
  CONSTRAINT ck_board_type CHECK (type IN ('scrum', 'kanban'))
);

-- BLOCK 2: board_column — a column shows one or more workflow statuses
CREATE TABLE board_column (
  id uuid NOT NULL,
  board_id uuid NOT NULL,
  name text NOT NULL,
  position int NOT NULL,
  -- an array, not a join table: the board reads all columns with their statuses in one row each
  status_ids uuid[] NOT NULL,
  wip_limit int,
  CONSTRAINT pk_board_column PRIMARY KEY (id),
  CONSTRAINT fk_board_column_board_id FOREIGN KEY (board_id) REFERENCES board (id),
  CONSTRAINT ck_board_column_name CHECK (char_length(name) BETWEEN 1 AND 30),
  CONSTRAINT ck_board_column_position CHECK (position >= 0),
  CONSTRAINT ck_board_column_wip_limit CHECK (wip_limit > 0)
);

-- "the columns of board X", read every time a board opens
CREATE INDEX ix_board_column_board_id ON board_column (board_id);
