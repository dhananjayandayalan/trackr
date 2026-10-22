# Phase 03 · First Spring Boot app (api)

**Goal:** `apps/api` starts, serves Actuator health, runs requests on virtual threads, and you understand what Spring Boot did on startup.
**Spec:** 01-architecture §2, §7; 11-infra §7. **Time:** 1 day.

## Concepts you'll learn

- **`@SpringBootApplication`** = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`.
- **Auto-configuration** — beans created because a class is on the classpath and a condition matched (`@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`).
- **Externalised configuration** — property sources and their order (defaults < `application.yml` < profile yml < env vars < command line).
- **Profiles** — `dev`, `test`, `prod`.
- **`@ConfigurationProperties` records** — typed config instead of `@Value` strings.
- **Virtual threads** — one cheap thread per request; blocking I/O no longer wastes a platform thread.
- **Actuator** — health, info, metrics, conditions report.

## Files in this phase

1. `apps/api/build.gradle.kts` (dependencies)
2. `apps/api/src/main/java/dev/trakr/api/ApiApplication.java`
3. `apps/api/src/main/resources/application.yml`
4. `apps/api/src/main/resources/application-dev.yml`
5. `apps/api/src/main/java/dev/trakr/api/shared/config/TrakrProperties.java`
6. `apps/api/src/main/java/dev/trakr/api/shared/config/TimeConfig.java`
7. `apps/api/src/main/java/dev/trakr/api/shared/web/DiagnosticsController.java` (dev profile only, deleted in phase 18)
8. `apps/api/src/test/java/dev/trakr/api/ApiApplicationTests.java`

### File 1 — `build.gradle.kts`

| #   | Cue                                  | What                                                                                                                                             |
| --- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | `// BLOCK 1: plugins`                | `id("trakr.spring-app")`                                                                                                                         |
| 2   | `// BLOCK 2: web + ops`              | Web MVC starter, Actuator starter, Validation starter (add each to the catalog first)                                                            |
| 3   | `// BLOCK 3: our libs`               | `implementation(project(":libs:contracts"))`                                                                                                     |
| 4   | `// BLOCK 4: tests`                  | `testImplementation` Spring Boot test starter                                                                                                    |
| 5   | `// BLOCK 5: configuration metadata` | `annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")` — gives IDE autocomplete for your own `trakr.*` properties |

### File 2 — `ApiApplication.java`

| #   | Cue                                 | What                                                                              |
| --- | ----------------------------------- | --------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: the application class` | `@SpringBootApplication @ConfigurationPropertiesScan public class ApiApplication` |
| 2   | `// BLOCK 2: main`                  | `SpringApplication.run(ApiApplication.class, args)`                               |

**Ask yourself:** which package does component scanning start from, and why does that make the package layout important?

### File 3 — `application.yml`

| #   | Cue                         | What                                                                                                                                                                                |
| --- | --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `# BLOCK 1: identity`       | `spring.application.name: trakr-api`, `spring.profiles.default: dev`                                                                                                                |
| 2   | `# BLOCK 2: threads`        | `spring.threads.virtual.enabled: true`                                                                                                                                              |
| 3   | `# BLOCK 3: server`         | `server.port: 8080`, `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 20s`, `server.compression.enabled: true`, `spring.mvc.problemdetails.enabled: true` |
| 4   | `# BLOCK 4: actuator`       | expose `health,info,metrics,prometheus,conditions` over web; `management.endpoint.health.probes.enabled: true`; `show-details: when-authorized`                                     |
| 5   | `# BLOCK 5: our properties` | `trakr.app.origin: ${TRAKR_APP_ORIGIN:http://localhost:5173}`                                                                                                                       |

### File 4 — `application-dev.yml`

`logging.level.dev.trakr: debug`, `management.endpoint.health.show-details: always`, `management.endpoints.web.exposure.include: "*"` (dev only — explain the risk in prod).

### File 5 — `TrakrProperties.java`

`@ConfigurationProperties("trakr") public record TrakrProperties(App app) { public record App(URI origin) {} }` with `@Validated` and `@NotNull`. **Check:** start with an invalid origin → startup fails with a clear binding error. That's the point of typed config.

### File 6 — `TimeConfig.java`

`@Configuration(proxyBeanMethods = false)` with `@Bean Clock clock() { return Clock.systemUTC(); }`. Every class that needs "now" injects `Clock` → tests can use `Clock.fixed(...)`.
**Ask yourself:** what does `proxyBeanMethods = false` turn off, and why is it safe here?

### File 7 — `DiagnosticsController.java`

`@Profile("dev") @RestController @RequestMapping("/api/v1/_diag")`; `GET /thread` returns `{name, virtual: Thread.currentThread().isVirtual()}`.
**Check:** `curl localhost:8080/api/v1/_diag/thread` → `"virtual": true`. Then set `spring.threads.virtual.enabled=false` via env var `SPRING_THREADS_VIRTUAL_ENABLED=false`, restart, call again → `false`. You just proved the property order.

### File 8 — `ApiApplicationTests.java`

| #   | What                                                                                                      |
| --- | --------------------------------------------------------------------------------------------------------- |
| 1   | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureMockMvc`                                 |
| 2   | test `contextLoads()`                                                                                     |
| 3   | test `healthIsUp()` using `MockMvcTester` (`assertThat(mvc.get().uri("/actuator/health")).hasStatusOk()`) |

## Explore (do these, write findings in the log)

1. Run with `--debug` and read the **conditions evaluation report**: find `DataSourceAutoConfiguration` in "negative matches" — why didn't it activate?
2. Open `http://localhost:8080/actuator/conditions` and `…/actuator/beans`. Count your beans vs Spring's.
3. Read the startup log line by line; identify the embedded server, the port and the active profile.

## Done when

- `./gradlew :apps:api:bootRun` starts in < 3 s; health is UP; diag shows virtual threads.
- Tests pass. You can explain auto-configuration with one example from the conditions report.

Commit: `phase-03: first spring boot app`.
