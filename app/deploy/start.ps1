param(
    [ValidateSet("dev", "mysql")]
    [string]$Profile = "dev",
    [int]$Port = 8080,
    [string]$BindAddress = "127.0.0.1",
    [string]$JavaHome = "",
    [switch]$Foreground
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Jar = Join-Path $Root "app\case-flow-backend.jar"
$LogDir = Join-Path $Root "logs"
$UploadDir = if ($env:CASEFLOW_UPLOAD_DIR) { $env:CASEFLOW_UPLOAD_DIR } else { Join-Path $Root "data\uploads" }
$PidFile = Join-Path $LogDir "caseflow.pid"
$LogFile = Join-Path $LogDir "caseflow.out.log"
$ErrorLogFile = Join-Path $LogDir "caseflow.err.log"

if (-not (Test-Path -LiteralPath $Jar)) { throw "未找到部署 JAR：$Jar" }
New-Item -ItemType Directory -Force -Path $LogDir, $UploadDir | Out-Null

if ($JavaHome) {
    $Java = Join-Path $JavaHome "bin\java.exe"
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $Java = Join-Path $env:JAVA_HOME "bin\java.exe"
} else {
    $Java = (Get-Command java -ErrorAction SilentlyContinue).Source
}
if (-not $Java -or -not (Test-Path -LiteralPath $Java)) { throw "未找到 Java 8+，请安装 JDK/JRE 或设置 JAVA_HOME" }

$Arguments = @(
    "-Dfile.encoding=UTF-8",
    "-Xmx1536m",
    "-jar", $Jar,
    "--spring.profiles.active=$Profile",
    "--server.port=$Port",
    "--server.address=$BindAddress",
    "--caseflow.upload-dir=$UploadDir"
)

if ($Foreground) {
    Write-Host "CaseFlow 正在前台运行：http://$BindAddress`:$Port/api/"
    & $Java @Arguments
    exit $LASTEXITCODE
}

if (Test-Path -LiteralPath $PidFile) {
    $oldPid = Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue
    if ($oldPid -and (Get-Process -Id ([int]$oldPid) -ErrorAction SilentlyContinue)) {
        throw "CaseFlow 已在运行（PID $oldPid），先执行 .\stop.ps1"
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

$Process = Start-Process -FilePath $Java -ArgumentList $Arguments -WorkingDirectory $Root `
    -RedirectStandardOutput $LogFile -RedirectStandardError $ErrorLogFile -PassThru
$Process.Id | Set-Content -LiteralPath $PidFile -Encoding ascii
Write-Host "CaseFlow 已启动（PID $($Process.Id)）"
Write-Host "访问地址：http://$BindAddress`:$Port/api/"
Write-Host "日志：$LogFile"
