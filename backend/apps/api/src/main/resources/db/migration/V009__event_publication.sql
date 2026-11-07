-- The outbox: Spring Modulith's event publication registry, JDBC flavour.
-- Copied verbatim from spring-modulith-events-jdbc-2.1.2.jar, org/springframework/modulith/events/jdbc/schemas/v2/
-- schema-postgresql.sql (v2 = the 2.x structure with status and resubmission columns; no
-- spring.modulith.events.jdbc.use-legacy-structure). Modulith writes and reads these columns by name: don't
-- rename or add any, and re-copy this file from the jar when upgrading Modulith.
-- Flyway owns the table, so spring.modulith.events.jdbc.schema-initialization.enabled stays false.
-- The archive table of the same file is left out: it is only used with completion-mode=archive.

-- BLOCK 1: one row per (event, listener): written in the business transaction, completed when the listener
-- (here: the Kafka externalizer, after the broker's ack) is done with it
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);

-- BLOCK 2: Modulith's indexes: find a publication by its serialized event when marking it completed, and
-- find old completed ones for cleanup. Hash indexes are Postgres-only, so jOOQ's H2 codegen skips both.
-- [jooq ignore start]
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
-- [jooq ignore stop]
