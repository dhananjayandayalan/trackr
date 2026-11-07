# 04 · Events, outbox and Kafka

## 1. Two kinds of events

- **Domain events** — Java records published inside a transaction with `ApplicationEventPublisher` (e.g. `IssueMoved`). In-process listeners in other api modules may react with `@ApplicationModuleListener` (async, after commit, in a new transaction).
- **Integration events** — the same facts serialised to Kafka for other apps (workers, collab, other api instances). Only events marked `@Externalized` leave the process.

The payload records live in `libs/contracts` (`dev.trakr.contracts.events`) so every app shares one definition.

## 2. The outbox (why events are never lost)

```
BEGIN
  UPDATE issue SET status_id=…, rank=…, version=version+1 WHERE id=… AND version=…
  INSERT INTO issue_event (…)
  INSERT INTO event_publication (id, event_type, serialized_event, listener_id, publication_date)  ← Modulith
COMMIT
-- after commit, asynchronously:
  Modulith externalizer → KafkaTemplate.send(topic, key, envelope) → on ack: completion_date = now()
```

- If the app crashes after commit but before Kafka acks, the publication stays incomplete. On restart Modulith resubmits incomplete publications (`spring.modulith.events.republish-outstanding-events-on-restart=true`).
- So delivery is **at-least-once** → consumers must be idempotent (§6).
- Completed publications are deleted after 7 days by a scheduled job (`spring.modulith.events.completion-mode=delete` or a cleanup task — pick one in phase 09 and explain why).

## 3. Envelope (CloudEvents 1.0 structured mode, JSON)

```json
{
  "specversion": "1.0",
  "id": "0190f3b2-7c1e-7a51-9f00-3b1d2c9e4a10",
  "type": "trakr.issue.moved.v1",
  "source": "trakr/api",
  "subject": "issue/0190f2aa-…",
  "time": "2026-10-22T13:20:00.123Z",
  "datacontenttype": "application/json",
  "workspaceid": "0190…",
  "projectid": "0190…",
  "actorid": "0190…",
  "sequence": 42,
  "data": { "...": "event-specific" }
}
```

- `id`: UUIDv7 created when the domain event is created (not when sent) → stable across retries → dedupe key.
- `sequence`: the aggregate's `version` after the change. Consumers drop events with `sequence ≤ lastSeen` for that aggregate.
- Kafka record key = aggregate id (string). Kafka headers duplicate `ce_type` and `ce_id` so consumers can route without parsing the body.
- Java: `record EventEnvelope<T>(String specversion, UUID id, String type, …, T data)` in `libs/contracts`; a `TrakrEvent` sealed interface lets each payload declare its `type()`, `subject()`, `aggregateId()`, `sequence()`.

## 4. Catalogue

### Topics

| Topic                    | Key        | Partitions (local/prod) | Retention | Producers          | Consumers                                                            |
| ------------------------ | ---------- | ----------------------- | --------- | ------------------ | -------------------------------------------------------------------- |
| `trakr.issues.v1`        | issue id   | 3 / 12                  | 7 days    | api                | api-realtime, workers (search-indexer, report-projector, notifier)   |
| `trakr.projects.v1`      | project id | 3 / 6                   | 7 days    | api                | api-realtime, workers (search-indexer for project renames, notifier) |
| `trakr.comments.v1`      | issue id   | 3 / 6                   | 7 days    | api                | api-realtime, workers (search-indexer, notifier)                     |
| `trakr.notifications.v1` | user id    | 3 / 6                   | 3 days    | workers (notifier) | api-realtime (`/me/stream`), workers (mailer)                        |
| `trakr.collab.v1`        | issue id   | 3 / 12                  | 1 hour    | collab             | collab (cross-instance relay), api (description snapshots)           |
| `<topic>.dlt`            | same       | same                    | 14 days   | error handlers     | humans (kafka-ui)                                                    |

Topics are declared as `NewTopic` beans in the producing app (`TopicBuilder.name(...).partitions(...).config(...)`) so they exist before first use. `auto.create.topics.enable=false` on the broker.

### Event types (payload `data`)

| type                                                                        | Topic         | data                                                                |
| --------------------------------------------------------------------------- | ------------- | ------------------------------------------------------------------- |
| `trakr.issue.created.v1`                                                    | issues        | full `IssueSnapshot`                                                |
| `trakr.issue.updated.v1`                                                    | issues        | `{changes: {field: {old,new}}, snapshot: IssueSnapshot}`            |
| `trakr.issue.transitioned.v1`                                               | issues        | `{fromStatusId,toStatusId,toCategory,snapshot}`                     |
| `trakr.issue.moved.v1`                                                      | issues        | `{statusId,sprintId,rank,previousSprintId,snapshot}`                |
| `trakr.issue.deleted.v1`                                                    | issues        | `{key}`                                                             |
| `trakr.issue.linked.v1`                                                     | issues        | `{linkId,targetIssueId,linkType}`                                   |
| `trakr.issue.work-logged.v1`                                                | issues        | `{minutes,userId}`                                                  |
| `trakr.issue.description-changed.v1`                                        | issues        | `{text,html}` (emitted by api after a collab snapshot)              |
| `trakr.comment.added.v1` / `.edited.v1` / `.deleted.v1`                     | comments      | `{commentId,issueId,issueKey,authorId,bodyText,mentionedUserIds[]}` |
| `trakr.project.created.v1` / `.updated.v1` / `.archived.v1`                 | projects      | `ProjectSnapshot`                                                   |
| `trakr.project.member-changed.v1`                                           | projects      | `{userId,role\|null}`                                               |
| `trakr.workflow.changed.v1`                                                 | projects      | `{statuses[],transitions[]}`                                        |
| `trakr.sprint.started.v1` / `.completed.v1` / `.updated.v1` / `.created.v1` | projects      | `SprintSnapshot` (+ `report` on completed)                          |
| `trakr.sprint.deleted.v1` (added in phase 08)                               | projects      | `SprintSnapshot` of the deleted planned sprint                      |
| `trakr.notification.created.v1`                                             | notifications | `{notificationId,userId,kind,issueKey,actorName,summary}`           |
| `trakr.collab.update.v1`                                                    | collab        | `{issueId, update: base64, originInstance, clientId}`               |
| `trakr.collab.snapshot.v1`                                                  | collab        | `{issueId, text, html, uptoUpdateId}`                               |

`IssueSnapshot` = every field the search index and board need (see 05-search §2). Snapshots make consumers simple: they never call back into the api.

Schema evolution rules: add fields only; never rename or change meaning; breaking change → new `type` with `.v2` and dual-publish for one release.

## 5. Consumers

| App     | Group id                                                                               | Topics                                    | Does                                                                                               |
| ------- | -------------------------------------------------------------------------------------- | ----------------------------------------- | -------------------------------------------------------------------------------------------------- |
| api     | `api-realtime-${random-instance-id}` (unique per instance, `auto.offset.reset=latest`) | issues, projects, comments, notifications | Pushes SSE to local subscribers, appends to Redis stream `sse:project:{id}` (MAXLEN ~ 2000, 5 min) |
| api     | `api-collab-snapshots`                                                                 | collab (`snapshot` type only)             | Updates `issue.description_*`, bumps version, emits `description-changed`                          |
| workers | `workers-search-indexer`                                                               | issues, comments, projects                | Upserts ES documents (bulk, 500 docs / 1 s)                                                        |
| workers | `workers-report-projector`                                                             | issues, projects                          | Maintains `sprint_day_snapshot`, `sprint_report`                                                   |
| workers | `workers-notifier`                                                                     | issues, comments, projects                | Writes `notification` rows, publishes `trakr.notification.created.v1`                              |
| workers | `workers-mailer`                                                                       | notifications                             | Sends email (respecting per-user preferences; batches into a 10-minute digest)                     |
| collab  | `collab-relay-${instance-id}`                                                          | collab (`update` type)                    | Broadcast to local sockets unless `originInstance` = self                                          |

Consumer settings: `enable.auto.commit=false`, ack mode `RECORD` for notifier/mailer, `BATCH` for search-indexer; `isolation.level=read_committed`; `max.poll.records=500` (indexer) / 50 (others).
Producer settings: `acks=all`, `enable.idempotence=true`, `compression.type=zstd`, `linger.ms=5`.

## 6. Idempotency and ordering

- Ordering is guaranteed **per key** (= per aggregate) because one key always lands on the same partition.
- Search indexer: uses ES external versioning — `version = sequence`, `version_type=external` → an older event can never overwrite a newer document.
- Report projector & notifier: insert into `processed_event (consumer_group, event_id)` in the same DB transaction as the side effect; a duplicate violates the PK → skip.
- Mailer: dedupes by notification id.

## 7. Errors and retries

`DefaultErrorHandler` with exponential back-off (1 s, 2 s, 4 s, 8 s, max 4 attempts) then `DeadLetterPublishingRecoverer` → `<topic>.dlt` with exception headers. Deserialization errors go straight to the DLT (no retry). Non-retryable exceptions (validation, unknown type) are classified as such. A `workers` admin endpoint `POST /admin/dlt/{topic}/replay` re-publishes DLT records to the main topic.

## 8. Local tools

kafka-ui (kafbat) at http://localhost:8085 — browse topics, see consumer lag, inspect DLT messages.
