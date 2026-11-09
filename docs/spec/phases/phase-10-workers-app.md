# Phase 10 · Workers app: search indexer, report projector, notifier, mailer

**Goal:** a second Spring Boot app that consumes Kafka, keeps Elasticsearch and the report tables up to date, creates notifications and sends email — idempotently, with retries and dead-letter topics.
**Spec:** 04-events §5–7, 05-search §2, §5–6, 02-data-model §4. **Time:** 5 days.

## Concepts you'll learn

- **Idempotent consumers** — the same message twice must have the same effect as once.
- **External versioning in Elasticsearch** — "newest wins" without reading first.
- **Bulk indexing** — batching for throughput (`BulkIngester`).
- **Error handling in Spring Kafka** — `DefaultErrorHandler`, back-off, `DeadLetterPublishingRecoverer`, retryable vs fatal exceptions.
- **Consumer lag** — how far behind a consumer group is, and why it's the key metric for workers.
- **Projections** — read models built from events (burndown snapshots).
- **`JdbcClient`** — plain SQL with Spring's fluent client (workers don't need JPA).

## Files in this phase

0. api migration `V010__read_models.sql` (notification, sprint_day_snapshot, sprint_report, processed_event, saved_filter — 02-data-model §4); the api still owns all migrations
1. `settings.gradle.kts` → `include("apps:workers")`
2. `apps/workers/build.gradle.kts`: spring-app plugin; Spring Kafka; JDBC starter + postgresql; Elasticsearch Java client (`co.elastic.clients:elasticsearch-java`, matching 9.x) + Jackson; Mail starter; Actuator; Caffeine; contracts
3. `WorkersApplication.java`, `application.yml`, `application-dev.yml` (port 8082, no web endpoints except actuator + `/admin`)
4. `kafka/KafkaConsumerConfig.java` — container factories (record vs batch), error handler, DLT recoverer
5. `kafka/ProcessedEvents.java` — idempotency helper over `processed_event`
6. `search/ElasticsearchConfig.java`, `search/IndexManager.java`, `src/main/resources/es/trakr-issues-v1.json` (settings + mappings from 05-search §2)
7. `search/IssueDocument.java` (record), `search/IssueDocumentMapper.java`
8. `search/SearchIndexer.java`
9. `search/Reindexer.java` + `admin/AdminController.java` (`POST /admin/search/reindex`, `POST /admin/dlt/{topic}/replay`)
10. `reports/ReportProjector.java`
11. `notify/NotificationRules.java`, `notify/Notifier.java`
12. `mail/Mailer.java`, `mail/DigestScheduler.java`, templates in `src/main/resources/mail/*.txt`
13. api side: `realtime/internal/MeStreamConsumer.java` (consumes `trakr.notifications.v1`, pushes to `/me/stream`)
14. Tests

### File 4 — `KafkaConsumerConfig`

| #   | Cue                                 | What                                                                                                                                                                                                                                                          |
| --- | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: dead-letter recoverer` | `new DeadLetterPublishingRecoverer(kafkaTemplate, (rec, ex) -> new TopicPartition(rec.topic() + ".dlt", rec.partition()))`                                                                                                                                    |
| 2   | `// BLOCK 2: error handler`         | `new DefaultErrorHandler(recoverer, new ExponentialBackOffWithMaxRetries(4) {{ setInitialInterval(1000); setMultiplier(2); }})`; `addNotRetryableExceptions(DeserializationException.class, IllegalArgumentException.class, UnknownEventTypeException.class)` |
| 3   | `// BLOCK 3: record factory`        | `ConcurrentKafkaListenerContainerFactory` with `AckMode.RECORD`, concurrency 3, the error handler                                                                                                                                                             |
| 4   | `// BLOCK 4: batch factory`         | `setBatchListener(true)`, `AckMode.BATCH` (for the indexer)                                                                                                                                                                                                   |
| 5   | `// BLOCK 5: DLT topics`            | `NewTopic` beans for each `<topic>.dlt`                                                                                                                                                                                                                       |

**Ask yourself:** why must the DLT have the same number of partitions as the source topic for this recoverer?

### File 8 — `SearchIndexer`

| #   | Cue                                       | What                                                                                                                                                                                                                                                 |
| --- | ----------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: listener`                    | `@KafkaListener(topics = {ISSUES, COMMENTS, PROJECTS}, groupId = "workers-search-indexer", containerFactory = "batchFactory")` receiving `List<ConsumerRecord<String, EventEnvelope<TrakrEvent>>>`                                                   |
| 2   | `// BLOCK 2: route by type`               | pattern-matching `switch (envelope.data())` over the sealed hierarchy: created/updated/transitioned/moved → upsert; deleted → delete (also external-versioned); comment events → partial update of `commentsText`; project renamed → `updateByQuery` |
| 3   | `// BLOCK 3: bulk with external versions` | add operations to a `BulkIngester` (or one `BulkRequest` per batch): `index(i -> i.index(ALIAS).id(id).routing(projectId).version(sequence).versionType(VersionType.External).document(doc))`                                                        |
| 4   | `// BLOCK 4: interpret the bulk response` | `409 version_conflict` → ignore (we already have newer); other item errors → throw so the batch is retried (explain at-least-once again)                                                                                                             |

### File 6 — `IndexManager`

On startup: if alias `trakr-issues` doesn't exist → create `trakr-issues-v1` from the JSON file and point the alias at it. Teach: why apps write to an **alias** and never to the concrete index.

### File 10 — `ReportProjector`

Listens to issues + projects (record mode). Uses `processedEvents.runOnce(groupId, eventId, () -> …)` inside a transaction.

- `SprintStarted` → insert day-0 `sprint_day_snapshot` (scope = sum of points in sprint).
- Any issue event affecting a sprint issue (points change, done/undone, added/removed from active sprint) → recompute today's row for that sprint with one SQL `INSERT … ON CONFLICT (sprint_id, day) DO UPDATE` reading current values from `issue`.
- `SprintCompleted` → insert `sprint_report` from the event's report.
  **Ask yourself:** the projector reads current state from `issue` instead of applying deltas — what failure modes does that remove?

### File 11 — `NotificationRules` (pure, unit-tested) + `Notifier`

| Event                                 | Recipients                    | Kind             |
| ------------------------------------- | ----------------------------- | ---------------- |
| IssueUpdated with `assigneeId` change | new assignee (not the actor)  | `assigned`       |
| CommentAdded                          | `mentionedUserIds`            | `mentioned`      |
| CommentAdded                          | watchers − author − mentioned | `commented`      |
| IssueTransitioned                     | watchers − actor              | `status_changed` |

`Notifier` inserts rows + publishes `trakr.notification.created.v1` per row (key = user id).

### File 12 — `Mailer` + `DigestScheduler`

Mailer consumes notifications; immediate email for `mentioned`/`assigned`, others accumulate in Redis list `digest:{userId}`; `DigestScheduler` (`@Scheduled(fixedDelay = 10 min)`) sends one digest per user. Check: Mailpit UI shows them.

## Tests

- `NotificationRulesTest` (pure, table-driven).
- `IssueDocumentMapperTest`.
- `SearchIndexerIT` (Kafka + ES + Postgres containers): publish created → updated → **older updated** (lower sequence) → document reflects the newest; deleted removes it; replaying the same batch twice changes nothing.
- `ReportProjectorIT`: start sprint, complete issues across "days" (inject a fixed `Clock`), snapshots correct; duplicate event ignored.
- `DeadLetterIT`: a poison message (invalid JSON) lands in `.dlt` without blocking later messages.
- `ReindexIT`: wipe the index, run reindex, counts match Postgres.

## Decisions taken while building it

- **Kafka factories:** Boot's auto-configured consumer/producer factories, customised with
  `DefaultKafkaConsumerFactoryCustomizer` / `DefaultKafkaProducerFactoryCustomizer` (our deserializer and a
  `DelegatingByTypeSerializer`: `byte[]` for dead letters, Jackson for envelopes). Factories built by hand from
  `KafkaProperties` ignore a Testcontainers service connection and quietly connect to localhost:9092.
- **Deserializing `data`:** `EnvelopeDeserializer` reads the envelope's `type` and converts `data` with
  `EventTypes.classOf(type)`; an unknown type throws `UnknownEventTypeException` (not retryable → DLT).
- **Indexer:** one synchronous bulk request per poll (not the async `BulkIngester`, which would let offsets commit
  before Elasticsearch had the documents). A poison record in a batch → `BatchListenerFailedException(index)`.
  Deleted issues are deleted from the index (external-versioned), not flagged. Comment events: `CommentAdded`
  appends to `commentsText` by script; full comment text comes from the comment table in phase 15.
  Projects have no renamable field in the document; the `update_by_query` lesson is done with sprint renames.
- **Contracts added now:** `CommentEvent` (added/edited/deleted, routed to `trakr.comments.v1` by the api from phase 15)
  and `NotificationEvent.NotificationCreated`, so the rules, mailer and tests work before comments exist.
- **Reindex** streams Postgres with a `JdbcTemplate` (fetch size 1000 in a read-only transaction = a server-side cursor),
  since the workers have no jOOQ. `POST /admin/*` needs `X-Admin-Token`.
- **Notifier → Kafka** sends inside the DB transaction and waits for the ack (no outbox in workers): at least once,
  and the mailer dedupes by notification id. "Per-user mail preferences" have no table yet; everyone gets mail.
- **Tests** apply the api's migrations with Flyway from `apps/api/src/main/resources/db/migration` (test classpath only).
- Measured on the local stack: an edit reaches Elasticsearch in ~1 s, an assignment reaches `/me/stream` in ~250 ms.

## Done when

- Create/move/edit issues in the api → documents appear in ES within ~1 s (`GET trakr-issues/_doc/<id>` in Kibana).
- kafka-ui shows lag 0 for all `workers-*` groups at rest.
- A mention produces an in-app notification on `/me/stream` and an email in Mailpit.

Commit: `phase-10: workers app`.
