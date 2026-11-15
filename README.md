# FlipFix

**Keep your Galaxy Z Flip cover-screen video playing after you unlock — the way it worked before One UI 9.**

For four years the Flip cover screen worked the same way: the wallpaper or video you set *was* the cover
screen. Swipe for widgets, down for notifications, up for Samsung Wallet. One UI 9 changed that: the cover
video is now only a lock screen. The moment you unlock, Samsung pauses it and covers it with Now Brief or a
flat grey widget grid.

FlipFix brings the old flow back. No root, no Shizuku, and it uses **Samsung's own video engine**, so what
you see is exactly the video you set in Wallpaper & style.

> Status: early but working daily on a Z Flip6 (One UI 9.0). The clock, notification icons, battery pill and
> music pill match Samsung's cover lock screen to within a pixel or two.

## What you get

| On the cover screen, after unlocking while folded | |
|---|---|
| Your cover video keeps playing | FlipFix takes over within a frame of the unlock |
| Your clock stays exactly where it was | Same font, size and position as Samsung's lock screen |
| Notification icons and battery | Samsung-style icons top-left; battery pill top-right with the charging bolt or protection shield |
| Music pill | Samsung's Now bar player: artwork, scrolling title, working previous / play-pause / next |
| Swipe left (or right) | Samsung's widgets, as before |
| Swipe right from the left edge on the widgets | Back to your video |
| Swipe up | Samsung Wallet |
| Swipe down from the top edge | Samsung's own notification panel (FlipFix doesn't intercept it) |
| Open the phone | FlipFix switches its accessibility service off |

## How it works

Samsung never actually removes the cover video on unlock. The video wallpaper keeps running on the cover
display; Samsung's cover launcher sends it a `samsung.android.wallpaper.pause` command and draws its widget
UI on top. FlipFix:

1. opens a transparent screen on the cover display right after you unlock, which makes it the window
   showing the cover wallpaper, and
2. sends the matching `samsung.android.wallpaper.resume` command, so Samsung's player keeps going.

The clock is drawn to match Samsung's: Wallpaper & style leaves a rendered template of your cover face
(`cover_clock.png`) next to the cover video, which gives the clock's position, size and colour, and the
digits use Samsung's own clock font, loaded at runtime from Samsung's AOD app (FlipFix does not ship any
Samsung fonts).

The full investigation is in [`research/`](research/).

### Why an accessibility service, and why it turns itself off

Android only lets an app open a screen from the background in a few cases; an accessibility service is one of
them. FlipFix's service listens only for "which window is on top" changes. It **never reads screen content**,
and FlipFix has **no internet permission**.

Many banking apps refuse to run while any accessibility service is on. So FlipFix switches its service **on
only while the cover screen is on** and **off the moment you open the phone**. Banking apps on the main
screen never see it. This switching needs a one-time permission granted from a PC (below).

One UI 9 doesn't let banking apps run on the cover screen at all (checked with two banks), so this already
covers them. For any app that does open on the cover and complains, tick it under **Choose banking apps…**
in the FlipFix app: FlipFix then switches itself off while that app is open. FlipFix also asks once when an
app's name looks financial. A **Quick Settings tile** switches FlipFix on or off at any time.

## Requirements

- Galaxy Z Flip on **One UI 9** (tested: Z Flip6 `SM-F741B`, One UI 9.0 / Android 17). Flip 5 / 7 / 8 should
  behave the same; reports welcome.
- A PC with [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
  (`adb`) for the one-time setup, and Android Studio or a JDK to build.

## Install

1. On the phone: **Settings → About phone → Software information**, tap **Build number** 7 times, then
   **Settings → Developer options → USB debugging** on. Connect the USB cable and allow the prompt.
2. Build and install:
   ```bash
   ./gradlew :app:installDebug
   ```
3. Grant the two permissions (one time):
   ```bash
   adb shell pm grant io.github.avengerdeveloper.flipfix android.permission.WRITE_SECURE_SETTINGS
   adb shell cmd notification allow_listener io.github.avengerdeveloper.flipfix/io.github.avengerdeveloper.flipfix.FlipNotificationListener
   ```
   (Notification access can also be given in **Settings → Notifications → Device & app notifications**.)
4. Fold the phone, unlock — your video stays.

There is no need to turn the accessibility service on by hand; FlipFix manages it. Open the FlipFix app on
the main screen to check status or switch it off.

> Installing with `adb` avoids Android's "restricted settings" block, which applies to apps sideloaded from
> a browser or file manager.

## Troubleshooting

`tools/watch.ps1` (Windows PowerShell) streams FlipFix's log plus Samsung's video commands while you fold,
unlock and swipe. `tools/cover-shot.ps1` takes cover-screen screenshots (Samsung locked, then FlipFix
unlocked) for side-by-side comparison. `tools/probe.ps1` and `tools/capture-unlock.ps1` take read-only snapshots of how the cover
screen is built on your firmware. None of the tools change anything on the phone.

## Project layout

| Path | What |
|---|---|
| `app/` | The FlipFix app |
| `covertest/` | The feasibility probe that proved the approach |
| `tools/` | Read-only ADB probes and the log watcher |
| `research/` | Community research and on-device findings for One UI 9 |

## Roadmap

- Other Samsung clock styles (stacked, other fonts); the horizontal style is matched today
- Pre-built APKs on GitHub Releases

## Disclaimer

FlipFix is an independent project, not affiliated with or endorsed by Samsung. Galaxy, One UI and Samsung
Wallet are trademarks of Samsung Electronics. It relies on undocumented One UI behaviour that a future
update may change.

## Licence

[MIT](LICENSE)
