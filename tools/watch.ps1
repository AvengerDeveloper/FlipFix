<#
.SYNOPSIS
  Live view of FlipFix's log plus Samsung's cover-video commands. Ctrl+C to stop.
  Also saved to probe-output\watch-logcat.txt.
#>
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$outRoot = Join-Path $repoRoot 'probe-output'
New-Item -ItemType Directory -Force $outRoot | Out-Null

& $adb logcat -c
# logcat -e matches the message text only (not the tag), so match FlipFix's own messages explicitly.
& $adb logcat -v time -e 'fold rule|accessibility service|launched cover face|edge strip|opened on cover|media pill|cover icons|not launching|launch failed|sent resume|resume failed|Wallet not installed|could not open Wallet|opened Wallet|samsung\.android\.wallpaper\.(pause|resume)|ImageWallpaper_17\[VideoController\]: (play|pause)$|accessibilityservice.*flipfix|AccessibilityManagerService.*flipfix' |
    Tee-Object -FilePath (Join-Path $outRoot 'watch-logcat.txt')
