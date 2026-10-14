$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$PidFile = Join-Path $Root "logs\caseflow.pid"
if (-not (Test-Path -LiteralPath $PidFile)) {
    Write-Host "CaseFlow 当前没有 PID 文件。"
    exit 0
}
$PidValue = Get-Content -LiteralPath $PidFile | Select-Object -First 1
$Process = Get-Process -Id ([int]$PidValue) -ErrorAction SilentlyContinue
if ($Process) {
    Stop-Process -Id $Process.Id -Force
    Write-Host "已停止 CaseFlow（PID $($Process.Id)）"
} else {
    Write-Host "进程 $PidValue 已不存在。"
}
Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
