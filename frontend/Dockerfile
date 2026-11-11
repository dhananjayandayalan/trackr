# syntax=docker/dockerfile:1
# The web image (phase 18, 11-infra §8): the production build of the SPA inside Caddy, which also routes /api and
# /collab to the apps. Build context is frontend/:   docker build -t trakr-web .      (from frontend/)
# The Caddyfile is not baked in: infra/docker-compose.prod.yml mounts infra/caddy/Caddyfile, so routing and headers
# change without rebuilding the files.

# BLOCK 1: build with pnpm. Corepack reads "packageManager" in package.json and runs exactly that pnpm. The
# manifests are copied first: as long as they don't change, the install layer is reused and only the build reruns.
# The store sits in a cache mount: shared between builds, never in a layer.
FROM node:24-alpine AS build
ENV PNPM_HOME=/pnpm COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,id=pnpm-store,target=/pnpm/store \
    pnpm install --frozen-lockfile --store-dir /pnpm/store
COPY . .
# vite build, not `pnpm build` (= tsc -b && vite build): type checking includes the tests, and one of them reads a
# fixture from docs/spec/, outside this build context. CI's frontend job type-checks; the image only bundles.
RUN pnpm exec vite build

# BLOCK 2: serve — dist/ is all a browser needs: index.html, hashed /assets/*, fonts, favicon
FROM caddy:2.10-alpine
COPY --from=build /app/dist /srv
