<#
.SYNOPSIS
  Captures what the cover screen looks like to Android just before and just after unlocking.

.DESCRIPTION
  Read-only. Records logcat for the whole session and runs probe.ps1 twice:
    probe-output\locked\    folded, cover screen on, video playing, still locked
    probe-output\unlocked\  folded, right after unlocking (Now Brief / grey widgets)
  plus probe-output\unlock-logcat.txt covering the transition.
#>
$ErrorActionPreference = 'Stop'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
$env:PATH = "$(Split-Path $adb);$env:PATH"

$repoRoot = Split-Path -Parent $PSScriptRoot
$outRoot = Join-Path $repoRoot 'probe-output'
New-Item -ItemType Directory -Force $outRoot | Out-Null
$logFile = Join-Path $outRoot 'unlock-logcat.txt'

function Say([string]$msg, [string]$color = 'Cyan') { Write-Host ''; Write-Host $msg -ForegroundColor $color }

Say 'STEP 1: Fold the phone. Press the side key once so the cover screen shows your video.' 'Yellow'
Say '        Do NOT unlock. Tap the cover screen if it goes dark.' 'Yellow'
Read-Host 'Press Enter here when the video is playing on the cover screen'

& $adb logcat -c
$logcat = Start-Process -FilePath $adb -ArgumentList 'logcat', '-v', 'threadtime' `
    -RedirectStandardOutput $logFile -NoNewWindow -PassThru

& (Join-Path $PSScriptRoot 'probe.ps1') -Phase locked

[console]::Beep(880, 300)
Say 'STEP 2: Now UNLOCK with your fingerprint, keeping the phone folded.' 'Green'
Say '        Wait until Now Brief / the grey widgets appear (tap the cover if it dims).' 'Green'
Read-Host 'Press Enter here once the unlocked cover screen is showing'

& (Join-Path $PSScriptRoot 'probe.ps1') -Phase unlocked

Stop-Process -Id $logcat.Id -ErrorAction SilentlyContinue
[console]::Beep(660, 200)
Say "Done. You can unfold the phone. Results are in $outRoot" 'Cyan'
