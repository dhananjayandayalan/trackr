# 11 · Local infrastructure and configuration

## 1. Services (`infra/docker-compose.yml`, project name `trakr`)

| Service       | Image (pin exact tag at phase 01)                   | Host port                   | Purpose                                         | Notes                                                                                |
| ------------- | --------------------------------------------------- | --------------------------- | ----------------------------------------------- | ------------------------------------------------------------------------------------ |
| postgres      | `postgres:18`                                       | 5432                        | Database `trakr`                                | init script creates extensions + roles; volume `pgdata`                              |
| redis         | `redis:8`                                           | 6379                        | Sessions, cache, rate limits, SSE replay buffer | `--appendonly yes`                                                                   |
| kafka         | `apache/kafka:4.x`                                  | 9092                        | Single-node KRaft broker+controller             | see §2                                                                               |
| kafka-ui      | `ghcr.io/kafbat/kafka-ui`                           | 8085                        | Browse topics, lag, DLT                         | profile `tools`                                                                      |
| elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:9.x` | 9200                        | Search read model                               | `discovery.type=single-node`, `xpack.security.enabled=false` (local only), heap 1 GB |
| kibana        | `docker.elastic.co/kibana/kibana:9.x`               | 5601                        | Dev Tools console for ES                        | profile `tools`                                                                      |
| seaweedfs     | `chrislusf/seaweedfs`                               | 8333 (S3), 9333 (master UI) | S3-compatible attachments                       | `server -s3 -s3.config=/etc/seaweedfs/s3.json -dir=/data`                            |
| mailpit       | `axllent/mailpit`                                   | 1025 (SMTP), 8025 (UI)      | Catches outgoing email                          |                                                                                      |

Every service has a `healthcheck`; apps start only after `docker compose up -d --wait` returns.
RAM budget: ~4–5 GB with tools profile off.

## 2. Kafka KRaft single node — the keys you must understand

| Env var                                                                                    | Value                                                                                        | Why                                                                                                  |
| ------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `KAFKA_NODE_ID`                                                                            | `1`                                                                                          | Unique id of this node                                                                               |
| `KAFKA_PROCESS_ROLES`                                                                      | `broker,controller`                                                                          | One process does both jobs (dev only)                                                                |
| `KAFKA_CONTROLLER_QUORUM_VOTERS`                                                           | `1@kafka:9093`                                                                               | Who votes in the metadata quorum                                                                     |
| `KAFKA_LISTENERS`                                                                          | `PLAINTEXT://:29092,CONTROLLER://:9093,PLAINTEXT_HOST://:9092`                               | Sockets the broker opens                                                                             |
| `KAFKA_ADVERTISED_LISTENERS`                                                               | `PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092`                                    | Addresses given to clients: containers use `kafka:29092`, your apps on the host use `localhost:9092` |
| `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP`                                                     | `CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT`                          |                                                                                                      |
| `KAFKA_CONTROLLER_LISTENER_NAMES`                                                          | `CONTROLLER`                                                                                 |                                                                                                      |
| `KAFKA_INTER_BROKER_LISTENER_NAME`                                                         | `PLAINTEXT`                                                                                  |                                                                                                      |
| `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`, `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR` | `1`                                                                                          | Only one broker exists                                                                               |
| `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR`                                                      | `1`                                                                                          |                                                                                                      |
| `KAFKA_AUTO_CREATE_TOPICS_ENABLE`                                                          | `false`                                                                                      | Topics are declared by the apps (04-events §4)                                                       |
| `KAFKA_NUM_PARTITIONS`                                                                     | `3`                                                                                          | Default if a topic forgets                                                                           |
| `CLUSTER_ID`                                                                               | a fixed base64 UUID (generate once with `kafka-storage.sh random-uuid` inside the container) | KRaft needs the storage formatted with a cluster id                                                  |

## 3. Postgres init (`infra/postgres/init/01-extensions.sh`)

Creates extensions `citext`, `pg_trgm` in database `trakr`, and roles `trakr_api`, `trakr_workers`, `trakr_collab` (passwords from env). Flyway runs as the owner role `trakr` (migrations); apps use their own roles from phase 18 (until then everything uses `trakr`).

## 4. SeaweedFS S3 (`infra/seaweedfs/s3.json`)

One identity `trakr-dev` with access key / secret from `.env` and actions `Read, Write, List, Tagging`. Bucket `trakr-attachments` created on api startup if missing (idempotent `createBucket`, ignore "already exists"). CORS on the bucket: allow `PUT, GET` from `http://localhost:5173`.

## 5. `infra/.env.example`

```
POSTGRES_PASSWORD=trakr
TRAKR_API_DB_PASSWORD=trakr_api
TRAKR_WORKERS_DB_PASSWORD=trakr_workers
TRAKR_COLLAB_DB_PASSWORD=trakr_collab
S3_ACCESS_KEY=trakr-dev
S3_SECRET_KEY=change-me-dev-secret
KAFKA_CLUSTER_ID=<generate once>
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
```

## 6. Application ports and URLs

| What                | URL                                                                                    |
| ------------------- | -------------------------------------------------------------------------------------- |
| Frontend dev (Vite) | http://localhost:5173                                                                  |
| api                 | http://localhost:8080 (Swagger UI `/swagger-ui.html`, health `/actuator/health`)       |
| collab              | ws://localhost:8081/collab/issues/{key} (health http://localhost:8081/actuator/health) |
| workers             | http://localhost:8082/actuator/health, admin `/admin/*`                                |
| kafka-ui            | http://localhost:8085                                                                  |
| Kibana              | http://localhost:5601                                                                  |
| Mailpit             | http://localhost:8025                                                                  |
| SeaweedFS S3        | http://localhost:8333                                                                  |

## 7. Spring configuration layout

Each app: `src/main/resources/application.yml` (shared defaults) + `application-dev.yml` (local hosts/ports above) + `application-test.yml`. Profiles: `dev` (default locally), `test`, `prod`. All secrets via `${ENV_VAR}` placeholders with dev defaults only in `application-dev.yml`.

Key properties (api):

```
spring.threads.virtual.enabled: true
spring.datasource.url: jdbc:postgresql://localhost:5432/trakr
spring.jpa.open-in-view: false            # explain why in phase 04
spring.jpa.hibernate.ddl-auto: validate   # Flyway owns the schema
spring.kafka.bootstrap-servers: localhost:9092
spring.data.redis.host: localhost
spring.elasticsearch.uris: http://localhost:9200
spring.modulith.events.externalization.enabled: true
spring.modulith.events.republish-outstanding-events-on-restart: true
server.servlet.session.cookie.name: TRAKR_SESSION
trakr.s3.endpoint: http://localhost:8333
trakr.s3.bucket: trakr-attachments
trakr.app.origin: http://localhost:5173
```

## 8. Production shape (phase 18)

Caddy in front: `/` → static frontend build (immutable hashed assets, `index.html` no-cache), `/api/*` → api (flush interval -1 for SSE), `/collab/*` → collab (WebSocket), `/oauth2/*` + `/login/oauth2/*` → api. Apps run as containers built with Spring Boot's Buildpacks image task or a hand-written multi-stage Dockerfile (we write the Dockerfile by hand — learning goal), using the Java 25 AOT cache for fast startup.
