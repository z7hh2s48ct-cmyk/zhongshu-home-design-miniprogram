$ErrorActionPreference = 'Stop'
$auditDir=$PSScriptRoot
$repoDir=(Resolve-Path -LiteralPath (Join-Path $auditDir '../..')).Path
$backendDir=Join-Path $repoDir '后端程序'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:ZS_PG_PORT='55432'
$env:ZS_PG_URL='jdbc:postgresql://127.0.0.1:55432/zhongshu_design'
$env:ZS_PG_USERNAME='zhongshu'
$env:ZS_PG_PASSWORD='zhongshu'
$env:ZS_REDIS_PORT='56379'
$env:ZS_E2E_BASE_URL='http://127.0.0.1:48113'
$env:ZS_E2E_ASSET_ROOT=Join-Path $auditDir 'e2e-assets'
$env:ZS_E2E_REPORT_DIR=Join-Path $auditDir 'e2e'
$env:ZS_E2E_POLL_TIMEOUT_MS='60000'
$compose=Join-Path $backendDir 'compose.dev.yml'
$example=Join-Path $backendDir '.env.example'
$composeProject='zs-audit-20260913'
$backendProcess=$null
$result=1
try {
  Set-Location -LiteralPath $backendDir
  & docker compose -p $composeProject --env-file $example -f $compose up -d --wait *> (Join-Path $auditDir 'e2e-compose.log')
  if($LASTEXITCODE -ne 0){throw 'Isolated Compose startup failed; see e2e-compose.log'}
  & ./mvnw.cmd -B -o -DskipTests package *> (Join-Path $auditDir 'backend-package.log')
  if($LASTEXITCODE -ne 0){throw 'Backend package failed; see backend-package.log'}
  $javaArgs=@('-jar','yudao-server/target/yudao-server.jar','--spring.profiles.active=local,pg,zsdev','--server.address=127.0.0.1','--server.port=48113',"--zhongshu.design.asset.storage-root=$env:ZS_E2E_ASSET_ROOT")
  $backendProcess=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList $javaArgs -WorkingDirectory $backendDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $auditDir 'e2e-backend.stdout.log') -RedirectStandardError (Join-Path $auditDir 'e2e-backend.stderr.log')
  $ready=$false
  for($i=0;$i -lt 60;$i++){
    if($backendProcess.HasExited){throw 'Isolated backend exited before readiness'}
    try {$r=Invoke-RestMethod -Uri 'http://127.0.0.1:48113/app-api/design/v1/home' -Headers @{'tenant-id'='1'} -TimeoutSec 2;if($r.code -eq 0){$ready=$true;break}}catch{}
    Start-Sleep -Seconds 2
  }
  if(-not $ready){throw 'Isolated backend readiness timed out'}
  Set-Location -LiteralPath $repoDir
  & node e2e/zs-e2e.mjs *> (Join-Path $auditDir 'e2e-run.log')
  $result=$LASTEXITCODE
  Get-Content (Join-Path $auditDir 'e2e-run.log') -Tail 18
} finally {
  if($backendProcess -and -not $backendProcess.HasExited){Stop-Process -Id $backendProcess.Id}
  & docker compose -p $composeProject --env-file $example -f $compose down --volumes *> (Join-Path $auditDir 'e2e-cleanup.log')
  Set-Location -LiteralPath $repoDir
}
Write-Output "ISOLATED_E2E_EXIT=$result"
exit $result
