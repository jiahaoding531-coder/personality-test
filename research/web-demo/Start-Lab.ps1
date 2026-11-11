param([switch]$NoOpen)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$node = (Get-Command node -ErrorAction Stop).Source
$url = 'http://127.0.0.1:5186/'
$listener = Get-NetTCPConnection -State Listen -LocalPort 5186 -ErrorAction SilentlyContinue
if ($listener) {
    $running = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $listener.OwningProcess)
    $expected = Join-Path $root 'server.mjs'
    if ($running.Name -ne 'node.exe' -or $running.CommandLine -notlike ('*' + $expected + '*')) {
        throw '5186由其他服务占用，未停止任何进程。请先核对占用程序。'
    }
    Write-Output '本机实验服务已在运行。若刚修改代码，请先关闭旧服务再重新启动。'
} else {
    Push-Location -LiteralPath $root
    try {
        & $node 'lab-build.mjs'
        if ($LASTEXITCODE -ne 0) { throw '独立Java构建失败，未启动新服务。' }
        & $node 'build.mjs'
        if ($LASTEXITCODE -ne 0) { throw '网页构建失败，未启动新服务。' }
    } finally { Pop-Location }
    $logs = Join-Path $root ('lab-cache\logs\' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($logs) | Out-Null
    $started = Start-Process -FilePath $node -ArgumentList ('"' + (Join-Path $root 'server.mjs') + '"') -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'server.log') -RedirectStandardError (Join-Path $logs 'error.log') -PassThru
    Write-Output ('本机实验进程已创建，正在自检，进程编号 ' + $started.Id)
}
& $node (Join-Path $root 'check-lab.mjs')
if ($LASTEXITCODE -ne 0) { throw '本机环境自检未通过，未打开演示页面。请查看检查代码；不会自动终止其他进程或修改配置。' }
Write-Output $url
Write-Output '仅当前电脑可访问。不连接数据库、高德或模型，不提供手机公网地址。'
if (-not $NoOpen) { Start-Process $url }
