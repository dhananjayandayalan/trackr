# Phase 01 · Local infrastructure (Docker Compose)

**Goal:** one command starts Postgres, Redis, Kafka, Elasticsearch, SeaweedFS and Mailpit, all healthy.
**Spec:** 11-infra.md. **Time:** 1 day.

## Concepts you'll learn

- **Compose service / volume / network / healthcheck / profile.**
- **YAML anchors & merge keys** (`x-` extension fields, `&name`, `<<: *name`) — remove duplication.
- **Kafka KRaft** — Kafka's own Raft-based metadata quorum; no ZooKeeper since Kafka 4.
- **Advertised listeners** — the address a broker _tells_ clients to use; the #1 cause of "connected but can't produce".
- **Single-node Elasticsearch** — why `discovery.type=single-node` and why the heap is capped.
- **S3 API** — buckets, objects, access keys, presigned URLs (used in phase 15).

## Files in this phase

1. `infra/.env.example` and your local `infra/.env`
2. `infra/postgres/init/01-extensions.sh`
3. `infra/seaweedfs/s3.json`
4. `infra/docker-compose.yml`
5. `infra/README.md` (how to start/stop/reset)

### File 1 — `.env.example` → copy to `.env`

Copy the variables from 11-infra §5. Generate `KAFKA_CLUSTER_ID` once:
`docker run --rm apache/kafka:<tag> /opt/kafka/bin/kafka-storage.sh random-uuid`.
**Ask yourself:** why is `.env` git-ignored but `.env.example` committed?

### File 2 — `01-extensions.sh`

Use a shell init script so app-role passwords come from the environment instead of being hard-coded in SQL.

| #   | Cue                                              | What it must do                                                                                                                                                                                                                                           |
| --- | ------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `-- BLOCK 1: extensions used by the schema`      | `CREATE EXTENSION IF NOT EXISTS citext; … pg_trgm;`                                                                                                                                                                                                       |
| 2   | `-- BLOCK 2: per-app roles (used from phase 18)` | `CREATE ROLE trakr_api LOGIN PASSWORD '…'` etc. Passwords: the official image runs `.sql` files without env substitution — so either hard-code dev passwords here or switch this file to a `.sh` script that reads env vars. Pick one and write down why. |

**Check:** after compose up, `docker compose exec postgres psql -U trakr -d trakr -c '\dx'` lists both extensions.
**Ask yourself:** init scripts run only when the data volume is empty — how do you re-run them?

### File 3 — `s3.json`

| #   | What it must do                                                                                                                                          |
| --- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `identities[]` with one identity `trakr-dev`, credentials `{accessKey, secretKey}` matching `.env`, `actions: ["Read","Write","List","Tagging","Admin"]` |

(SeaweedFS reads JSON literally — no env substitution — so this file holds the dev key; it is dev-only.)

### File 4 — `docker-compose.yml`

| #   | Cue                                           | What it must do                                                                                                                                                                                                                                                                                                                                      | Check                                          |
| --- | --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| 1   | `# BLOCK 1: project name and shared defaults` | `name: trakr`; an `x-healthcheck-defaults` anchor (`interval: 5s`, `timeout: 3s`, `retries: 30`, `start_period: 10s`); an `x-logging` anchor capping json-file logs (`max-size: 10m`, `max-file: 3`)                                                                                                                                                 | `docker compose config` renders without errors |
| 2   | `# BLOCK 2: postgres`                         | Image `postgres:18`; env `POSTGRES_DB=trakr`, `POSTGRES_USER=trakr`, `POSTGRES_PASSWORD=${POSTGRES_PASSWORD}`, and the three `TRAKR_*_DB_PASSWORD` values for the init script; port 5432; volume `pgdata:/var/lib/postgresql` (check the image docs — the data path changed in 18); mount `./postgres/init` to `/docker-entrypoint-initdb.d:ro`; healthcheck `pg_isready -U trakr -d trakr`                          | `psql` connects                                |
| 3   | `# BLOCK 3: redis`                            | `redis:8`, command `redis-server --appendonly yes`, port 6379, volume, healthcheck `redis-cli ping`                                                                                                                                                                                                                                                  | `redis-cli -p 6379 ping` → PONG                |
| 4   | `# BLOCK 4: kafka (KRaft, single node)`       | `apache/kafka:<4.x tag>`; every env var from 11-infra §2; `CLUSTER_ID: ${KAFKA_CLUSTER_ID}`; port `9092:9092`; volume `kafkadata:/var/lib/kafka/data`; healthcheck runs `/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092`                                                                                              | see below                                      |
| 5   | `# BLOCK 5: kafka-ui (profile tools)`         | `ghcr.io/kafbat/kafka-ui`; `profiles: [tools]`; env `KAFKA_CLUSTERS_0_NAME=local`, `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=kafka:29092`; port `8085:8080`; `depends_on: kafka: condition: service_healthy`                                                                                                                                                | UI shows the cluster                           |
| 6   | `# BLOCK 6: elasticsearch`                    | Image `docker.elastic.co/elasticsearch/elasticsearch:<9.x>`; env `discovery.type=single-node`, `xpack.security.enabled=false`, `ES_JAVA_OPTS=-Xms1g -Xmx1g`, `cluster.routing.allocation.disk.threshold_enabled=false`; `ulimits.memlock` unlimited; port 9200; volume; healthcheck `curl -fs localhost:9200/_cluster/health?wait_for_status=yellow` | `curl localhost:9200` shows version 9          |
| 7   | `# BLOCK 7: kibana (profile tools)`           | Matching 9.x tag, `ELASTICSEARCH_HOSTS=http://elasticsearch:9200`, port 5601                                                                                                                                                                                                                                                                         | Dev Tools console works                        |
| 8   | `# BLOCK 8: seaweedfs (S3 API)`               | `chrislusf/seaweedfs`; `command: server -dir=/data -s3 -s3.config=/etc/seaweedfs/s3.json`; mount the json read-only; ports 8333, 9333; volume; healthcheck on the master `/cluster/status`                                                                                                                                                           | `curl localhost:9333/cluster/status`           |
| 9   | `# BLOCK 9: mailpit`                          | `axllent/mailpit`, ports 1025 and 8025                                                                                                                                                                                                                                                                                                               | UI at :8025                                    |
| 10  | `# BLOCK 10: volumes`                         | Named volumes `pgdata`, `redisdata`, `kafkadata`, `esdata`, `seaweeddata`                                                                                                                                                                                                                                                                            |                                                |

**Kafka check (do all three, they teach different things):**

```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic smoke --partitions 3
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic smoke
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic smoke --from-beginning
```

Then delete the topic. Wait — auto-create is off, so `--create` was required. **Ask yourself:** what would happen to an app on your host if `ADVERTISED_LISTENERS` only had `kafka:29092`?

**S3 check:** with the AWS CLI (or any S3 client) pointed at `--endpoint-url http://localhost:8333`, create and list a bucket.

### File 5 — `infra/README.md`

Commands: `docker compose --env-file .env up -d --wait`, `--profile tools`, `docker compose logs -f kafka`, `docker compose down` (keeps data), `docker compose down -v` (wipes data — when and why).

## Done when

- `docker compose up -d --wait` returns with every service healthy.
- You produced and consumed a Kafka message, created an S3 bucket, and queried ES.
- You can explain listeners vs advertised listeners and why ES needs a heap cap.

Commit: `phase-01: local infrastructure`.
