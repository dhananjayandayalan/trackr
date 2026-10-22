# Copilot setup — move these into `.github/`

Copilot reads its repository instructions from `.github/`. These files were delivered here because that folder couldn't be written remotely. Move them once:

**PowerShell (from the repo root):**
```powershell
New-Item -ItemType Directory -Force .github\instructions | Out-Null
Move-Item docs\copilot-setup\copilot-instructions.md .github\
Move-Item docs\copilot-setup\instructions\*.instructions.md .github\instructions\
Remove-Item -Recurse docs\copilot-setup
```

**Git Bash / WSL:**
```bash
mkdir -p .github/instructions
mv docs/copilot-setup/copilot-instructions.md .github/
mv docs/copilot-setup/instructions/*.instructions.md .github/instructions/
rm -rf docs/copilot-setup
```

Result:
```
.github/
├── copilot-instructions.md               repo-wide learning-mode rules
└── instructions/
    ├── backend-java.instructions.md       applies to backend/**/*.java
    ├── frontend-react.instructions.md     applies to frontend/src/**/*.{ts,tsx}
    ├── css.instructions.md                applies to frontend/src/**/*.css
    ├── sql-migrations.instructions.md     applies to Flyway migrations
    └── infra.instructions.md              applies to build, compose and config files
```
