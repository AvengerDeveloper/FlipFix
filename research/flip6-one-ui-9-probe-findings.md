# Probe findings: Galaxy Z Flip6, One UI 9.0

Device: SM-F741B, Android 17 (SDK 37), `ro.build.version.oneui=90000`, build `CP2A.260605.016.F741BXXS4EZJ1`.
Captured 2026-11-15 with `tools/capture-unlock.ps1` (locked snapshot, then unlocked snapshot, logcat across the unlock).
Raw output is in `probe-output/` (not for publishing: it contains personal app lists and notifications).

## How the cover screen is built

Display 1 (cover), top to bottom:

| Window | Owner | Locked | Unlocked |
|---|---|---|---|
| `SubScreenLockWindow` (type NOTIFICATION_SHADE, translucent) | SystemUI | **visible** | hidden |
| `SubHomeActivity` (BASE_APPLICATION, `fmt=TRANSPARENT`, `FLAG_SHOW_WALLPAPER` set in `fl=0x81910100`) | SystemUI `com.android.systemui.subscreen` | hidden | **visible** |
| `ImageWallpaper` (type WALLPAPER), layer `Wallpaper BBQ wrapper 17_sub\|system` | SystemUI | **visible** | **visible** |

So the cover video is **never torn down on unlock**. The wallpaper window stays on screen behind
`SubHomeActivity`, which itself asks to show the wallpaper. Samsung just pauses the video and draws the
Now Brief / grey widget UI on top.

## The exact unlock sequence (logcat)

```
12:00:08.348 KeyguardViewMediator: keyguardGoingAway requested
12:00:08.455 SubScreenManager: onKeyguardShowingChanged() false
12:00:08.455 ImageWallpaper_17[Video]: updatePlayerState ... isKeyguard=false, forcePause=false  -> play
12:00:08.456 SubLauncher:BouncerViewController: onKeyguardShowingChanged keepLockScreenAfterUnlock=false
12:00:08.457 SubLauncher:LockEngine: lockWindow show=false
12:00:08.457 SubLauncher:SubLauncherClockController: pauseWallpaper:
12:00:08.458 ImageWallpaper_17[Video]: onCommand: samsung.android.wallpaper.pause
12:00:08.458 ImageWallpaper_17[Video]: updatePlayerState ... forcePause=true  -> pause (SemMediaPlayer.pause)
12:00:08.470 ACTION_USER_PRESENT broadcast
```

The video engine is `ImageWallpaper_17[Video]` (which = 17 = cover/sub display + system), playing through
`SemMediaPlayer`. The pause is a **wallpaper command** (`WallpaperManager.sendWallpaperCommand`) sent by the
cover launcher (`SubLauncherClockController.pauseWallpaper`).

SystemUI's code also contains the opposite command, **`samsung.android.wallpaper.resume`**, plus
`coveropened`, `coverclosed`, `goingtosleep`, `bouncervisibilitychanged` and others.

## The video file

`dumpsys wallpaper` for which=17: `mWpType=8` (video),
`mVideoFilePath=/data/overlays/coverwallpaper/0/<id>/video_wallpaper_0_17.mp4`.

That folder is world-readable (`rwxrwxr-x`, SELinux `themecenter_data_file`), and the ADB shell user read
the MP4 bytes. Same folder: `cover_clock.png`, `thumbnail.jpg`, `crop_wallpaper.gif`.
Not yet known: whether a normal app (`untrusted_app`) can read it, or only shell/Shizuku.

The dressroom private folder (`/data/user_de/0/com.samsung.android.app.dressroom/`) is `Permission denied`, as expected.

## Settings of interest

- `secure fingerprint_stay_on_lock_screen=0`, `secure face_stay_on_lock_screen=1`: the "Stay on Lock screen"
  option exists for fingerprint too. It feeds `keepLockScreenAfterUnlock` in the cover launcher.
- `system cover_screen_timeout=15`
- `system lockscreen_wallpaper_sub=-1`, `system plugin_lock_sub_enabled=10011`
- `secure all_splugin_load_failed=com.samsung.android.multistar|` (MultiStar's plugin failed to load)
- No setting changes between locked and unlocked except brightness and the unlock timestamp.

## What this means for FlipFix

**Primary approach: "wallpaper window".** A transparent FlipFix Activity with `windowShowWallpaper=true`,
launched on display 1 right after unlock, becomes the cover's wallpaper target. It then sends
`samsung.android.wallpaper.resume` through `WallpaperManager.sendWallpaperCommand(windowToken, ...)`.
The result would be Samsung's own engine playing the exact current video live, with no file access and no
decoding by FlipFix. The clock, battery and notification views sit on top.

Unverified, and next to test:
1. Can a third-party Activity start on display 1 (`am start --display 1`), or does One UI block it?
2. Does the wallpaper show through it?
3. Does the engine accept `resume` from our window token (WMS only takes commands from the wallpaper target)?

**Fallback:** accessibility overlay plus ExoPlayer, fed either from the MP4 path above (if an app or Shizuku can
read it) or from a video the user picks.

**Zero-install option to test:** turn on fingerprint "Stay on Lock screen" and check whether the video keeps
playing after a fingerprint unlock.

## Feasibility test results (`covertest` module, 2026-11-15): all passed

Phone folded and unlocked (Now Brief showing), then
`adb shell am start --display 1 -n dev.flipfix.covertest/.CoverTestActivity`:

```
FlipFixTest: onCreate display=1 name=Built-in Screen state=2
FlipFixTest: READ OK /data/overlays/coverwallpaper/0/<id>/video_wallpaper_0_17.mp4 (27594988 bytes, box=ftyp)
FlipFixTest: sent samsung.android.wallpaper.resume
ImageWallpaper_17[Video]: onCommand: samsung.android.wallpaper.resume
ImageWallpaper_17[Video]: updatePlayerState ... isKeyguard=false, forcePause=false
ImageWallpaper_17[VideoController]: play
```
`dumpsys window`, display 1: `mWallpaperTarget=Window{... dev.flipfix.covertest/...CoverTestActivity}`.

1. **A third-party activity runs on the cover display:** yes, with no "open phone to continue" block (launched from adb; launching from our own service is still to be checked).
2. **Samsung's cover video shows through it:** yes. Our opaque activity with `windowShowWallpaper` hides SubHomeActivity and becomes the wallpaper target.
3. **The engine accepts `samsung.android.wallpaper.resume` from us:** yes. The video visibly plays after unlock.
4. **A normal app (no special permissions) can read the cover video files:** yes, the current one and the previous ones in the history.

So the primary approach works: FlipFix needs no video player of its own and gets the exact current video.
