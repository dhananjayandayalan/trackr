# Local infrastructure

Run these commands from the `infra` directory. Make sure `.env` exists there
and contains the values documented in `.env.example`.

## Start services

```powershell
docker compose --env-file .env up -d --wait
```

To also start the optional Kafka UI and Kibana tools:

```powershell
docker compose --env-file .env --profile tools up -d --wait
```

## Seed a long backlog (dev only)

To check how the backlog screen handles a long list, add 1 000 issues to the bottom of the demo workspace's
MAR backlog. Each run adds another 1 000, and they don't reach search, because they bypass the api's events.

```powershell
Get-Content postgres/seed-big-backlog.sql | docker compose --env-file .env exec -T postgres psql -U trakr -d trakr -v ON_ERROR_STOP=1
```

## View Kafka logs

```powershell
docker compose --env-file .env logs -f kafka
```

Press `Ctrl+C` to stop following the logs; this does not stop the service.

## Stop services

```powershell
docker compose --env-file .env down
```

This stops and removes the containers and network but keeps named volumes, so
database, Kafka, Elasticsearch, Redis, and SeaweedFS data are available next
time you start the services.

To remove the volumes as well:

```powershell
docker compose --env-file .env down -v
```

Use `down -v` only when you intentionally want to wipe the local service data,
such as resetting the development environment. The data cannot be recovered
from the removed volumes.
