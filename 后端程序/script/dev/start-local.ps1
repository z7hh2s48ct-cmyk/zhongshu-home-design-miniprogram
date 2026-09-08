[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [int]$HealthTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDirectory = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory '..\..')).Path
$composeFile = Join-Path $backendDirectory 'compose.dev.yml'
$envFile = Join-Path $backendDirectory '.env'
$envExampleFile = Join-Path $backendDirectory '.env.example'
$wrapper = Join-Path $backendDirectory 'mvnw.cmd'
$jarFile = Join-Path $backendDirectory 'yudao-server\target\yudao-server.jar'
$runtimeDirectory = Join-Path $backendDirectory 'target\local-dev'
$pidFile = Join-Path $runtimeDirectory 'backend.pid'
$stdoutLog = Join-Path $runtimeDirectory 'backend.stdout.log'
$stderrLog = Join-Path $runtimeDirectory 'backend.stderr.log'

Set-Location -LiteralPath $backendDirectory

if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath $envExampleFile -Destination $envFile
    Write-Host '已从 .env.example 创建本机 .env。'
}

foreach ($rawLine in Get-Content -LiteralPath $envFile) {
    $line = $rawLine.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith('#')) {
        continue
    }
    $separatorIndex = $line.IndexOf('=')
    if ($separatorIndex -le 0) {
        throw "无效的 .env 行：$rawLine"
    }
    $name = $line.Substring(0, $separatorIndex).Trim()
    $value = $line.Substring($separatorIndex + 1).Trim()
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

$javaVersionText = (& java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersionText -notmatch 'version "(?<major>\d+)') {
    throw '未检测到可用 JDK；请安装 JDK 17 并配置 PATH/JAVA_HOME。'
}
if ([int]$Matches.major -ne 17) {
    throw "当前 Java 主版本为 $($Matches.major)，项目要求 JDK 17。"
}

docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker daemon 不可用，请先启动 Docker Desktop 或 Docker Engine。'
}

& docker compose --env-file $envFile -f $composeFile up -d --wait
if ($LASTEXITCODE -ne 0) {
    throw 'PostgreSQL/Redis 启动或健康检查失败。'
}

if (-not $SkipBuild) {
    & $wrapper -B -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw '后端 Maven 编译失败。'
    }
}

if (-not (Test-Path -LiteralPath $jarFile)) {
    throw "未找到可运行包：$jarFile"
}

New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
if (Test-Path -LiteralPath $pidFile) {
    $existingPid = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    if (Get-Process -Id $existingPid -ErrorAction SilentlyContinue) {
        throw "后端已经运行，PID=$existingPid。"
    }
    Remove-Item -LiteralPath $pidFile -Force
}

$backendProcess = Start-Process `
    -FilePath 'java' `
    -ArgumentList @('-jar', $jarFile, '--spring.profiles.active=local,pg,zsdev') `
    -WorkingDirectory $backendDirectory `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden `
    -PassThru

Set-Content -LiteralPath $pidFile -Value $backendProcess.Id -Encoding ascii
$healthUrl = 'http://127.0.0.1:48080/actuator/health'
$deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
$isHealthy = $false

try {
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($backendProcess.HasExited) {
            $stderrTail = if (Test-Path -LiteralPath $stderrLog) {
                (Get-Content -LiteralPath $stderrLog -Tail 40) -join [Environment]::NewLine
            } else {
                '(无错误日志)'
            }
            throw "后端在健康检查前退出，exitCode=$($backendProcess.ExitCode)。`n$stderrTail"
        }

        try {
            $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
            if ($health.status -eq 'UP') {
                $isHealthy = $true
                break
            }
        } catch {
            # 服务仍在启动；到超时点再统一报告。
        }
        Start-Sleep -Seconds 2
    }

    if (-not $isHealthy) {
        throw "后端在 $HealthTimeoutSeconds 秒内未达到 UP；请检查 $stderrLog。"
    }
} finally {
    if (-not $isHealthy) {
        if (-not $backendProcess.HasExited) {
            Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "后端已就绪：$healthUrl，PID=$($backendProcess.Id)"
Write-Host "日志目录：$runtimeDirectory"
