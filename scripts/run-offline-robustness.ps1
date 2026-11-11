param(
    [string]$InputFile,
    [string]$PlanFile,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot 'backend'
if (-not $InputFile) { $InputFile = Join-Path $backendDirectory 'src/test/resources/experiments/synthetic-cultural-nodes-v1.json' }
if (-not $PlanFile) { $PlanFile = Join-Path $backendDirectory 'src/test/resources/experiments/synthetic-robustness-plan-v1.json' }
$InputFile = (Resolve-Path -LiteralPath $InputFile).Path
$PlanFile = (Resolve-Path -LiteralPath $PlanFile).Path
if (-not $OutputDirectory) {
    $runName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $OutputDirectory = Join-Path $backendDirectory ('target/offline-robustness/' + $runName)
}
$OutputDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw '输出目录已存在，不覆盖旧实验。' }

Push-Location -LiteralPath $backendDirectory
try {
    # 只使用已缓存的依赖，不加载 Spring，不运行数据库测试。
    & .\mvnw.cmd -o -q test-compile 'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath' '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/offline-fairness-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw '离线编译或依赖准备失败，实验未运行。' }
    $dependencies = (Get-Content -LiteralPath 'target/offline-fairness-classpath.txt' -Raw).Trim()
    $classpath = (Join-Path $backendDirectory 'target/test-classes') + [IO.Path]::PathSeparator + (Join-Path $backendDirectory 'target/classes') + [IO.Path]::PathSeparator + $dependencies
    [IO.Directory]::CreateDirectory((Split-Path -Parent $OutputDirectory)) | Out-Null
    & java '-Xmx512m' '-Dfile.encoding=UTF-8' '-cp' $classpath 'com.example.personality.experiment.OfflineRobustnessMain' $InputFile $PlanFile $OutputDirectory $repositoryRoot
    if ($LASTEXITCODE -ne 0) { throw '组合实验未完成；请保留现场，不使用不完整报告。' }
}
finally { Pop-Location }
