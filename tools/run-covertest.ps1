<#
.SYNOPSIS
  Launches the FlipFix cover test activity on the cover display (after unlock) and records the result.

.DESCRIPTION
  Output: probe-output\covertest-logcat.txt (full logcat while the test runs) and
          probe-output\covertest-window.txt (who the cover display's wallpaper target is).
#>
$ErrorActionPreference = 'Stop'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }

$repoRoot = Split-Path -Parent $PSScriptRoot
$outRoot = Join-Path $repoRoot 'probe-output'
New-Item -ItemType Directory -Force $outRoot | Out-Null
$logFile = Join-Path $outRoot 'covertest-logcat.txt'

function Say([string]$msg, [string]$color = 'Cyan') { Write-Host ''; Write-Host $msg -ForegroundColor $color }

# Current cover video path, as reported by the wallpaper service (which=17).
$videoPath = (& $adb shell 'dumpsys wallpaper' | Select-String -Pattern 'mVideoFilePath=([^,]*_17\.mp4)' |
    Select-Object -First 1).Matches.Groups[1].Value
Write-Host "Cover video path: $videoPath"

Say 'STEP 1: Fold the phone, unlock it with your fingerprint (stay folded),' 'Yellow'
Say '        and wait for Now Brief / the grey widgets on the cover screen.' 'Yellow'
Read-Host 'Press Enter here when the unlocked cover screen is showing'

& $adb logcat -c
$logcat = Start-Process -FilePath $adb -ArgumentList 'logcat', '-v', 'threadtime' `
    -RedirectStandardOutput $logFile -NoNewWindow -PassThru

$amArgs = @('shell', 'am', 'start', '--display', '1', '-n', 'io.github.avengerdeveloper.flipfix.covertest/.CoverTestActivity')
if ($videoPath) { $amArgs += @('--es', 'videoPath', $videoPath) }
Say 'Launching the test on the cover screen...'
& $adb @amArgs

[console]::Beep(880, 300)
Say 'STEP 2: Look at the cover screen.' 'Green'
Say '        Do you see your video behind the test buttons? Is it MOVING or frozen?' 'Green'
Say '        Then tap Pause, wait 2 seconds, tap Resume. Tap the screen if it dims.' 'Green'
Read-Host 'Press Enter here when done (about 10 seconds is enough)'

& $adb shell 'dumpsys window' | Select-String -Pattern 'mWallpaperTarget|mPrevWallpaperTarget|Display: mDisplayId|mFocusedApp|mCurrentFocus' |
    ForEach-Object { $_.Line.Trim() } | Out-File -Encoding utf8 (Join-Path $outRoot 'covertest-window.txt')

Stop-Process -Id $logcat.Id -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 300

Say 'Test log (FlipFixTest + cover video engine):' 'Cyan'
Select-String -Path $logFile -Pattern 'FlipFixTest|ImageWallpaper_17\[Video\]: (onCommand|updatePlayerState)|ImageWallpaper_17\[VideoController\]: (play|pause)' |
    ForEach-Object { $_.Line }
Say "Done. Full logs are in $outRoot" 'Cyan'
