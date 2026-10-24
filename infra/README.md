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
