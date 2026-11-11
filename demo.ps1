<#
.SYNOPSIS
  Records a video tour of the whole application (frontend/demo/record-demo.ts).

.DESCRIPTION
  .\demo.ps1                  record against the dev stack (start it first with .\dev.ps1, workers included)
  .\demo.ps1 -Headed          watch the browser while it records
  .\demo.ps1 -Base http://localhost   record the production-shaped stack behind Caddy instead

  The video lands in frontend\demo\out\ (trakr-demo-<time>.webm, plus an .mp4 when ffmpeg is on PATH). The tour
  signs in as the dev seed's Ada, uses Grace as the colleague in a second (unrecorded) browser, and puts back
  everything it changes when it ends.
#>
param(
  [switch] $Headed,
  [string] $Base = 'http://localhost:5173'
)

$frontend = Join-Path $PSScriptRoot 'frontend'

# BLOCK 1: is the app there? The SPA answers /login and the api answers /api/v1/me (401 when signed out is fine)
function Test-Url([string] $url) {
  try { Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5 | Out-Null; return $true }
  catch { return $null -ne $_.Exception.Response }
}
if (-not (Test-Url "$Base/login") -or -not (Test-Url "$Base/api/v1/me")) {
  throw "The app doesn't answer on $Base - start it first (.\dev.ps1, or the Caddy stack for http://localhost)."
}

# BLOCK 2: record
$demoArgs = @("--base=$Base")
if ($Headed) { $demoArgs += '--headed' }
Push-Location $frontend
try {
  & cmd.exe /c "pnpm demo $($demoArgs -join ' ')"
  if ($LASTEXITCODE -ne 0) { throw "The recording stopped early (exit $LASTEXITCODE); see the output above." }
} finally { Pop-Location }

Write-Host ''
Write-Host "Recordings: $(Join-Path $frontend 'demo\out')" -ForegroundColor Green
