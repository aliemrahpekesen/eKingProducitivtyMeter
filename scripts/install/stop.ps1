#Requires -Version 5.1
# Stops everything install.ps1 started (backend, frontend, infra containers), KEEPING data volumes.
$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $Root
$StateDir = '.install'
foreach ($app in @('backend', 'frontend')) {
  $pidFile = "$StateDir/$app.pid"
  if (Test-Path $pidFile) {
    $procId = Get-Content $pidFile
    Stop-Process -Id $procId -ErrorAction SilentlyContinue
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    Write-Host "stopped $app (pid $procId)"
  }
}
$ComposeEnv = 'infra/docker-compose/.env'
if (-not (Test-Path $ComposeEnv)) { Copy-Item 'infra/docker-compose/.env.example' $ComposeEnv }
& docker compose -f infra/docker-compose/docker-compose.yml --env-file $ComposeEnv `
  --profile core --profile observability stop
Write-Host 'infra stopped (volumes kept) - restart with .\scripts\install\install.ps1'
