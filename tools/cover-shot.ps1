<#
.SYNOPSIS
  Screenshots of the cover screen for side-by-side comparison: Samsung's locked cover face, then
  FlipFix's unlocked face. Saved to probe-output\shots\.

.DESCRIPTION
  Uses screencap on the cover panel's physical display id (the smaller built-in panel).
  The image is written to /data/local/tmp on the phone, pulled, then deleted there.
#>
param([string[]]$Steps = @('samsung-locked', 'flipfix-unlocked'))
$ErrorActionPreference = 'Stop'
# "powershell -File" passes "a,b" as one string; split it into separate steps.
$Steps = @($Steps | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $repoRoot 'probe-output\shots'
New-Item -ItemType Directory -Force $outDir | Out-Null

Write-Host 'Waiting for the phone (plug the USB cable into this PC)...'
& $adb wait-for-device
Write-Host 'Phone connected.'

# Physical id of the smaller built-in panel = the cover screen.
$panels = & $adb shell dumpsys display | Select-String -Pattern 'uniqueId="local:(\d+)", (\d+) x (\d+)' |
    ForEach-Object { [pscustomobject]@{ Id = $_.Matches[0].Groups[1].Value; Pixels = [int]$_.Matches[0].Groups[2].Value * [int]$_.Matches[0].Groups[3].Value } } |
    Sort-Object Id -Unique | Sort-Object Pixels
$coverId = $panels[0].Id
Write-Host "Cover panel id: $coverId"

$prompts = @{
    'samsung-locked'            = 'Fold the phone and press the side key: cover LOCK screen with your video. Do NOT unlock.'
    'flipfix-unlocked'          = 'Now unlock with your fingerprint (stay folded) so FlipFix shows.'
    'charging-samsung-locked'   = 'Plugged in and CHARGING. Fold, press the side key: cover LOCK screen. Do NOT unlock.'
    'charging-flipfix-unlocked' = 'Still charging: unlock with your fingerprint (stay folded) so FlipFix shows.'
}

# Battery state at the moment of each shot (status: 2 = charging, 4 = plugged but not charging, 5 = full).
function BatteryState {
    $b = & $adb shell dumpsys battery
    $get = { param($k) (($b | Select-String -Pattern "^\s+$k\s*:\s*(.+)$" | Select-Object -First 1).Matches.Groups[1].Value).Trim() }
    "level $(& $get 'level')%, status $(& $get 'status'), USB powered $(& $get 'USB powered'), AC powered $(& $get 'AC powered')"
}

foreach ($step in $Steps) {
    Write-Host ''
    $prompt = $prompts[$step]
    if (-not $prompt) { $prompt = "Get the cover screen ready for '$step'." }
    Write-Host $prompt -ForegroundColor Yellow
    Read-Host 'Press Enter to take the screenshot (tap the cover first if it dimmed)'
    $remote = "/data/local/tmp/flipfix-$step.png"
    & $adb shell screencap -p -d $coverId $remote
    & $adb pull $remote (Join-Path $outDir "$step.png") | Out-Null
    & $adb shell rm $remote
    $battery = BatteryState
    $battery | Out-File -Encoding utf8 (Join-Path $outDir "$step.battery.txt")
    Write-Host "Saved $step.png  ($battery)" -ForegroundColor Green
}
Write-Host "`nDone. Screenshots are in $outDir"
