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

## Specification

See [`docs/spec/README.md`](docs/spec/README.md) for the specification.

## Repository Layout

The repository tree from `01-architecture` §3 will be added as the project is built.
