$ErrorActionPreference = 'Stop'

$OutDir = Join-Path (Get-Location) 'xingrui-logs'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$MainLog = Join-Path $OutDir 'xingrui_carplay_test.txt'
$PropsLog = Join-Path $OutDir 'xingrui_props.txt'
$LogcatTmp = Join-Path $OutDir '_logcat.tmp'
$GeteventTmp = Join-Path $OutDir '_getevent.tmp'
$LogcatErr = Join-Path $OutDir '_logcat.err'
$GeteventErr = Join-Path $OutDir '_getevent.err'

function Write-Section([string]$Title) {
    Add-Content -Encoding UTF8 $MainLog "`r`n`r`n==================== $Title ====================`r`n"
}

function Add-AdbShell([string]$Command) {
    Add-Content -Encoding UTF8 $MainLog ("`r`n$ adb shell " + $Command + "`r`n")
    adb shell sh -c $Command 2>&1 | Add-Content -Encoding UTF8 $MainLog
}

Write-Host 'Checking ADB connection...'
adb get-state | Out-Null

Remove-Item $MainLog,$PropsLog,$LogcatTmp,$GeteventTmp,$LogcatErr,$GeteventErr -Force -ErrorAction SilentlyContinue

@(
    'Xingrui E02 / Car test capture',
    'collector_version=2026-10-18-ipk-v4',
    ('Host time: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')),
    'During capture, test: Car launch -> wireless CarPlay -> next/previous -> volume +/- -> OK -> voice short/long press -> start navigation -> three-finger panel -> Ultra on/off -> observe cluster.',
    ''
) | Set-Content -Encoding UTF8 $MainLog

# Keep complete system properties separate as requested.
adb shell getprop | Set-Content -Encoding UTF8 $PropsLog

Write-Section 'DEVICE BASICS'
Add-AdbShell 'uname -a; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.model; getprop ro.ecarx.version.platform; getprop ro.build.fingerprint'

Write-Section 'IPK NETWORK - START'
Add-AdbShell 'echo "--- IPK properties ---"; getprop | grep -i ipk || true; echo "--- usb0 link ---"; ip link show usb0 2>/dev/null || true; echo "--- usb0 address ---"; ip addr show dev usb0 2>/dev/null || true; echo "--- usb0 neighbors ---"; ip neigh show dev usb0 2>/dev/null || true; echo "--- routes ---"; ip route show table all 2>/dev/null | grep -E "usb0|172\.30\.100" || true; echo "--- sockets ---"; (ss -tunap 2>/dev/null || netstat -tunap 2>/dev/null || true) | grep -E "172\.30\.100|usb0" || true'

Write-Section 'DISPLAY SNAPSHOT - START'
Add-AdbShell 'dumpsys display'

Write-Section 'INPUT SNAPSHOT - START'
Add-AdbShell 'dumpsys input'

Write-Section 'CAR / VHAL SNAPSHOT - START'
Add-AdbShell 'dumpsys car_service 2>/dev/null | grep -Ei "IPK|0x2e700137|0x22700005|INSTANT_SPEED|RPM|GEAR|hard_key|DashboardMapDisplay" || true'

# Start from a clean Android log buffer.
adb logcat -c

Write-Host ''
Write-Host 'Capture started.' -ForegroundColor Green
Write-Host 'Now perform the complete in-car test.'
Write-Host 'When finished, return to this window and press ENTER.' -ForegroundColor Yellow
Write-Host ''

$logcat = Start-Process -FilePath 'adb' -ArgumentList @('logcat','-v','threadtime') -RedirectStandardOutput $LogcatTmp -RedirectStandardError $LogcatErr -PassThru -NoNewWindow
$getevent = Start-Process -FilePath 'adb' -ArgumentList @('shell','getevent','-lt') -RedirectStandardOutput $GeteventTmp -RedirectStandardError $GeteventErr -PassThru -NoNewWindow

[void](Read-Host 'Press ENTER after the test sequence is complete')

foreach ($p in @($logcat,$getevent)) {
    if ($p -and -not $p.HasExited) {
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        $p.WaitForExit()
    }
}
Start-Sleep -Milliseconds 500

Write-Section 'FULL LOGCAT'
if (Test-Path $LogcatTmp) { Get-Content $LogcatTmp | Add-Content -Encoding UTF8 $MainLog }
if ((Test-Path $LogcatErr) -and ((Get-Item $LogcatErr).Length -gt 0)) {
    Add-Content -Encoding UTF8 $MainLog "`r`n--- LOGCAT STDERR ---`r`n"
    Get-Content $LogcatErr | Add-Content -Encoding UTF8 $MainLog
}

Write-Section 'IPK / CLUSTER FILTERED LOGCAT'
if (Test-Path $LogcatTmp) {
    Get-Content $LogcatTmp |
        Select-String -Pattern 'IPK|DashboardMapDisplay|Cluster|Instrument|ECarXNavi|naviservice|compositor|0x2e700137|0x22700005|hard_key|INSTANT_SPEED|RPM|GEAR|Xingrui' |
        ForEach-Object { $_.Line } | Add-Content -Encoding UTF8 $MainLog
}

Write-Section 'LOW-LEVEL INPUT / GETEVENT'
if (Test-Path $GeteventTmp) { Get-Content $GeteventTmp | Add-Content -Encoding UTF8 $MainLog }
if ((Test-Path $GeteventErr) -and ((Get-Item $GeteventErr).Length -gt 0)) {
    Add-Content -Encoding UTF8 $MainLog "`r`n--- GETEVENT STDERR ---`r`n"
    Get-Content $GeteventErr | Add-Content -Encoding UTF8 $MainLog
}

Write-Section 'DISPLAY SNAPSHOT - END'
Add-AdbShell 'dumpsys display'

Write-Section 'INPUT SNAPSHOT - END'
Add-AdbShell 'dumpsys input'

Write-Section 'CAR / VHAL SNAPSHOT - END'
Add-AdbShell 'dumpsys car_service 2>/dev/null | grep -Ei "IPK|0x2e700137|0x22700005|INSTANT_SPEED|RPM|GEAR|hard_key|DashboardMapDisplay" || true'

Write-Section 'IPK NETWORK - END'
Add-AdbShell 'echo "--- IPK properties ---"; getprop | grep -i ipk || true; echo "--- usb0 link ---"; ip link show usb0 2>/dev/null || true; echo "--- usb0 address ---"; ip addr show dev usb0 2>/dev/null || true; echo "--- usb0 neighbors ---"; ip neigh show dev usb0 2>/dev/null || true; echo "--- routes ---"; ip route show table all 2>/dev/null | grep -E "usb0|172\.30\.100" || true; echo "--- sockets ---"; (ss -tunap 2>/dev/null || netstat -tunap 2>/dev/null || true) | grep -E "172\.30\.100|usb0" || true'

Write-Section 'ECARX SERVICES'
Add-AdbShell 'service list | grep -Ei "ecarx|car_service|vehicle|input|navi|display|cluster|ipk" || true'

Write-Section 'SELECTED PROCESS / PACKAGE SNAPSHOT'
Add-AdbShell 'ps -A | grep -Ei "car|diplay|xcertplay|geely|ecarx|navi|cluster|instrument|ipk|compositor|input" || true'

Write-Section 'GEELY LEGACY INPUT SERVICE CHECK'
Add-AdbShell 'dumpsys package com.geely.inputservice 2>/dev/null || true; dumpsys activity services com.geely.inputservice 2>/dev/null || true'

Write-Section 'END'
Add-Content -Encoding UTF8 $MainLog ('Capture completed: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))

Remove-Item $LogcatTmp,$GeteventTmp,$LogcatErr,$GeteventErr -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host 'Done. Send me only these two files:' -ForegroundColor Green
Write-Host "  $MainLog"
Write-Host "  $PropsLog"
