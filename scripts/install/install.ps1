#Requires -Version 5.1
<#
.SYNOPSIS
  EIP one-command local install for Windows. Mirrors scripts/install/install.sh 1:1.
.DESCRIPTION
  Stands up the FULL infra stack (Postgres, Redis, Kafka, MinIO, Keycloak, OTel Collector,
  Prometheus, Grafana via Docker Compose), provisions the RLS role, builds and starts the backend
  with the selected environment profile, starts the frontend in the matching Vite mode,
  smoke-tests the live endpoints, and prints endpoints + sample-user info.
  Environments (ADR-022): config/environments/<env>.env — dev/test seed the demo dataset,
  preprod does not, prod refuses to boot until OIDC lands (DEBT-012).
.EXAMPLE
  .\scripts\install\install.ps1 -Env dev
  .\scripts\install\install.ps1 -Env preprod -CoreOnly
#>
[CmdletBinding()]
param(
  [ValidateSet('dev', 'test', 'preprod', 'prod')]
  [string]$Env = 'dev',
  [switch]$CoreOnly
)
$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $Root

if ($Env -eq 'prod') {
  Write-Host @'
  X prod is configuration-complete but INTENTIONALLY not bootable in this release.
    OIDC tenant resolution is not implemented yet (DEBT-012); ProductionTenantResolutionGuard
    refuses startup so the dev header tenant resolver can never serve production traffic.
    Use -Env preprod for a production rehearsal, or dev/test for seeded environments.
'@
  exit 2
}

$EnvConf = "config/environments/$Env.env"
if (-not (Test-Path $EnvConf)) { Write-Error "unknown environment '$Env'"; exit 2 }
$StateDir = '.install'
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
$ComposeEnv = 'infra/docker-compose/.env'
if (-not (Test-Path $ComposeEnv)) { Copy-Item 'infra/docker-compose/.env.example' $ComposeEnv }

# --- config resolution: process env > env file > compose .env > default ------------------------
function Read-EnvFile([string]$Path) {
  $map = @{}
  if (Test-Path $Path) {
    foreach ($line in Get-Content $Path) {
      if ($line -match '^\s*([A-Z_]+)=([^#]*)') { $map[$Matches[1]] = $Matches[2].Trim() }
    }
  }
  return $map
}
$fileConf = Read-EnvFile $EnvConf
$composeConf = Read-EnvFile $ComposeEnv
function Get-Conf([string]$Key, [string]$Default) {
  $v = [Environment]::GetEnvironmentVariable($Key)
  if ($v) { return $v }
  if ($fileConf.ContainsKey($Key) -and $fileConf[$Key]) { return $fileConf[$Key] }
  if ($composeConf.ContainsKey($Key) -and $composeConf[$Key]) { return $composeConf[$Key] }
  return $Default
}

$SpringProfile = Get-Conf 'SPRING_PROFILES_ACTIVE' 'dev'
$ViteMode      = Get-Conf 'VITE_MODE' 'development'
$FrontendServe = Get-Conf 'FRONTEND_SERVE' 'dev'
$Seeded        = Get-Conf 'SEEDED' '1'
$BackendPort   = Get-Conf 'EIP_BACKEND_PORT' '8080'
$FrontendPort  = Get-Conf 'VITE_PORT' '5173'
$PgPort        = Get-Conf 'POSTGRES_PORT' '5432'
$PgUser        = Get-Conf 'POSTGRES_USER' 'eip'
$PgPassword    = Get-Conf 'POSTGRES_PASSWORD' 'eip_dev_pw'
$PgDb          = Get-Conf 'POSTGRES_DB' 'eip'
$Ports = [ordered]@{
  'Postgres'      = @($PgPort, 'POSTGRES_PORT')
  'Redis'         = @((Get-Conf 'REDIS_PORT' '6379'), 'REDIS_PORT')
  'Kafka'         = @((Get-Conf 'KAFKA_PORT' '29092'), 'KAFKA_PORT')
  'MinIO API'     = @((Get-Conf 'MINIO_API_PORT' '9000'), 'MINIO_API_PORT')
  'MinIO console' = @((Get-Conf 'MINIO_CONSOLE_PORT' '9001'), 'MINIO_CONSOLE_PORT')
  'Keycloak'      = @((Get-Conf 'KEYCLOAK_PORT' '8180'), 'KEYCLOAK_PORT')
  'Keycloak mgmt' = @((Get-Conf 'KEYCLOAK_MGMT_PORT' '9010'), 'KEYCLOAK_MGMT_PORT')
  'Backend'       = @($BackendPort, 'EIP_BACKEND_PORT')
  'Frontend'      = @($FrontendPort, 'VITE_PORT')
}
if (-not $CoreOnly) {
  $Ports['OTel OTLP gRPC'] = @((Get-Conf 'OTLP_GRPC_PORT' '4317'), 'OTLP_GRPC_PORT')
  $Ports['OTel OTLP HTTP'] = @((Get-Conf 'OTLP_HTTP_PORT' '4318'), 'OTLP_HTTP_PORT')
  $Ports['Prometheus']     = @((Get-Conf 'PROMETHEUS_PORT' '9090'), 'PROMETHEUS_PORT')
  $Ports['Grafana']        = @((Get-Conf 'GRAFANA_PORT' '3001'), 'GRAFANA_PORT')
}
$DemoTenantId = '00000000-0000-4000-8000-0000000000de'
foreach ($k in @('POSTGRES_PORT','POSTGRES_USER','POSTGRES_PASSWORD','POSTGRES_DB','REDIS_PORT','KAFKA_PORT','MINIO_API_PORT','MINIO_CONSOLE_PORT','KEYCLOAK_PORT','KEYCLOAK_MGMT_PORT','OTLP_GRPC_PORT','OTLP_HTTP_PORT','PROMETHEUS_PORT','GRAFANA_PORT')) {
  [Environment]::SetEnvironmentVariable($k, (Get-Conf $k ([string]$composeConf[$k])))
}

$ComposeArgs = @('compose', '-f', 'infra/docker-compose/docker-compose.yml', '--env-file', $ComposeEnv, '--profile', 'core')
$InfraServices = @('postgres', 'redis', 'kafka', 'minio', 'keycloak')
if (-not $CoreOnly) {
  $ComposeArgs += @('--profile', 'observability')
  $InfraServices += @('otel-collector', 'prometheus', 'grafana')
}

Write-Host "==> EIP install - environment: $Env (Spring profile: $SpringProfile, Vite mode: $ViteMode)"

# --- [1/8] prerequisites ------------------------------------------------------------------------
Write-Host '==> [1/8] Checking prerequisites...'
$failures = @()
function Need([string]$Cmd, [string]$Hint) {
  if (-not (Get-Command $Cmd -ErrorAction SilentlyContinue)) { $script:failures += "missing: $Cmd - $Hint" }
}
Need 'docker' 'install Docker Desktop (https://docs.docker.com/get-docker/)'
Need 'java'   'install a Java 21 JDK (e.g. winget install EclipseAdoptium.Temurin.21.JDK)'
Need 'node'   'install Node.js >= 20 (winget install OpenJS.NodeJS.LTS)'
Need 'pnpm'   'install pnpm (corepack enable / npm i -g pnpm)'
if (Get-Command docker -ErrorAction SilentlyContinue) {
  docker info *> $null; if ($LASTEXITCODE -ne 0) { $failures += 'Docker daemon is not running - start Docker Desktop first' }
  docker compose version *> $null; if ($LASTEXITCODE -ne 0) { $failures += 'Docker Compose v2 plugin missing' }
}
if (Get-Command java -ErrorAction SilentlyContinue) {
  $jver = (& java -version 2>&1 | Select-String -Pattern 'version "(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }) | Select-Object -First 1
  if ([int]$jver -lt 21) { $failures += "Java $jver found - Java 21+ required" }
}
if (Get-Command node -ErrorAction SilentlyContinue) {
  $nver = ((& node -v) -replace '^v(\d+).*', '$1')
  if ([int]$nver -lt 20) { $failures += "Node $nver found - Node 20+ required" }
}
if ($failures.Count -gt 0) { $failures | ForEach-Object { Write-Host "  X $_" }; exit 1 }
Write-Host '    OK: docker + compose v2, java 21+, node 20+, pnpm'

# --- [2/8] port conflicts -----------------------------------------------------------------------
Write-Host '==> [2/8] Checking ports...'
$conflict = $false
foreach ($entry in $Ports.GetEnumerator()) {
  $port = [int]$entry.Value[0]; $override = $entry.Value[1]
  $inUse = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  if ($inUse) {
    $owner = (Get-Process -Id $inUse[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName
    Write-Host "  X $($entry.Key) port $port in use by: $owner   -> override:  `$env:$override=<free-port>; .\scripts\install\install.ps1 -Env $Env"
    $conflict = $true
  }
}
if ($conflict) { Write-Host '  Refusing to start - free the port(s) above or re-run with the printed overrides.'; exit 1 }
Write-Host '    OK: all ports free'

# --- [3/8] infra --------------------------------------------------------------------------------
Write-Host "==> [3/8] Starting infra ($($InfraServices -join ' '))..."
& docker @ComposeArgs up -d --wait --wait-timeout 900 @InfraServices
if ($LASTEXITCODE -ne 0) { Write-Error 'infra failed to start'; exit 1 }
& docker @ComposeArgs run --rm minio-init | Out-Null
Write-Host '    OK: infra healthy (incl. MinIO buckets)'

# --- [4/8] database role ------------------------------------------------------------------------
Write-Host '==> [4/8] Provisioning the RLS role (eip_app, NOBYPASSRLS)...'
Get-Content 'infra/docker-compose/postgres/demo-roles.sql' -Raw |
  & docker @ComposeArgs exec -T postgres psql -v ON_ERROR_STOP=1 -U $PgUser -d $PgDb | Out-Null

# --- [5/8] backend ------------------------------------------------------------------------------
Write-Host "==> [5/8] Building + starting eip-app (profile: $SpringProfile) on :$BackendPort..."
Push-Location backend
& .\gradlew.bat -q :eip-app:bootJar
if ($LASTEXITCODE -ne 0) { Pop-Location; Write-Error 'backend build failed'; exit 1 }
Pop-Location
$Jar = Get-ChildItem 'backend/eip-app/build/libs/*.jar' | Where-Object { $_.Name -notmatch '-plain' } | Select-Object -First 1
$backendEnv = @{
  EIP_DB_URL                 = "jdbc:postgresql://localhost:$PgPort/$PgDb"
  EIP_APP_DB_USER            = 'eip_app'
  EIP_APP_DB_PASSWORD        = 'eip_app_dev_pw'
  EIP_MIGRATOR_DB_USER       = $PgUser
  EIP_MIGRATOR_DB_PASSWORD   = $PgPassword
  EIP_OTLP_TRACES_ENDPOINT   = "http://localhost:$(Get-Conf 'OTLP_HTTP_PORT' '4318')/v1/traces"
  SERVER_PORT                = $BackendPort
  SPRING_PROFILES_ACTIVE     = $SpringProfile
}
foreach ($kv in $backendEnv.GetEnumerator()) { [Environment]::SetEnvironmentVariable($kv.Key, $kv.Value) }
$backend = Start-Process -FilePath 'java' -ArgumentList @('-jar', $Jar.FullName) `
  -RedirectStandardOutput "$StateDir/backend.log" -RedirectStandardError "$StateDir/backend.err.log" -PassThru -WindowStyle Hidden
$backend.Id | Out-File "$StateDir/backend.pid" -Encoding ascii
$BackendUrl = "http://localhost:$BackendPort"
$healthy = $false
Write-Host '    waiting for the API' -NoNewline
for ($i = 0; $i -lt 90; $i++) {
  try {
    $r = Invoke-RestMethod "$BackendUrl/actuator/health" -TimeoutSec 2
    if ($r.status -eq 'UP') { $healthy = $true; Write-Host ' - UP'; break }
  } catch { }
  Write-Host '.' -NoNewline; Start-Sleep -Seconds 2
}
if (-not $healthy) { Write-Host ''; Write-Error "backend not healthy - see $StateDir/backend.log"; exit 1 }

# --- [6/8] frontend -----------------------------------------------------------------------------
Write-Host "==> [6/8] Starting the frontend (mode: $ViteMode, serve: $FrontendServe) on :$FrontendPort..."
& pnpm --dir frontend install --frozen-lockfile | Out-Null
[Environment]::SetEnvironmentVariable('VITE_API_PROXY_TARGET', $BackendUrl)
if ($FrontendServe -eq 'preview') {
  Push-Location frontend; & pnpm exec vite build --mode $ViteMode | Out-Null; Pop-Location
  $feArgs = @('--dir', 'frontend', 'exec', 'vite', 'preview', '--port', $FrontendPort, '--strictPort')
} else {
  $feArgs = @('--dir', 'frontend', 'exec', 'vite', '--mode', $ViteMode, '--port', $FrontendPort, '--strictPort')
}
$frontend = Start-Process -FilePath 'pnpm' -ArgumentList $feArgs `
  -RedirectStandardOutput "$StateDir/frontend.log" -RedirectStandardError "$StateDir/frontend.err.log" -PassThru -WindowStyle Hidden
$frontend.Id | Out-File "$StateDir/frontend.pid" -Encoding ascii
$FrontendUrl = "http://localhost:$FrontendPort"
for ($i = 0; $i -lt 30; $i++) { try { Invoke-WebRequest $FrontendUrl -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep 1 } }

# --- [7/8] smoke: every live function -----------------------------------------------------------
Write-Host '==> [7/8] Smoke-testing the live surface...'
$smokeFail = $false
function Expect([string]$Desc, [scriptblock]$Check) {
  try { if (& $Check) { Write-Host "    OK: $Desc"; return } } catch { }
  Write-Host "    X  $Desc"; $script:smokeFail = $true
}
Expect 'actuator health UP'          { (Invoke-RestMethod "$BackendUrl/actuator/health").status -eq 'UP' }
Expect 'actuator prometheus metrics' { (Invoke-WebRequest "$BackendUrl/actuator/prometheus").Content -match 'eip_api' }
Expect 'OpenAPI contract served'     { (Invoke-WebRequest "$BackendUrl/v3/api-docs").Content -match '/api/v1/friction/summary' }
Expect 'missing tenant fails closed (401)' {
  try { Invoke-WebRequest "$BackendUrl/api/v1/session" | Out-Null; $false }
  catch { $_.Exception.Response.StatusCode.value__ -eq 401 }
}
if ($Seeded -eq '1') {
  $H = @{ 'X-EIP-Tenant' = $DemoTenantId }
  Expect 'session (tenant identity)' { (Invoke-RestMethod "$BackendUrl/api/v1/session" -Headers $H).tenantId -eq $DemoTenantId }
  Expect 'connectors list (paged)'   { $null -ne (Invoke-RestMethod "$BackendUrl/api/v1/connectors?limit=2" -Headers $H).hasMore }
  $summary = $null
  Expect 'computed friction summary' { $script:summary = Invoke-RestMethod "$BackendUrl/api/v1/friction/summary" -Headers $H; $summary.metricVersion -eq 'engineering_friction_v0.1' }
  Expect 'evidence drill-down'       { $teamId = $summary.teams[0].teamId; $null -ne (Invoke-RestMethod "$BackendUrl/api/v1/friction/teams/$teamId/evidence" -Headers $H).items }
}
Expect 'frontend UI served' { (Invoke-WebRequest $FrontendUrl).Content -match '<div id="root"' }
if ($smokeFail) { Write-Error "smoke test failed - logs: $StateDir/backend.log, $StateDir/frontend.log"; exit 1 }

# --- [8/8] state + summary ----------------------------------------------------------------------
@("EIP_ENV=$Env", "BACKEND_PORT=$BackendPort", "FRONTEND_PORT=$FrontendPort", "POSTGRES_PORT=$PgPort") |
  Out-File "$StateDir/state" -Encoding ascii
Write-Host ''
Write-Host '  =============================================================================='
Write-Host "   EIP is running - environment: $Env (SIMULATION data source; no real connectors yet)"
Write-Host ''
Write-Host "     Admin panel  $FrontendUrl/#admin   <- manage tenants & integrations"
Write-Host "     Frontend     $FrontendUrl      (Overview: metrics)"
Write-Host "     Backend API  $BackendUrl/api/v1        OpenAPI: $BackendUrl/v3/api-docs"
Write-Host "     Health       $BackendUrl/actuator/health"
if ($Seeded -eq '1') {
  Write-Host "     Demo tenant  $DemoTenantId"
  Write-Host '                  (preloaded in the UI; teams Platform 91 > Payments 56 > Web 50)'
}
Write-Host "     PostgreSQL   postgresql://${PgUser}:***@localhost:$PgPort/$PgDb   (app role: eip_app, RLS enforced)"
Write-Host "     Keycloak     http://localhost:$(Get-Conf 'KEYCLOAK_PORT' '8180')   (admin / admin_dev_pw)"
Write-Host "     MinIO        http://localhost:$(Get-Conf 'MINIO_CONSOLE_PORT' '9001')   (eip_minio / eip_minio_dev_pw)"
if (-not $CoreOnly) {
  Write-Host "     Prometheus   http://localhost:$(Get-Conf 'PROMETHEUS_PORT' '9090')"
  Write-Host "     Grafana      http://localhost:$(Get-Conf 'GRAFANA_PORT' '3001')   (admin / admin_dev_pw)"
}
Write-Host ''
Write-Host '     Stop:        .\scripts\install\stop.ps1        (keeps data)'
Write-Host '     Uninstall:   .\scripts\install\uninstall.ps1   (DESTRUCTIVE: removes volumes)'
Write-Host '  =============================================================================='
Start-Process "$FrontendUrl/#admin" -ErrorAction SilentlyContinue
