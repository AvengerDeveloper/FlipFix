<#
.SYNOPSIS
  Read-only ADB probe of a Galaxy Z Flip's cover screen, wallpaper and dressroom state.

.DESCRIPTION
  Runs only read commands (getprop, settings list, dumpsys, ls, find). Nothing on the
  phone is changed. Output goes to probe-output\<Phase>\ next to this repo.

  Run it twice, so the two snapshots can be diffed:
    .\tools\probe.ps1 -Phase locked     # folded, cover screen ON, video playing, NOT unlocked
    .\tools\probe.ps1 -Phase unlocked   # folded, cover screen ON, after unlocking (grey widgets)

.PARAMETER Phase
  Label for this snapshot, e.g. "locked" or "unlocked".
#>
param(
    [Parameter(Mandatory = $true)][string]$Phase
)

$ErrorActionPreference = 'Continue'

# Locate adb: PATH first, then the default Android Studio SDK location.
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $candidate = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $candidate) { $adb = $candidate }
}
if (-not $adb) { throw 'adb not found. Install Android Studio or SDK Platform-Tools first.' }

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
if (-not $devices) { throw 'No authorised device. Check the cable and accept the USB debugging prompt on the phone.' }

$repoRoot = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $repoRoot "probe-output\$Phase"
New-Item -ItemType Directory -Force $outDir | Out-Null

function Probe([string]$name, [string]$shellCmd) {
    Write-Host "  $name"
    $out = & $adb shell $shellCmd 2>&1 | ForEach-Object { "$_" }
    $out | Out-File -Encoding utf8 (Join-Path $outDir "$name.txt")
}

Write-Host "Probing ($Phase) -> $outDir"

# Device identity
Probe 'device'           'getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.version.oneui; getprop ro.build.display.id'

# Public settings tables (readable by a normal app without any bridge)
Probe 'settings-system'  'settings list system'
Probe 'settings-secure'  'settings list secure'
Probe 'settings-global'  'settings list global'

# Displays, windows, layers: how the cover screen is composed
Probe 'display'          'dumpsys display'
Probe 'window-displays'  'dumpsys window displays'
Probe 'window-windows'   'dumpsys window windows'
Probe 'activities'       'dumpsys activity activities'
Probe 'surfaceflinger'   'dumpsys SurfaceFlinger --list'
Probe 'wallpaper'        'dumpsys wallpaper'

# Samsung packages related to the cover screen
# Single quotes inside the device command: Windows PowerShell 5.1 mangles embedded double quotes
# when passing arguments to native exes.
Probe 'packages-samsung' "pm list packages -f --user 0 | grep -i -E 'cover|subscreen|dressroom|wallpaper|brief|widget|launcher|systemui|aod|smartsuggestions'"
Probe 'pkg-dressroom'    'dumpsys package com.samsung.android.app.dressroom'

# Storage: what UID 2000 can and cannot see
Probe 'ls-dressroom-ext' 'ls -la /sdcard/Android/data/com.samsung.android.app.dressroom/ 2>&1'
Probe 'ls-systemui-ext'  'ls -la /sdcard/Android/data/com.android.systemui/ 2>&1'
Probe 'ls-dressroom-de'  'ls -la /data/user_de/0/com.samsung.android.app.dressroom/ 2>&1'
Probe 'find-sdcard'      "find /sdcard/ \( -iname '*cover*' -o -iname '*subscreen*' -o -iname '*wallpaper*' \) 2>/dev/null | head -n 200"
# Samsung's cover wallpaper store (One UI 9): world-readable to the shell user on the Flip 6.
Probe 'ls-coverwallpaper' 'ls -laRZ /data/overlays/coverwallpaper/ 2>&1'

Write-Host "Done. Files written to $outDir"
