$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'collect_xingrui_test.sh'
$deviceScript = '/sdcard/Download/collect_xingrui_test.sh'
$out = Join-Path (Get-Location) ('xingrui-logs-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
adb get-state
if ($LASTEXITCODE -ne 0) { throw 'ADB device not connected' }
adb push $script $deviceScript
if ($LASTEXITCODE -ne 0) { throw 'Collector upload failed' }
adb shell sh $deviceScript start
if ($LASTEXITCODE -ne 0) { throw 'Capture did not start; check existing capture status' }
Read-Host 'Test wireless music, wheel keys, Ultra on/off; press ENTER when finished'
adb shell sh $deviceScript stop
$ready = $false
for ($i = 0; $i -lt 50; $i++) {
    $status = adb shell sh $deviceScript status
    Write-Host ($status -join "`n")
    if (($status -join "`n") -match '^READY:') { $ready = $true; break }
    if (($status -join "`n") -match '^ERROR:') { throw 'Capture worker failed; retain the capture folder' }
    Start-Sleep -Seconds 3
}
if (-not $ready) { throw 'Report still finalizing. Run collector status later; do not restart capture.' }
adb pull /sdcard/Download/XingruiLogs/xingrui_carplay_test.txt $out
if ($LASTEXITCODE -ne 0) { throw 'Log download failed' }
adb pull /sdcard/Download/XingruiLogs/xingrui_props.txt $out
if ($LASTEXITCODE -ne 0) { throw 'Properties download failed' }
Write-Host "Send the two files in $out"
