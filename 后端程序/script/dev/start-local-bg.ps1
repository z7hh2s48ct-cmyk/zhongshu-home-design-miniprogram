# Detached local-dev launcher: starts backend jar + ai-runtime engine as
# system-level processes that survive the calling shell/agent session.
# Paths are derived from $PSScriptRoot (no literals, no encoding pitfalls).
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File start-local-bg.ps1
# Stop:   powershell -NoProfile -ExecutionPolicy Bypass -File stop-local-bg.ps1
$ErrorActionPreference = 'Stop'

$devDir = $PSScriptRoot
$backendDir = Split-Path -Parent (Split-Path -Parent $devDir)
$engineDir = Join-Path (Split-Path -Parent $backendDir) 'ai-runtime'
$runtime = Join-Path $backendDir 'target\local-dev'
New-Item -ItemType Directory -Force $runtime | Out-Null

foreach ($existing in @('backend.pid', 'engine.pid')) {
    $pidFile = Join-Path $runtime $existing
    if (Test-Path $pidFile) {
        $old = Get-Content $pidFile -ErrorAction SilentlyContinue
        if ($old -and (Get-Process -Id $old -ErrorAction SilentlyContinue)) {
            throw "already running: $existing pid=$old (run stop-local-bg.ps1 first)"
        }
        Remove-Item $pidFile -Force
    }
}

# Load .env into process env for both children
# -Encoding UTF8 is mandatory: PS 5.1 otherwise decodes the UTF-8 file as GBK and
# a Chinese comment's trailing byte can swallow the next LF, silently merging the
# following VAR=... line into the comment (ZS_COS_ENDPOINT was lost this way).
Get-Content -Encoding UTF8 (Join-Path $backendDir '.env') | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $i = $line.IndexOf('=')
        if ($i -gt 0) {
            [Environment]::SetEnvironmentVariable($line.Substring(0, $i).Trim(), $line.Substring($i + 1).Trim(), 'Process')
        }
    }
}
$env:PATH = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin;' + $env:PATH

# Backend (jar built via mvnw package; profiles local,pg,zsdev)
$backend = Start-Process -FilePath 'java' `
    -ArgumentList @('-jar', (Join-Path $backendDir 'yudao-server\target\yudao-server.jar'), '--spring.profiles.active=local,pg,zsdev') `
    -WorkingDirectory $backendDir -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $runtime 'backend.stdout.log') `
    -RedirectStandardError (Join-Path $runtime 'backend.stderr.log') -PassThru
Set-Content (Join-Path $runtime 'backend.pid') $backend.Id -Encoding ascii

# AI runtime engine (dev: backend uses COS asset channel (c9caf498), so the engine
# must run in https mode with the bucket origin whitelisted — local-fs mode only
# understands local:// URLs and every job would fail with STORAGE_ORIGIN.
$env:ZS_AI_CORE_URL = 'http://127.0.0.1:48080'
$env:ZS_INTERNAL_SECRET = 'zsdev-internal-secret-0123456789abcdef'
$env:ZS_AI_DAILY_CALL_LIMIT = '50'
$env:ZS_AI_STORAGE_MODE = 'https'
$bucketOrigin = ('https://{0}.cos.{1}.myqcloud.com' -f $env:ZS_COS_BUCKET, $env:ZS_COS_REGION)
$env:ZS_AI_STORAGE_ORIGINS = $bucketOrigin
$env:ZS_AI_JOURNAL_DIR = Join-Path $env:TEMP 'zs-ai-journal'
$env:ZS_AI_IMAGE_URL_ORIGINS = 'https://webstatic.aiproxy.vip'
$env:ZS_AI_IMAGE_QUALITY = 'high'
$env:ZS_AI_IMAGE_SIZE_FLAT = '2160x3840'
$env:ZS_AI_REQUEST_TIMEOUT_MS = '600000'
$engine = Start-Process -FilePath 'node' -ArgumentList 'src/main.mjs' `
    -WorkingDirectory $engineDir -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $runtime 'engine.stdout.log') `
    -RedirectStandardError (Join-Path $runtime 'engine.stderr.log') -PassThru
Set-Content (Join-Path $runtime 'engine.pid') $engine.Id -Encoding ascii

"backend pid=$($backend.Id)  engine pid=$($engine.Id)"
"logs: $runtime"
