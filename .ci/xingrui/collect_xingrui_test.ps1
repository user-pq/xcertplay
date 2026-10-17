$ErrorActionPreference = 'Stop'

$OutDir = Join-Path (Get-Location) 'xingrui-logs'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$MainLog = Join-Path $OutDir 'xingrui_carplay_test.txt'
$PropsLog = Join-Path $OutDir 'xingrui_props.txt'
$LogcatTmp = Join-Path $OutDir '_logcat.tmp'
$GeteventTmp = Join-Path $OutDir '_getevent.tmp'

function Write-Section([string]$Title) {
    Add-Content -Encoding UTF8 $MainLog "`r`n`r`n==================== $Title ====================`r`n"
}

Write-Host 'Checking ADB connection...'
adb get-state | Out-Null

Remove-Item $MainLog,$PropsLog,$LogcatTmp,$GeteventTmp -Force -ErrorAction SilentlyContinue

@(
    'Xingrui E02 / DiPlay test capture',
    ('Host time: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')),
    'During capture, test: DiPlay launch -> wireless CarPlay -> next/previous -> volume +/- -> voice short/long press -> start navigation -> observe cluster.',
    ''
) | Set-Content -Encoding UTF8 $MainLog

# Keep system properties separate as requested.
adb shell getprop | Set-Content -Encoding UTF8 $PropsLog

# Start from a clean Android log buffer.
adb logcat -c

Write-Host ''
Write-Host 'Capture started.' -ForegroundColor Green
Write-Host 'Now perform the complete in-car test.'
Write-Host 'When finished, return to this window and press ENTER.' -ForegroundColor Yellow
Write-Host ''

$logcat = Start-Process -FilePath 'adb' -ArgumentList @('logcat','-v','threadtime') -RedirectStandardOutput $LogcatTmp -RedirectStandardError (Join-Path $OutDir '_logcat.err') -PassThru -NoNewWindow
$getevent = Start-Process -FilePath 'adb' -ArgumentList @('shell','getevent','-lt') -RedirectStandardOutput $GeteventTmp -RedirectStandardError (Join-Path $OutDir '_getevent.err') -PassThru -NoNewWindow

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

Write-Section 'LOW-LEVEL INPUT / GETEVENT'
if (Test-Path $GeteventTmp) { Get-Content $GeteventTmp | Add-Content -Encoding UTF8 $MainLog }

Write-Section 'DUMPSYS DISPLAY'
adb shell dumpsys display | Add-Content -Encoding UTF8 $MainLog

Write-Section 'DUMPSYS INPUT'
adb shell dumpsys input | Add-Content -Encoding UTF8 $MainLog

Write-Section 'GEELY INPUT SERVICE PACKAGE'
adb shell dumpsys package com.geely.inputservice | Add-Content -Encoding UTF8 $MainLog

Write-Section 'GEELY INPUT SERVICE RESOLUTION'
adb shell cmd package resolve-service --brief -a com.geely.inputservice 2>&1 | Add-Content -Encoding UTF8 $MainLog

Write-Section 'SELECTED PROCESS / PACKAGE SNAPSHOT'
adb shell ps -A | Select-String -Pattern 'diplay|xcertplay|geely|ecarx|navi|cluster|instrument' | ForEach-Object { $_.Line } | Add-Content -Encoding UTF8 $MainLog

Write-Section 'END'
Add-Content -Encoding UTF8 $MainLog ('Capture completed: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))

Remove-Item $LogcatTmp,$GeteventTmp,(Join-Path $OutDir '_logcat.err'),(Join-Path $OutDir '_getevent.err') -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host 'Done. Send me only these two files:' -ForegroundColor Green
Write-Host "  $MainLog"
Write-Host "  $PropsLog"
