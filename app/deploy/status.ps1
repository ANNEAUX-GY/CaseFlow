$Root = Split-Path -Parent $PSScriptRoot
$PidFile = Join-Path $Root "logs\caseflow.pid"
if (-not (Test-Path -LiteralPath $PidFile)) {
    Write-Host "CaseFlow 未运行（没有 PID 文件）。"
    exit 1
}
$PidValue = Get-Content -LiteralPath $PidFile | Select-Object -First 1
$Process = Get-Process -Id ([int]$PidValue) -ErrorAction SilentlyContinue
if ($Process) {
    Write-Host "CaseFlow 正在运行：PID $($Process.Id)，启动时间 $($Process.StartTime)"
    exit 0
}
Write-Host "CaseFlow 未运行：PID 文件记录 $PidValue，但进程不存在。"
exit 1
