-- BLOCK 1: extensions. IF NOT EXISTS because the postgres init script may already have created them
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
