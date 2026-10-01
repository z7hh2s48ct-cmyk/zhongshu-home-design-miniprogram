# Stops the detached backend / engine started by start-local-bg.ps1.
$devDir = $PSScriptRoot
$backendDir = Split-Path -Parent (Split-Path -Parent $devDir)
$runtime = Join-Path $backendDir 'target\local-dev'
foreach ($name in @('engine.pid', 'backend.pid')) {
    $pidFile = Join-Path $runtime $name
    if (Test-Path $pidFile) {
        $id = Get-Content $pidFile -ErrorAction SilentlyContinue
        if ($id -and (Get-Process -Id $id -ErrorAction SilentlyContinue)) {
            Stop-Process -Id $id -Force
            "stopped $name pid=$id"
        } else {
            "$name pid=$id not running"
        }
        Remove-Item $pidFile -Force
    } else {
        "$name not found"
    }
}
