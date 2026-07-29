#Requires -Version 5.1
# DESTRUCTIVE teardown: stops the apps, removes all EIP containers AND data volumes.
[CmdletBinding()]
param([switch]$Yes)
$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $Root
if (-not $Yes) {
  $answer = Read-Host "This DELETES all EIP containers and data volumes (Postgres data included). Type 'yes' to continue"
  if ($answer -ne 'yes') { Write-Host 'aborted'; exit 1 }
}
& "$PSScriptRoot/stop.ps1"
$ComposeEnv = 'infra/docker-compose/.env'
if (-not (Test-Path $ComposeEnv)) { Copy-Item 'infra/docker-compose/.env.example' $ComposeEnv }
& docker compose -f infra/docker-compose/docker-compose.yml --env-file $ComposeEnv `
  --profile core --profile observability down -v --remove-orphans
Remove-Item -Recurse -Force '.install' -ErrorAction SilentlyContinue
Write-Host 'EIP uninstalled (containers + volumes removed)'
