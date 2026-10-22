# Phase 02 · Gradle from scratch

**Goal:** a multi-project Gradle build with a version catalog and your own convention plugins, plus the first real code: `Ids` (UUIDv7) with tests.
**Spec:** 01-architecture §3, §8. **Time:** 1–2 days.

## Concepts you'll learn

- **Settings vs build scripts** — `settings.gradle.kts` decides _which_ projects exist; `build.gradle.kts` decides _how_ each is built.
- **Included builds** — `build-logic` is a separate build whose output (plugins) your main build consumes.
- **Precompiled script plugins** — a `trakr.java-conventions.gradle.kts` file becomes a plugin with id `trakr.java-conventions`.
- **Version catalog** — `libs.versions.toml` → type-safe accessors like `libs.spring.boot.starter.webmvc`.
- **Java toolchains** — Gradle downloads/uses JDK 25 regardless of the JDK running Gradle.
- **Dependency configurations** — `implementation`, `api` (needs `java-library`), `compileOnly`, `runtimeOnly`, `testImplementation`, `annotationProcessor`.
- **BOM / platform** — one place controls the versions of a whole family (Spring Boot's dependency management).
- **Configuration cache & build cache** — why `org.gradle.configuration-cache=true` makes builds fast and what it forbids.

## Files in this phase (in order)

1. `backend/gradle.properties`
2. `backend/settings.gradle.kts`
3. `backend/gradle/libs.versions.toml`
4. `backend/build-logic/settings.gradle.kts`
5. `backend/build-logic/build.gradle.kts`
6. `backend/build-logic/src/main/kotlin/trakr.java-conventions.gradle.kts`
7. `backend/build-logic/src/main/kotlin/trakr.spring-app.gradle.kts`
8. `backend/libs/kernel/build.gradle.kts`
9. `backend/libs/kernel/src/main/java/dev/trakr/kernel/Ids.java`
10. `backend/libs/kernel/src/test/java/dev/trakr/kernel/IdsTest.java`
11. `backend/libs/contracts/build.gradle.kts` (empty module for now)
12. Gradle wrapper (generated): `gradle wrapper --gradle-version <9.x>` inside `backend/`

### File 1 — `gradle.properties`

| #   | Cue                                 | What                                                                                                                                         |
| --- | ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `# BLOCK 1: build performance`      | `org.gradle.configuration-cache=true`, `org.gradle.caching=true`, `org.gradle.parallel=true`, `org.gradle.jvmargs=-Xmx2g -XX:+UseParallelGC` |
| 2   | `# BLOCK 2: toolchain provisioning` | allow auto-download of toolchains (`org.gradle.java.installations.auto-download=true`)                                                       |

### File 2 — `settings.gradle.kts`

| #   | Cue                                        | What it must do                                                                                                                         |
| --- | ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: where plugins come from`      | `pluginManagement { includeBuild("build-logic"); repositories { gradlePluginPortal(); mavenCentral() } }`                               |
| 2   | `// BLOCK 2: where dependencies come from` | `dependencyResolutionManagement { repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS; repositories { mavenCentral() } }`         |
| 3   | `// BLOCK 3: toolchain resolver`           | apply the `org.gradle.toolchains.foojay-resolver-convention` settings plugin (so Gradle can download JDK 25)                            |
| 4   | `// BLOCK 4: the projects`                 | `rootProject.name = "trakr"`; `include("libs:kernel", "libs:contracts", "apps:api")` (workers and collab are added in phases 10 and 16) |

**Ask yourself:** what does `FAIL_ON_PROJECT_REPOS` protect you from?

### File 3 — `libs.versions.toml`

Write four sections, one block each: `[versions]`, `[libraries]`, `[bundles]`, `[plugins]`.

- `[versions]`: `java = "25"`, `springBoot`, `springModulith`, `springdoc`, `jooq`, `flyway`, `testcontainers`, `elasticsearch`, `awsSdk`, `antlr`, `owaspSanitizer`. Look up the **current exact versions** on Maven Central / the Spring site and pin them.
- `[libraries]`: start with only what this phase needs — `spring-boot-dependencies` (BOM), `spring-modulith-bom`, `junit-bom`, `assertj-core`, `spring-boot-gradle-plugin` and `dependency-management` plugin as _libraries_ (build-logic needs them on its classpath). Add others in their phases; each addition is its own mini-block with a one-line "why".
- `[plugins]`: `spring-boot`, `jooq-codegen` (phase 04), `antlr` is built in (no entry).
  **Watch out:** Copilot will suggest Boot 3 artifact names — check every starter name against the Boot 4.1 reference.

### File 4 — `build-logic/settings.gradle.kts`

Blocks: reuse the main catalog (`dependencyResolutionManagement { versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } } }`), repositories, `rootProject.name = "build-logic"`.

### File 5 — `build-logic/build.gradle.kts`

| #   | What                                                                                                                           |
| --- | ------------------------------------------------------------------------------------------------------------------------------ |
| 1   | `plugins { \`kotlin-dsl\` }` — enables precompiled script plugins                                                              |
| 2   | `dependencies { implementation(libs.spring.boot.gradle.plugin) }` — so convention plugins can apply `org.springframework.boot` |

**Ask yourself:** why does the plugin go in `implementation` here rather than in a `plugins {}` block?

### File 6 — `trakr.java-conventions.gradle.kts`

| #   | Cue                                          | What it must do                                                                                                                                                                                                               |
| --- | -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: plugins every Java module gets` | `java-library`, `jacoco`                                                                                                                                                                                                      |
| 2   | `// BLOCK 2: toolchain + compiler flags`     | Java 25 toolchain; `options.compilerArgs += listOf("-parameters", "-Xlint:all,-processing", "-Werror")`; `options.encoding = "UTF-8"`                                                                                         |
| 3   | `// BLOCK 3: shared dependencies`            | `implementation(platform(libs.spring.boot.dependencies))`, `testImplementation(platform(libs.junit.bom))`, JUnit Jupiter, AssertJ, `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`, JSpecify `compileOnlyApi` |
| 4   | `// BLOCK 4: test task`                      | `tasks.test { useJUnitPlatform(); jvmArgs("-XX:+EnableDynamicAgentLoading"); testLogging { events("failed"); exceptionFormat = FULL } }`                                                                                      |
| 5   | `// BLOCK 5: reproducible archives`          | `tasks.withType<AbstractArchiveTask> { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }`                                                                                                                    |

Note: in a precompiled script plugin the `libs` accessor is not generated automatically — use `val libs = the<VersionCatalogsExtension>().named("libs")` and `libs.findLibrary("…").get()`. This is a classic Gradle head-scratcher; write it down.
**Ask yourself:** what does `-parameters` give Spring (hint: `@PathVariable` names)?

### File 7 — `trakr.spring-app.gradle.kts`

| #   | What                                                                                                                                                                                                       |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Apply `id("trakr.java-conventions")` and `id("org.springframework.boot")`                                                                                                                                  |
| 2   | `springBoot { buildInfo() }` — exposes build info at `/actuator/info`                                                                                                                                      |
| 3   | Add the Spring Modulith BOM as a platform, plus `developmentOnly`/`testAndDevelopmentOnly` configurations for `spring-boot-docker-compose` (optional, explain what it does and decide whether you want it) |
| 4   | `tasks.bootJar { archiveFileName = "${project.name}.jar" }`                                                                                                                                                |

### File 8 — `libs/kernel/build.gradle.kts`

`plugins { id("trakr.java-conventions") }` and nothing else. Notice how small a module becomes once conventions exist.

### File 9 — `Ids.java` (UUIDv7, RFC 9562)

| #   | Cue                             | What it must do                                                                                                                                                                                                                                                                |
| --- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | `// BLOCK 1: class shape`       | `public final class Ids` with a private constructor, a `SecureRandom`, and state for monotonicity (`lastMillis`, `counter`) guarded by a lock (`ReentrantLock` — not `synchronized`, which pins virtual threads in some cases; check the JDK 25 status and note what you find) |
| 2   | `// BLOCK 2: newId()`           | 48-bit Unix ms timestamp in the top bits; version nibble `0111`; 12-bit `rand_a` used as a counter when several ids are made in the same millisecond; variant bits `10`; 62 random bits. Build two `long`s with shifts and masks → `new UUID(msb, lsb)`                        |
| 3   | `// BLOCK 3: timestampOf(UUID)` | Extracts the millis (used in tests and debugging)                                                                                                                                                                                                                              |

**Check (File 10 tests):** version() == 7, variant() == 2, 10 000 ids generated in a loop are strictly increasing when compared as unsigned (UUID.compareTo is signed — test with `toString()` ordering or compare msb unsigned), timestampOf ≈ now.
**Ask yourself:** why are time-ordered ids better for a B-tree primary key than random UUIDv4?

### File 11 — `libs/contracts/build.gradle.kts`

`plugins { id("trakr.java-conventions") }`, `dependencies { api(project(":libs:kernel")) }`. Explain `api` vs `implementation` here: apps that depend on contracts also see kernel types.

### apps/api placeholder

`apps/api/build.gradle.kts` with `plugins { id("trakr.spring-app") }` — the app class comes in phase 03.

## Run

```
cd backend
gradle wrapper --gradle-version <9.x>   # once; afterwards always ./gradlew
./gradlew projects
./gradlew :libs:kernel:test
./gradlew build --configuration-cache
```

## Done when

- `./gradlew build` passes twice; the second run says `Reusing configuration cache` and most tasks are `UP-TO-DATE`/`FROM-CACHE`.
- IdsTest passes.
- You can draw the build: settings → included build-logic → convention plugins → modules.

Commit: `phase-02: gradle build, conventions, UUIDv7`.
