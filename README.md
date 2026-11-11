## What TRAKR Is

TRAKR is the project built in this repository. Its purpose and features will be described here as they are defined.

## How to Run

Prerequisites: Docker Desktop running, a JDK to launch Gradle (it downloads JDK 25 itself), Node 24 with pnpm, and
`infra/.env` (copy `infra/.env.example`). Then, from the repository root in PowerShell:

```powershell
.\dev.ps1                # containers + api (:8080) + workers (:8082) + Vite (:5173); Ctrl+C stops it all
.\dev.ps1 -KeepInfra     # same, but leave the containers running on exit
.\dev.ps1 -NoWorkers     # skip the workers app (search indexing, emails)
.\dev.ps1 stop           # stop everything, e.g. after closing the window instead of pressing Ctrl+C
```

Open http://localhost:5173 and sign in as `ada@trakr.dev` / `correct-horse-7` (dev seed). App logs are in
`.dev-logs/`. Containers are stopped, not removed, so the data survives.

```powershell
.\dev.ps1 -Observability     # also Grafana's LGTM stack: traces, metrics and logs on http://localhost:3000
```

### Record a demo video

With the dev stack running (workers included, search needs them):

```powershell
.\demo.ps1            # a ~1½-minute scripted tour of every screen → frontend\demo\out\trakr-demo-<time>.webm
.\demo.ps1 -Headed    # watch it while it records
```

The tour signs in as Ada, has Grace act from a second browser (live board, co-editing), shows captions and the
cursor, and puts back everything it changed. An `.mp4` is written too when `ffmpeg` is on PATH.

### Production-shaped stack (containers behind Caddy)

```powershell
.\dev.ps1 stop
cd infra
docker compose --env-file .env -f docker-compose.yml -f docker-compose.prod.yml up -d --build --wait
```

Open http://localhost: the three apps run from their images (layered jars, Java 25 AOT cache, non-root), Caddy
serves the SPA and routes `/api`, `/collab` and OAuth. See `docs/spec/11-infra.md` §8.

### Tests

| Where | Command | What |
|---|---|---|
| `backend/` | `.\gradlew check` | unit, module, slice and Testcontainers integration tests (~15 min, needs Docker) |
| `frontend/` | `pnpm test`, `pnpm typecheck`, `pnpm lint` | Vitest, types, lint |
| `frontend/` | `pnpm build; pnpm size` | production build and its 180 KB first-load budget |
| `frontend/` | `pnpm e2e` | Playwright + axe on all 12 screens, against `.\dev.ps1` |

CI (`.github/workflows/ci.yml`) runs all of them on every push and pull request.

## Specification

See [`docs/spec/README.md`](docs/spec/README.md) for the specification.

## Repository Layout

The repository tree from `01-architecture` §3 will be added as the project is built.
