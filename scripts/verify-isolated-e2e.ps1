param([string]$RunName = 'rg1')
$ErrorActionPreference = 'Stop'
if ($RunName -notmatch '^[a-z0-9-]+$') { throw 'RunName must be lowercase letters/digits/hyphens' }
$repoDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendDir = Join-Path $repoDir '后端程序'
$artifactDir = Join-Path $repoDir "artifacts/risk-remediation/$RunName-e2e"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 before running' }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:ZS_PG_PORT = '55433'
$env:ZS_PG_URL = 'jdbc:postgresql://127.0.0.1:55433/zhongshu_design'
$env:ZS_PG_USERNAME = 'zhongshu'
$env:ZS_PG_PASSWORD = 'zhongshu'
$env:ZS_REDIS_PORT = '56380'
$env:ZS_E2E_BASE_URL = 'http://127.0.0.1:48114'
$env:ZS_E2E_ASSET_ROOT = Join-Path $artifactDir 'assets'
$env:ZS_E2E_REPORT_DIR = Join-Path $artifactDir 'results'
$env:ZS_E2E_POLL_TIMEOUT_MS = '60000'
$compose = Join-Path $backendDir 'compose.dev.yml'
$example = Join-Path $backendDir '.env.example'
$composeProject = "zs-risk-$RunName"
$backendProcess = $null
$result = 1
try {
    Set-Location -LiteralPath $backendDir
    & docker compose -p $composeProject --env-file $example -f $compose up -d --wait *> (Join-Path $artifactDir 'compose.log')
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Compose startup failed' }
    & ./mvnw.cmd -B -o -DskipTests package *> (Join-Path $artifactDir 'package.log')
    if ($LASTEXITCODE -ne 0) { throw 'Backend package failed' }
    # Start twice against the same newly created DB: initialization and repeat-start idempotency.
    foreach ($attempt in 1..2) {
        $javaArgs = @('-jar','yudao-server/target/yudao-server.jar','--spring.profiles.active=local,pg,zsdev',
            '--server.address=127.0.0.1','--server.port=48114',
            "--zhongshu.design.asset.storage-root=`"$env:ZS_E2E_ASSET_ROOT`"")
        $backendProcess = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList $javaArgs -WorkingDirectory $backendDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $artifactDir "backend-$attempt.stdout.log") -RedirectStandardError (Join-Path $artifactDir "backend-$attempt.stderr.log")
        $ready = $false
        for ($i=0; $i -lt 60; $i++) {
            if ($backendProcess.HasExited) { throw "Backend startup $attempt failed" }
            try {
                $r = Invoke-RestMethod -Uri "$env:ZS_E2E_BASE_URL/app-api/design/v1/home" -Headers @{'tenant-id'='1'} -TimeoutSec 2
                if ($r.code -eq 0) { $ready=$true; break }
            } catch {}
            Start-Sleep -Seconds 2
        }
        if (-not $ready) { throw "Backend startup $attempt timed out" }
        if ($attempt -eq 1) { Stop-Process -Id $backendProcess.Id; $backendProcess.WaitForExit() }
    }
    Set-Location -LiteralPath $repoDir
    & node e2e/zs-e2e.mjs *> (Join-Path $artifactDir 'run.log')
    $result = $LASTEXITCODE
    Get-Content (Join-Path $artifactDir 'run.log') -Tail 18
} finally {
    if ($backendProcess -and -not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id }
    # Delete only the test project's volumes created above; never touch other Compose projects.
    & docker compose -p $composeProject --env-file $example -f $compose down --volumes *> (Join-Path $artifactDir 'cleanup.log')
    Set-Location -LiteralPath $repoDir
}
Write-Output "ISOLATED_E2E_EXIT=$result"
exit $result
