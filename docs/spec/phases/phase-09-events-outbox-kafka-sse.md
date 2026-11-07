# Phase 09 · Events: outbox → Kafka → Server-Sent Events

**Goal:** every domain event is stored in the outbox in the same transaction, published to Kafka in a CloudEvents envelope, consumed back by every api instance and streamed to browsers over SSE with replay on reconnect.
**Spec:** 04-events (all), 03-api §4, 01-architecture §5.1, §6. **Time:** 4–5 days.

## Concepts you'll learn

- **Transactional outbox** and why "save then send" without it loses or invents events.
- **Event publication registry** (Spring Modulith) — completed vs incomplete publications, republish on restart.
- **Kafka producer guarantees** — `acks=all`, idempotent producer, keys → partitions → ordering.
- **Consumer groups** — shared group = work split; unique group per instance = broadcast.
- **CloudEvents** — a standard envelope.
- **SSE** — `text/event-stream`, `id:`/`event:`/`data:` lines, `Last-Event-ID`, heartbeats.
- **Redis Streams** — `XADD … MAXLEN ~`, `XRANGE` for replay.

## Files in this phase

1. `libs/contracts`: `TrakrEvent.java` (sealed interface), `EventEnvelope.java`, `Topics.java` (constants), `EventTypes.java` (type strings), all payload records grouped by topic (`IssueEvent` sub-interface etc.)
2. Catalog/build: `spring-modulith-events-kafka`, Spring Kafka (comes with it), Testcontainers `kafka`
3. `V009__event_publication.sql` — copy the exact DDL for your Modulith version's JPA/JDBC event publication table from the Modulith reference (do not invent columns)
4. `shared/events/EventExternalizationConfig.java`
5. `shared/events/KafkaTopicsConfig.java` (NewTopic beans)
6. `application.yml` — Kafka producer/consumer blocks, Modulith blocks
7. `realtime/package-info.java`, `realtime/internal/SseHub.java`, `realtime/internal/ReplayBuffer.java`, `realtime/internal/RealtimeConsumer.java`, `realtime/internal/PresenceTracker.java`, `realtime/web/StreamController.java`
8. `shared/events/EventPublicationCleanup.java` (scheduled)
9. Tests

### File 1 — `TrakrEvent` and the envelope

| #   | Cue                                    | What                                                                                                                                                                                                                                                                |
| --- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: sealed event hierarchy`   | `public sealed interface TrakrEvent permits IssueEvent, ProjectEvent, CommentEvent, NotificationEvent, CollabEvent` with `UUID eventId()`, `String type()`, `UUID workspaceId()`, `UUID aggregateId()`, `long sequence()`, `UUID actorId()`, `Instant occurredAt()` |
| 2   | `// BLOCK 2: per-topic sub-interfaces` | e.g. `sealed interface IssueEvent extends TrakrEvent permits IssueCreated, IssueUpdated, …` with `UUID projectId()`                                                                                                                                                 |
| 3   | `// BLOCK 3: EventEnvelope record`     | fields from 04-events §3; `static EventEnvelope<T> wrap(T event, String source)`                                                                                                                                                                                    |

**Ask yourself:** what does `sealed` buy you in a `switch` over events in the consumers?

### File 4 — `EventExternalizationConfig`

| #   | Cue                                          | What                                                                                                                                                                                            |
| --- | -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: which events leave the process` | `EventExternalizationConfiguration.externalizing().select(event -> event instanceof TrakrEvent)`                                                                                                |
| 2   | `// BLOCK 2: routing`                        | route by sub-interface: `IssueEvent` → `Topics.ISSUES` with key `aggregateId()`, `ProjectEvent` → `Topics.PROJECTS` with key `projectId()`, etc. (`RoutingTarget.forTarget(topic).andKey(key)`) |
| 3   | `// BLOCK 3: mapping to the envelope`        | `.mapping(TrakrEvent.class, e -> EventEnvelope.wrap(e, "trakr/api"))`                                                                                                                           |
| 4   | `// BLOCK 4: headers`                        | add `ce_type`, `ce_id` Kafka headers (Modulith's Kafka module lets you customise the message — check the current API, e.g. a `headers` mapping)                                                 |

Contracts stay free of Spring annotations because routing is configured here.

### File 6 — Kafka + Modulith configuration

| #   | Cue                                   | What                                                                                                                                                                                                                                            |
| --- | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `# BLOCK 10: kafka producer`          | `bootstrap-servers`, `acks: all`, `properties: enable.idempotence: true, compression.type: zstd, linger.ms: 5`, JSON value serializer (the Jackson 3 based one in Spring for Apache Kafka 4 — find its exact class name), string key serializer |
| 2   | `# BLOCK 11: kafka consumer defaults` | `enable-auto-commit: false`, `isolation-level: read_committed`, `auto-offset-reset: latest` (realtime) — JSON deserializer with trusted package `dev.trakr.contracts`, wrapped in `ErrorHandlingDeserializer`                                   |
| 3   | `# BLOCK 12: modulith events`         | `spring.modulith.events.externalization.enabled: true`, `republish-outstanding-events-on-restart: true`, `jdbc.schema-initialization.enabled: false` (Flyway owns it)                                                                           |

### File 7 — realtime module

**`SseHub`**
| # | Cue | What |
|---|---|---|
| 1 | `// BLOCK 1: subscribers` | `ConcurrentHashMap<UUID projectId, Set<Subscriber>>` where `Subscriber(SseEmitter emitter, UUID userId)`; `ConcurrentHashMap.newKeySet()` |
| 2 | `// BLOCK 2: subscribe` | create `SseEmitter(0L)` (no timeout; we heartbeat), register `onCompletion/onTimeout/onError` to remove itself |
| 3 | `// BLOCK 3: broadcast(projectId, envelope)` | `emitter.send(SseEmitter.event().id(id).name(eventName).data(json, MediaType.APPLICATION_JSON))`; remove on `IOException` |
| 4 | `// BLOCK 4: heartbeat` | `@Scheduled(fixedRate = 15_000)` sends a comment `: ping` to every emitter |
**Watch out:** an `SseEmitter.send` may block on a slow client. On virtual threads that's fine per request, but broadcast loops run on the consumer thread — send through a small per-subscriber queue or a virtual-thread-per-send executor so one slow browser can't stall everyone. Implement the virtual-thread executor version and explain it.

**`RealtimeConsumer`**
`@KafkaListener(topics = {ISSUES, PROJECTS, COMMENTS}, groupId = "api-realtime-#{T(java.util.UUID).randomUUID()}")` → map envelope type to SSE event name (`trakr.issue.moved.v1` → `issue.moved`), strip private fields, `replayBuffer.append(projectId, envelope)`, `sseHub.broadcast(...)`.
**Ask yourself:** why a random group id here, but a fixed group id for the search indexer in phase 10?

**`ReplayBuffer`** (Redis Streams)
`XADD sse:project:{id} MAXLEN ~ 2000 * id <envelopeId> json <payload>`; `since(projectId, lastEventId)` → `XRANGE` from the entry after the one whose `id` field matches; if not found → `resync`.

**`StreamController`**
`GET /api/v1/projects/{key}/stream` (produces `text/event-stream`): access check (viewer) → subscribe → if `Last-Event-ID` header present, send replay (or `event: resync`) → presence join. `GET /api/v1/me/stream` → subscribe to user channel (fed in phase 10).

**`PresenceTracker`**
Redis sorted set `presence:project:{id}` (member = userId, score = expiry epoch) refreshed on heartbeat; broadcast `presence` events when the set changes.

## Tests

- `EventExternalizationIT` (Postgres + Kafka containers): move an issue → consume `trakr.issues.v1` with a test consumer → envelope fields correct, key = issue id, `ce_type` header present.
- `OutboxRecoveryIT`: stop the Kafka container, move an issue (commit succeeds), check `event_publication` has an incomplete row, start Kafka, trigger resubmission (`IncompleteEventPublications.resubmitIncompletePublications(...)`) → message arrives. **This test is the whole point of the outbox — run it and watch it.**
- `SseStreamIT`: open `/projects/MAR/stream` with `java.net.http.HttpClient` (`BodyHandlers.ofLines()`), move an issue via MockMvc/HTTP, assert an `event: issue.moved` line arrives within 2 s; reconnect with `Last-Event-ID` → replayed event.
- `SseHubTest`: a subscriber whose emitter throws is removed; slow subscriber doesn't block others.

## Explore

- kafka-ui: watch messages land in `trakr.issues.v1`; find the partition for a given issue key; see the `api-realtime-*` consumer groups appear and disappear as you restart.
- `curl -N -b cookies.txt localhost:8080/api/v1/projects/MAR/stream` in one terminal, move issues in another.

## Decisions taken while building it

- **Registry on JDBC** (`spring-modulith-starter-jdbc`), not JPA. `V009` is the verbatim **v2** PostgreSQL schema
  from `spring-modulith-events-jdbc-2.1.2.jar` (`schemas/v2/schema-postgresql.sql`, with `status`,
  `completion_attempts`, `last_resubmission_date`), without the archive table. Externalization mode stays the
  default `MODULE_LISTENER`: row written in the business transaction, sent after commit, completed on the ack.
- **Serialization:** `JacksonJsonSerializer` as value serializer with `spring.json.add.type.headers=false`, and
  `spring.modulith.events.kafka.enable-json=false` — Modulith's own JSON message converter would otherwise turn the
  serialized bytes into a base64 string. Consumers: `ErrorHandlingDeserializer` → `JacksonJsonDeserializer`,
  default type `EventEnvelope`.
- **Routing sees the original event, headers see the envelope** (read from Modulith's bytecode:
  `determineTarget(event)`, then `map(event)`, then the Kafka transport calls `getHeadersFor(payload)`). Headers
  `ce_type`, `ce_id`, `ce_specversion`, `ce_source` as UTF-8 bytes.
- **Hierarchy:** `TrakrEvent permits IssueEvent, ProjectEvent`; `ProjectEvent permits ProjectLifecycle, SprintEvent`.
  Comment, notification and collab events join in their phases (a sealed type can only list existing classes).
  ProjectService now also publishes `ProjectLifecycle.Created/Updated/Archived/MemberChanged`; a member change bumps
  the project's version so the event gets a fresh sequence.
- **Cleanup:** keep completed publications 7 days (daily delete) instead of `completion-mode=delete`; plus a job that
  resubmits publications still incomplete after 1 minute, so a Kafka outage heals without a restart.
- **SSE:** per-subscriber chain of sends on virtual threads (`CompletableFuture.thenRunAsync` on a
  virtual-thread-per-task executor): one slow browser can't stall the consumer or the others, and each browser keeps
  its order; >256 pending → disconnect (it reconnects and replays). Subscribe first, replay second, live frames wait
  behind the replay and are deduplicated by id. `ready` frame on connect; `presence` frames carry no id.
- Flow tests live in `dev.trakr.flows`. OutboxRecoveryIT pauses the Kafka container (`docker pause`, same ports) instead
  of stopping it, with short producer timeouts. Measured locally: `issue.moved` on the stream ~75 ms after the move.

## Done when

- Moving an issue shows up in a `curl -N` stream in < 1 s.
- Killing Kafka never loses an event (OutboxRecoveryIT green).

Commit: `phase-09: outbox, kafka, sse`.
