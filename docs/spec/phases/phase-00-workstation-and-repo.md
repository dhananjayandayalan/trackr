# Phase 00 · Workstation and repository

**Goal:** a clean repo with the right tools installed, and Copilot set up to help one block at a time.
**Time:** 2–3 hours.

## Concepts you'll learn

- **Toolchain pinning** — every developer (and CI) uses the same JDK/Node versions, so "works on my machine" disappears.
- **`.gitignore` / `.gitattributes`** — what never enters version control, and how line endings are normalised (important on Windows).
- **EditorConfig** — editor-agnostic formatting basics (indent, charset, final newline).

## 1. Install and verify the tools

| Tool                                      | Version                                                                             | Verify                   |
| ----------------------------------------- | ----------------------------------------------------------------------------------- | ------------------------ |
| Git                                       | latest                                                                              | `git --version`          |
| JDK                                       | 25 (Eclipse Temurin)                                                                | `java -version` → `25`   |
| Gradle (only to create the wrapper once)  | 9.x                                                                                 | `gradle --version`       |
| Node.js                                   | 24 LTS                                                                              | `node -v`                |
| pnpm                                      | 10.x via Corepack: `corepack enable` then `corepack prepare pnpm@latest --activate` | `pnpm -v`                |
| Docker Desktop (WSL 2 backend on Windows) | latest                                                                              | `docker compose version` |
| IDE                                       | IntelliJ IDEA (backend) and/or VS Code (frontend), both with GitHub Copilot         | Copilot icon active      |

Windows note: clone and work **inside the WSL 2 filesystem** (`~/code/jira-clone`) or directly on Windows — pick one and stay there. Mixing them makes Docker volumes and file watching slow.

## 2. Files in this phase

1. `.gitignore`
2. `.gitattributes`
3. `.editorconfig`
4. `README.md` (root)
5. `docs/learning-log.md`
6. `.vscode/settings.json` (optional, editor behaviour for Copilot learning mode)

### File 1 — `.gitignore`

| #   | Cue                                | What it must do                                                                                                                                                              | Check                                                 |
| --- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| 1   | `# BLOCK 1: OS and editor junk`    | Ignore `.DS_Store`, `Thumbs.db`, `.idea/` (except shared run configs if you want), `*.iml`, `.vscode/*` but keep `.vscode/settings.json` and `extensions.json` via `!` rules | `git status` after opening the IDE shows no IDE files |
| 2   | `# BLOCK 2: Gradle and Java`       | Ignore `.gradle/`, `build/`, `out/`, `*.class`, but **not** `gradle/wrapper/gradle-wrapper.jar`                                                                              |                                                       |
| 3   | `# BLOCK 3: Node and Vite`         | Ignore `node_modules/`, `dist/`, `.vite/`, `coverage/`, `playwright-report/`, `test-results/`                                                                                |                                                       |
| 4   | `# BLOCK 4: secrets and local env` | Ignore `infra/.env`, `*.local`, `.env*` except `.env.example`                                                                                                                | `git check-ignore -v infra/.env` prints a rule        |

**Ask yourself:** why is the wrapper jar committed even though it's a binary?

### File 2 — `.gitattributes`

| #   | Cue                                 | What it must do                                           |
| --- | ----------------------------------- | --------------------------------------------------------- |
| 1   | `# BLOCK 1: normalise line endings` | `* text=auto eol=lf`; force `*.bat`/`*.cmd` to `eol=crlf` |
| 2   | `# BLOCK 2: binaries`               | Mark `*.jar`, `*.png`, `*.woff2`, `*.bin` as `binary`     |

**Ask yourself:** what breaks in a shell script or Docker entrypoint checked out with CRLF?

### File 3 — `.editorconfig`

Blocks: `root = true`; `[*]` utf-8, lf, final newline, trim trailing whitespace, indent 2 spaces; `[*.{java,kt,kts}]` indent 4; `[*.md]` keep trailing whitespace (two spaces = line break).

### File 4 — root `README.md`

Sections (one block each): what TRAKR is · how to run (filled in later phases) · link to `docs/spec/README.md` · repo layout (copy the tree from 01-architecture §3 as you build it).

### File 5 — `docs/learning-log.md`

Header + the template from `docs/spec/README.md` §5. Add one entry for each block in this phase.

### File 6 — `.vscode/settings.json` (optional)

| #   | What it must do                                                                                                                                                     |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `editor.formatOnSave: true`, `files.eol: "\n"`                                                                                                                      |
| 2   | Keep Copilot inline suggestions on; turn **off** "next edit suggestions" if your Copilot version offers that setting, so only the block at your cursor is suggested |
| 3   | `css.lint.unknownAtRules: "ignore"` (stops false warnings for newer at-rules)                                                                                       |

## 3. Initialise the repository

```
git init -b main
git add .
git commit -m "phase-00: repo skeleton and tooling"
```

Create the empty folders now so the layout is visible: `infra/`, `backend/`, `frontend/`, `docs/spec/fixtures/`.

## Done when

- All tool versions verified.
- `git status` is clean after opening both IDEs.
- You can explain every line of `.gitignore` and `.gitattributes`.
