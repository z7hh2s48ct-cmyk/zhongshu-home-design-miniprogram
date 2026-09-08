[CmdletBinding()]
param(
    [switch]$DeleteData
)

$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDirectory = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory '..\..')).Path
$composeFile = Join-Path $backendDirectory 'compose.dev.yml'
$envFile = Join-Path $backendDirectory '.env'
$pidFile = Join-Path $backendDirectory 'target\local-dev\backend.pid'

Set-Location -LiteralPath $backendDirectory

if (Test-Path -LiteralPath $pidFile) {
    $backendPid = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    $backendProcess = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
    if ($backendProcess) {
        Stop-Process -Id $backendPid
        $backendProcess.WaitForExit(15000)
    }
    Remove-Item -LiteralPath $pidFile -Force
}

$composeArguments = @('compose')
if (Test-Path -LiteralPath $envFile) {
    $composeArguments += @('--env-file', $envFile)
}
$composeArguments += @('-f', $composeFile, 'down')
if ($DeleteData) {
    Write-Warning '将删除本机 PostgreSQL 与 Redis 开发卷；数据不可恢复。'
    $composeArguments += '--volumes'
}

& docker @composeArguments
if ($LASTEXITCODE -ne 0) {
    throw '本地依赖停止失败。'
}
