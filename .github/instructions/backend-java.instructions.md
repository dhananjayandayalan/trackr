---
applyTo: "backend/**/*.java"
---

# Backend Java rules (learning mode applies — one block at a time, then explain)

## Structure
- `apps/api` is a Spring Modulith application. Each top-level package under `dev.trakr.api` is a module: `identity`, `workspace`, `project`, `workflow`, `issue`, `comment`, `attachment`, `board`, `sprint`, `search`, `reporting`, `notification`, `realtime`, `home`. `shared` holds cross-cutting web/config code only.
- A module exposes types only from its root package. Anything in `<module>.internal` is private to that module. Other modules may call the root-package service interface or listen to its events — never reach into `internal`.
- Shared contracts (event payloads, the envelope, `Ids`) live in `libs/contracts` (`dev.trakr.contracts`) and `libs/kernel` (`dev.trakr.kernel`).

## Layers inside a module
- `web` — `@RestController` + request/response `record`s. No business logic, no repositories.
- root package — the public `*Service` (application service): transactions, authorization checks, publishing domain events.
- `internal/domain` — JPA entities and domain logic (state changes as methods on the entity, e.g. `issue.moveTo(status)`).
- `internal/persistence` — Spring Data repositories (writes) and jOOQ query classes (reads, named `*Queries`).

## Rules
- Constructor injection only. No field `@Autowired`.
- DTOs are Java `record`s. Validate requests with Jakarta Bean Validation on the record components.
- `@Transactional` lives on service methods, never on controllers.
- Entities carry `@Version long version` for optimistic locking. PATCH endpoints require `If-Match`.
- Publish domain events with `ApplicationEventPublisher` inside the transaction. Modulith's event publication registry persists them; Kafka externalization happens through `@Externalized` (see `docs/spec/04-events.md`).
- Reads that join several tables use jOOQ and return records straight to the controller. Do not load JPA entity graphs for list screens.
- Use `java.time`, `Optional` only as a return type, no `null` collections.
- Logging: SLF4J with structured key=value pairs, no string concatenation.
- Every service method gets at least one test. Integration tests use Testcontainers (`@ServiceConnection`).

## Explain these concepts when they first appear
Auto-configuration, bean lifecycle, proxies (`@Transactional` only works through the proxy), persistence context and dirty checking, optimistic locking, the outbox pattern, idempotent consumers, virtual threads.
