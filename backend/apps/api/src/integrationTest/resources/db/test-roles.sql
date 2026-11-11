-- Runs once when the test Postgres container starts, as its superuser, before Flyway: the same three login roles
-- infra/postgres/init creates for dev. V014 then gives them their grants, and the app connects as its own role
-- while Flyway and the tests' fixtures use the owner (TestcontainersConfiguration).
CREATE ROLE trakr_api LOGIN PASSWORD 'trakr_api';
CREATE ROLE trakr_workers LOGIN PASSWORD 'trakr_workers';
CREATE ROLE trakr_collab LOGIN PASSWORD 'trakr_collab';
