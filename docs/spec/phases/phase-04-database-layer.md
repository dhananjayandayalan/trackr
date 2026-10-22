# Phase 04 · Database layer: Flyway, JPA, jOOQ, Testcontainers

**Goal:** the api connects to Postgres, Flyway owns the schema, jOOQ classes are generated from the migrations, JPA is configured safely, and integration tests run against a real Postgres in a container.
**Spec:** 02-data-model §1–3, 10-testing. **Time:** 2 days.

## Concepts you'll learn

- **Connection pool (HikariCP)** — why apps reuse a few connections instead of opening one per request; how pool size relates to virtual threads (the pool, not threads, is now the bottleneck).
- **Schema migrations** — versioned, immutable, repeatable deploys.
- **JPA persistence context** — first-level cache, dirty checking, flush.
- **Open Session in View** — why we turn it off.
- **jOOQ code generation** — Java classes mirroring tables → compile-time-checked SQL.
- **Testcontainers + `@ServiceConnection`** — real dependencies in tests, wired automatically.
- **JVM Test Suites** — separate `test` and `integrationTest` source sets.

## Files in this phase

1. `gradle/libs.versions.toml` — add: postgresql driver, Data JPA starter, jOOQ starter, Flyway (core + `flyway-database-postgresql`), Testcontainers BOM + `postgresql` + `junit-jupiter`, `spring-boot-testcontainers`, jOOQ codegen plugin + `jooq-meta-extensions`
2. `build-logic/…/trakr.java-conventions.gradle.kts` — add the `integrationTest` suite
3. `apps/api/build.gradle.kts` — persistence deps + jOOQ codegen configuration
4. `apps/api/src/main/resources/db/migration/V001__extensions.sql`
5. `…/V002__workspace_and_users.sql`
6. `application.yml` + `application-dev.yml` — datasource, JPA, Flyway, jOOQ blocks
7. `apps/api/src/integrationTest/java/dev/trakr/api/TestcontainersConfiguration.java`
8. `apps/api/src/integrationTest/java/dev/trakr/api/DatabaseSmokeIT.java`
9. `apps/api/src/test/java/dev/trakr/api/TestApiApplication.java` (run the app locally with containers — optional)

### File 2 — integrationTest suite (in conventions)

| #   | Cue                                  | What                                                                                                                                                                                                                                           |
| --- | ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 6: integration test suite` | `testing { suites { val test by getting(JvmTestSuite::class) { useJUnitJupiter() }; register<JvmTestSuite>("integrationTest") { dependencies { implementation(project()) }; targets.all { testTask.configure { shouldRunAfter(test) } } } } }` |
| 2   | `// BLOCK 7: wire into check`        | `tasks.named("check") { dependsOn(testing.suites.named("integrationTest")) }` and let the suite extend `testImplementation`                                                                                                                    |

**Ask yourself:** why keep slow container tests out of the default `test` task?

### File 3 — api build: jOOQ codegen from SQL files

| #   | Cue                                    | What                                                                                                                                                                                                                                                                                                                                                                                                   |
| --- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | `// BLOCK 6: persistence dependencies` | JPA starter, jOOQ starter, Flyway core + postgres module, `runtimeOnly` postgresql                                                                                                                                                                                                                                                                                                                     |
| 2   | `// BLOCK 7: jOOQ codegen plugin`      | apply `org.jooq.jooq-codegen-gradle`; `jooqCodegen(libs.jooq.meta.extensions)`                                                                                                                                                                                                                                                                                                                         |
| 3   | `// BLOCK 8: codegen configuration`    | `jooq { configuration { generator { database { name = "org.jooq.meta.extensions.ddl.DDLDatabase"; properties { property { key = "scripts"; value = "src/main/resources/db/migration/*.sql" }; property { key = "sort"; value = "flyway" }; property { key = "defaultNameCase"; value = "lower" } } }; target { packageName = "dev.trakr.api.jooq"; directory = "build/generated-sources/jooq" } } } }` |
| 4   | `// BLOCK 9: compile after codegen`    | add the generated dir to `sourceSets.main`, `tasks.compileJava { dependsOn(tasks.named("jooqCodegen")) }`, declare migration files as task inputs so codegen re-runs only when SQL changes                                                                                                                                                                                                             |

**Watch out:** DDLDatabase _parses_ SQL with jOOQ's parser instead of running Postgres. Postgres-only statements it can't parse (RLS policies, some index options) go between `-- [jooq ignore start]` and `-- [jooq ignore stop]`. Write down every place you need that.

### Files 4–5 — the first migrations (write from 02-data-model)

| File | Blocks                                                                                                                                    |
| ---- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| V001 | 1 extensions (`citext`, `pg_trgm` — `IF NOT EXISTS`, because the init script may already have created them)                               |
| V002 | 1 `workspace` · 2 `app_user` (+ unique email) · 3 `user_identity` (+ unique provider/subject) · 4 `workspace_member` · 5 `password_reset` |

After each `CREATE TABLE`, explain each constraint to yourself (what bad data does it make impossible?).

### File 6 — configuration blocks

| #   | Cue                           | What                                                                                                                                                                                    |
| --- | ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `# BLOCK 6: datasource` (dev) | url/username/password from 11-infra; `spring.datasource.hikari.maximum-pool-size: 20`, `connection-timeout: 3s`, `pool-name: trakr-api`                                                 |
| 2   | `# BLOCK 7: jpa`              | `open-in-view: false`, `hibernate.ddl-auto: validate`, `properties.hibernate.jdbc.batch_size: 50`, `order_inserts/updates: true`, `default_batch_fetch_size: 50`, `jdbc.time_zone: UTC` |
| 3   | `# BLOCK 8: flyway`           | `spring.flyway.locations: classpath:db/migration`, `validate-on-migrate: true`, `clean-disabled: true`                                                                                  |
| 4   | `# BLOCK 9: jooq`             | `spring.jooq.sql-dialect: postgres`                                                                                                                                                     |

**Ask yourself:** with virtual threads, 10 000 concurrent requests can be in flight — what happens when they all need one of 20 connections? (Answer: they wait on the pool; size it from the DB's capacity, not the request rate.)

### File 7 — `TestcontainersConfiguration.java`

| #   | What                                                                                                      |
| --- | --------------------------------------------------------------------------------------------------------- |
| 1   | `@TestConfiguration(proxyBeanMethods = false)`                                                            |
| 2   | `@Bean @ServiceConnection PostgreSQLContainer<?> postgres()` with image `postgres:18`, `.withReuse(true)` |

Kafka, Redis and Elasticsearch containers are added to this same class in their phases.

### File 8 — `DatabaseSmokeIT.java`

| #   | What                                                                                                             |
| --- | ---------------------------------------------------------------------------------------------------------------- |
| 1   | `@SpringBootTest @Import(TestcontainersConfiguration.class)`                                                     |
| 2   | `flywayAppliedAllMigrations()` — inject `Flyway`, assert `info().applied().length == 2`                          |
| 3   | `jooqCanInsertAndReadAWorkspace()` — `dsl.insertInto(WORKSPACE).set(...)` then select it back; use `Ids.newId()` |
| 4   | `citextMakesEmailUnique()` — insert `A@x.com` then `a@x.com` → expect `DuplicateKeyException`                    |

## Run

```
docker compose -f ../infra/docker-compose.yml up -d --wait postgres
./gradlew :apps:api:jooqCodegen     # look at build/generated-sources/jooq — open Workspace.java and read it
./gradlew :apps:api:bootRun         # Flyway migrates the dev DB on startup
./gradlew :apps:api:integrationTest
```

## Done when

- Startup logs show Flyway applying V001–V002 and Hibernate validating (no entities yet — fine).
- Integration tests are green using a container, not your dev DB.
- You can explain Open Session in View and why it's off.

Commit: `phase-04: database layer`.
