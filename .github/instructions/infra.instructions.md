---
applyTo: "{infra/**,**/*.gradle.kts,**/libs.versions.toml,frontend/*.json,frontend/*.ts,**/Dockerfile,**/application*.yml}"
---

# Build and infrastructure files (learning mode applies — one section at a time)

Source: `docs/spec/11-infra.md` and the current phase file.

- Write configuration one top-level section at a time, then explain every key in that section: what it controls, the default if omitted, and why the spec sets it.
- Pin versions in exactly one place: `backend/gradle/libs.versions.toml` for the JVM side, `frontend/package.json` for the web side, image tags in `infra/docker-compose.yml`.
- Never suggest generator commands. The only generated files allowed are the Gradle wrapper (`gradle wrapper`), the pnpm lockfile, jOOQ sources and `schema.d.ts`.
- Ports, credentials, topic names and bucket names must match `docs/spec/11-infra.md`.
- Local credentials are dev-only and live in `infra/.env` (git-ignored); `infra/.env.example` documents them.
