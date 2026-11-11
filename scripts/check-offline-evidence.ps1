param(
    [string]$InputFile,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot 'backend'
if (-not $InputFile) { $InputFile = Join-Path $repositoryRoot 'docs/experiments/data/yexian-pilot-evidence-v1.json' }
$InputFile = (Resolve-Path -LiteralPath $InputFile).Path
if (-not $OutputDirectory) {
    $runName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $OutputDirectory = Join-Path $backendDirectory ('target/offline-evidence-readiness/' + $runName)
}
$OutputDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw '输出目录已存在，不覆盖旧检查。' }

Push-Location -LiteralPath $backendDirectory
try {
    # 仅使用缓存依赖，不启动 Spring，不连接数据库、不访问来源网页。
    & .\mvnw.cmd -o -q test-compile 'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath' '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/offline-fairness-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw '离线编译或依赖准备失败，检查未运行。' }
    $dependencies = (Get-Content -LiteralPath 'target/offline-fairness-classpath.txt' -Raw).Trim()
    $classpath = (Join-Path $backendDirectory 'target/test-classes') + [IO.Path]::PathSeparator + (Join-Path $backendDirectory 'target/classes') + [IO.Path]::PathSeparator + $dependencies
    [IO.Directory]::CreateDirectory((Split-Path -Parent $OutputDirectory)) | Out-Null
    & java '-Xmx512m' '-Dfile.encoding=UTF-8' '-cp' $classpath 'com.example.personality.experiment.OfflineEvidenceReadinessMain' $InputFile $OutputDirectory $repositoryRoot
    if ($LASTEXITCODE -eq 3) { throw '检查发现登记结构错误；诊断报告已保留，未授予实验资格。' }
    if ($LASTEXITCODE -ne 0) { throw '检查未完成；请保留现场，不使用不完整报告。' }
}
finally { Pop-Location }
