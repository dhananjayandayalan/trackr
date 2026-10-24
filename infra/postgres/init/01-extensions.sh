#!/bin/sh
set -eu

: "${TRAKR_API_DB_PASSWORD:?TRAKR_API_DB_PASSWORD must be set}"
: "${TRAKR_WORKERS_DB_PASSWORD:?TRAKR_WORKERS_DB_PASSWORD must be set}"
: "${TRAKR_COLLAB_DB_PASSWORD:?TRAKR_COLLAB_DB_PASSWORD must be set}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

\getenv api_db_password TRAKR_API_DB_PASSWORD
\getenv workers_db_password TRAKR_WORKERS_DB_PASSWORD
\getenv collab_db_password TRAKR_COLLAB_DB_PASSWORD

CREATE ROLE trakr_api LOGIN PASSWORD :'api_db_password';
CREATE ROLE trakr_workers LOGIN PASSWORD :'workers_db_password';
CREATE ROLE trakr_collab LOGIN PASSWORD :'collab_db_password';
SQL
