<#
.SYNOPSIS
  Runs the whole TRAKR dev stack with one command.

.DESCRIPTION
  .\dev.ps1            start containers, api, collab, workers and Vite; Ctrl+C stops all of them
  .\dev.ps1 stop       stop everything (e.g. after the window was closed instead of Ctrl+C)

  Containers are stopped, never removed: Postgres, Kafka and Elasticsearch data survive.
  App logs go to .dev-logs\ (api.log, collab.log, workers.log, web.log).

.PARAMETER KeepInfra
  Leave the containers running on exit, so the next start is faster.

.PARAMETER NoWorkers
  Skip the workers app (only needed for search indexing and emails).

.PARAMETER Observability
  Also start Grafana's LGTM container (traces, metrics, logs on http://localhost:3000) and make the apps export
  their traces and metrics to it over OTLP.
#>
param(
  [ValidateSet('up', 'stop')] [string] $Action = 'up',
  [switch] $KeepInfra,
  [switch] $NoWorkers,
  [switch] $Observability
)

$root     = $PSScriptRoot
$infra    = Join-Path $root 'infra'
$backend  = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'
$logs     = Join-Path $root '.dev-logs'
$appPorts = 8080, 8081, 8082, 5173
$apps     = New-Object System.Collections.ArrayList

# BLOCK 1: helpers
# both families: Vite listens on ::1 only, and a plain TcpClient in Windows PowerShell is IPv4-only
function Test-Port([int] $port) {
  foreach ($address in [System.Net.IPAddress]::Loopback, [System.Net.IPAddress]::IPv6Loopback) {
    $client = New-Object System.Net.Sockets.TcpClient($address.AddressFamily)
    try { $client.Connect($address, $port); return $true } catch { } finally { $client.Dispose() }
  }
  return $false
}

# Find our apps by command line, not by port: Windows often doesn't report the JVM's or Vite's listening
# socket as "Listen" (Get-NetTCPConnection / netstat), so a port lookup misses them.
function Get-StackProcess {
  $vite = Join-Path $frontend 'node_modules\.bin\*\vite\bin\vite.js'
  Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='node.exe'" | Where-Object {
    $_.CommandLine -and (
      $_.CommandLine -match 'dev\.trakr\.(api\.ApiApplication|collab\.CollabApplication|workers\.WorkersApplication)\b' -or
      $_.CommandLine -like "*$vite*")
  }
}

function Invoke-Compose([string[]] $composeArgs) {
  Push-Location $infra
  # the observability profile adds the LGTM container (phase 18); "stop" with the profile stops it too
  $profileArgs = if ($Observability) { @('--profile', 'observability') } else { @() }
  try { & docker compose --env-file .env @profileArgs @composeArgs } finally { Pop-Location }
  if ($LASTEXITCODE -ne 0) { throw "docker compose $($composeArgs -join ' ') failed" }
}

# cmd /c so .bat/.cmd shims (gradlew.bat, pnpm.cmd) work and stdout+stderr land in one log file
function Start-App([string] $name, [string] $dir, [string] $command, [int] $port) {
  $log = Join-Path $logs "$name.log"
  # cmd /c ""x.bat" args > "log" 2>&1": cmd strips only the outer quotes, so paths may contain spaces
  $proc = Start-Process cmd.exe -ArgumentList "/c `"$command > `"$log`" 2>&1`"" `
    -WorkingDirectory $dir -WindowStyle Hidden -PassThru
  $app = [pscustomobject] @{ Name = $name; Port = $port; Process = $proc; Log = $log }
  [void] $apps.Add($app)
  return $app
}

function Wait-App($app, [int] $timeoutSec) {
  Write-Host ("  waiting for {0,-8} on :{1} ..." -f $app.Name, $app.Port) -NoNewline
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while (-not (Test-Port $app.Port)) {
    if ($app.Process.HasExited -or (Get-Date) -gt $deadline) {
      Write-Host ' failed' -ForegroundColor Red
      Get-Content $app.Log -Tail 30 -Encoding UTF8 -ErrorAction SilentlyContinue | Write-Host
      throw "$($app.Name) did not start; full log: $($app.Log)"
    }
    Start-Sleep -Milliseconds 500
  }
  Write-Host ' up' -ForegroundColor Green
}

# BLOCK 2: stop - also used when the window was closed and the Ctrl+C cleanup never ran
function Stop-Stack {
  Write-Host "`nStopping apps..." -ForegroundColor Yellow
  foreach ($app in $apps) {
    if (-not $app.Process.HasExited) { & taskkill.exe /T /F /PID $app.Process.Id 2>&1 | Out-Null }
  }
  # catches what the tree kill missed (a run whose window was closed, a reused Gradle daemon)
  foreach ($proc in Get-StackProcess) {
    Write-Host "  killing $($proc.Name) pid $($proc.ProcessId)"
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
  }
  $deadline = (Get-Date).AddSeconds(15)
  while (($appPorts | Where-Object { Test-Port $_ }) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
  $busy = $appPorts | Where-Object { Test-Port $_ }
  if ($busy) { Write-Warning "Still in use: port(s) $($busy -join ', ') - not started by this script?" }
  if (-not $KeepInfra) {
    Write-Host 'Stopping containers (data is kept)...' -ForegroundColor Yellow
    Invoke-Compose @('stop')
  }
  Write-Host 'Stopped.' -ForegroundColor Green
}

if ($Action -eq 'stop') { Stop-Stack; return }

# BLOCK 3: preflight
& cmd.exe /c 'docker info >nul 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running - start Docker Desktop first.' }
if (-not (Test-Path (Join-Path $infra '.env'))) { throw 'infra\.env is missing - copy infra\.env.example to infra\.env.' }
$busy = $appPorts | Where-Object { Test-Port $_ }
if ($busy) {
  throw "Port(s) $($busy -join ', ') already in use - probably an earlier run. Run .\dev.ps1 stop first."
}
New-Item -ItemType Directory -Force $logs | Out-Null
if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
  Write-Host 'Installing frontend dependencies...' -ForegroundColor Cyan
  Push-Location $frontend
  try { & cmd.exe /c 'pnpm install' } finally { Pop-Location }
  if ($LASTEXITCODE -ne 0) { throw 'pnpm install failed' }
}

# BLOCK 4: start, in dependency order
try {
  Write-Host 'Starting containers...' -ForegroundColor Cyan
  Invoke-Compose @('up', '-d', '--wait')

  # the apps inherit this script's environment: with it set, they export traces and metrics to LGTM over OTLP
  if ($Observability) { $env:TRAKR_OTEL_ENABLED = 'true' }
  Write-Host 'Starting apps (first run compiles, give it a minute)...' -ForegroundColor Cyan
  # full path: cmd may be set not to look in the current folder (NoDefaultCurrentDirectoryInExePath)
  $gradlew = "`"$(Join-Path $backend 'gradlew.bat')`""
  $web = Start-App 'web' $frontend 'pnpm dev' 5173
  # the api runs the Flyway migrations, so it must be up before the workers read the schema
  $api = Start-App 'api' $backend "$gradlew :apps:api:bootRun --console=plain" 8080
  Wait-App $api 600
  # collab reads the issue tables the api's migrations create (and the api's sessions in Redis)
  $collab = Start-App 'collab' $backend "$gradlew :apps:collab:bootRun --console=plain" 8081
  Wait-App $collab 600
  if (-not $NoWorkers) {
    $workers = Start-App 'workers' $backend "$gradlew :apps:workers:bootRun --console=plain" 8082
    Wait-App $workers 600
  }
  Wait-App $web 120

  Write-Host ''
  Write-Host 'TRAKR is running' -ForegroundColor Green
  Write-Host '  App              http://localhost:5173   (ada@trakr.dev / correct-horse-7)'
  Write-Host '  UI gallery       http://localhost:5173/_ui'
  Write-Host '  Swagger UI       http://localhost:8080/swagger-ui.html'
  Write-Host '  API health       http://localhost:8080/actuator/health'
  Write-Host '  Collab health    http://localhost:8081/actuator/health'
  if (-not $NoWorkers) { Write-Host '  Workers health   http://localhost:8082/actuator/health' }
  if ($Observability) { Write-Host '  Grafana          http://localhost:3000   (admin / admin: Explore > Tempo for traces)' }
  Write-Host '  Mailpit          http://localhost:8025'
  Write-Host '  Elasticsearch    http://localhost:9200'
  Write-Host "  Logs             $logs"
  Write-Host ''
  Write-Host 'Press Ctrl+C to stop everything.' -ForegroundColor Yellow

  # BLOCK 5: watch - if one app dies, show why and shut the rest down
  while ($true) {
    $dead = $apps | Where-Object { $_.Process.HasExited } | Select-Object -First 1
    if ($dead) {
      Write-Host "`n$($dead.Name) stopped unexpectedly; last log lines:" -ForegroundColor Red
      Get-Content $dead.Log -Tail 30 -Encoding UTF8 -ErrorAction SilentlyContinue | Write-Host
      break
    }
    Start-Sleep -Seconds 1
  }
}
finally {
  # runs on Ctrl+C, on a startup failure and when an app dies
  Stop-Stack
}
