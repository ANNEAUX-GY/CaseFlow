# 导出预览图（PowerShell COM 偶发失败，重试三次）
param([int]$Tries = 3)
$src = "D:\案件指派demo\ppt\案件指派系统技术汇报\案件指派系统技术汇报.pptx"
$out = "D:\案件指派demo\ppt\案件指派系统技术汇报\preview"
for ($t = 1; $t -le $Tries; $t++) {
  try {
    if (Test-Path $out) { Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue }
    New-Item -ItemType Directory -Path $out -ErrorAction Stop | Out-Null
    $ppt = New-Object -ComObject PowerPoint.Application
    try {
      $pres = $ppt.Presentations.Open($src, $true, $false, $false)
      $i = 1
      foreach ($s in $pres.Slides) {
        $n = "{0:D2}" -f $i
        $s.Export("$out\p$n.png", "PNG", 1600, 900)
        $i++
      }
      $pres.Close()
      Write-Output "OK $($i-1)"
      exit 0
    } finally {
      $ppt.Quit()
      [System.Runtime.InteropServices.Marshal]::ReleaseComObject($ppt) | Out-Null
    }
  } catch {
    Write-Output "第 $t 次失败: $($_.Exception.Message)"
    Start-Sleep -Seconds 3
    Get-Process POWERPNT -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Seconds 2
  }
}
Write-Output "全部失败"
exit 1
