$ErrorActionPreference = 'Stop'
$auditDir = $PSScriptRoot
$repoDir = (Resolve-Path -LiteralPath (Join-Path $auditDir '../..')).Path
$javaBin = 'C:/Program Files/Android/Android Studio/jbr/bin'
$reportFile = Join-Path $repoDir '后端程序/yudao-server/target/surefire-reports/TEST-cn.iocoder.yudao.ControllerWiringContractTest.xml'
$report = [xml](Get-Content -LiteralPath $reportFile -Raw)
$classPath = ($report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
if (-not $classPath) { throw 'Run backend tests first to generate the current reactor classpath.' }
$classes = Join-Path $auditDir 'probe-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
function Quote-JavaArg([string]$value) { return '"' + $value.Replace('\','\\').Replace('"','\"') + '"' }
$compileArgs = @('-encoding','UTF-8','-proc:none','-cp',(Quote-JavaArg $classPath),'-d',(Quote-JavaArg $classes),(Quote-JavaArg (Join-Path $auditDir 'AuditRiskProbes.java')))
$compileFile = Join-Path $auditDir 'probe-compile.args'
Set-Content -LiteralPath $compileFile -Value $compileArgs -Encoding utf8
& (Join-Path $javaBin 'javac.exe') "@$compileFile" *> (Join-Path $auditDir 'probe-compile.log')
if ($LASTEXITCODE -ne 0) {Get-Content (Join-Path $auditDir 'probe-compile.log');exit $LASTEXITCODE}
$env:CLASSPATH = $classes+';'+$classPath
& (Join-Path $javaBin 'java.exe') '-Dfile.encoding=UTF-8' AuditRiskProbes *> (Join-Path $auditDir 'risk-probes.log')
$probeExit = $LASTEXITCODE
Get-Content -LiteralPath (Join-Path $auditDir 'risk-probes.log') -Tail 25
Write-Output "PROBE_EXIT=$probeExit"
exit $probeExit
