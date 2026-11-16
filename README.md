# FlipFix

**Keep your Galaxy Z Flip cover-screen video playing after you unlock — the way it worked before One UI 9.**

For four years the Flip cover screen worked the same way: the wallpaper or video you set *was* the cover
screen. Swipe for widgets, down for notifications, up for Samsung Wallet. One UI 9 changed that: the cover
video is now only a lock screen. The moment you unlock, Samsung pauses it and covers it with Now Brief or a
flat grey widget grid.

FlipFix brings the old flow back. No root, no Shizuku, and it uses **Samsung's own video engine**, so what
you see is exactly the video you set in Wallpaper & style.

> Status: working daily on a Z Flip6 and a Z Flip8 (One UI 9.0). The clock, notification icons, battery pill
> and music pill match Samsung's cover lock screen to within a few pixels.

## What you get

| On the cover screen, after unlocking while folded | |
|---|---|
| Your cover video keeps playing | FlipFix takes over within a frame of the unlock |
| Your clock stays exactly where it was | Same font, size and position as Samsung's lock screen |
| Notification icons and battery | Samsung-style icons top-left; battery pill top-right with the charging bolt or protection shield |
| Music pill | Samsung's Now bar player: artwork, scrolling title, working previous / play-pause / next |
| Swipe left (or right) | Samsung's widgets, as before; the cover face shrinks away as a card that follows your finger |
| Swipe right from the left edge on the widgets | Back to your video, with the card sliding back in |
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
(`cover_clock.png`) next to the cover video. FlipFix reads which clock style you use from it, plus the
position, size and colour, and draws that style with Samsung's own assets loaded at runtime from Samsung's
AOD app (FlipFix ships none of them):

| Clock style | Typical phone | How FlipFix draws it |
|---|---|---|
| One line: `13:49  Sun 15 November` | Z Flip6 | Samsung's ClockModex font |
| Stacked: date over big rounded digits | Z Flip8 | Samsung's own digit paths (`big_clock_num_*`), drawn as round lines |
| Anything else | | No clock (safety net), rather than a wrong one |

Samsung's weather on the stacked style comes from a provider ordinary apps can't read, so FlipFix keeps the
date where it is and leaves the weather's space empty. That's deliberate: fetching weather itself would mean
internet and location permissions, and FlipFix has neither.

The full investigation is in [`research/`](research/).

### Why an accessibility service, and why it turns itself off

Android only lets an app open a screen from the background in a few cases; an accessibility service is one of
them. FlipFix's service listens only for "which window is on top" changes and **never reads screen content**
(text or UI of other apps). For the swipe animations it takes snapshots of the cover screen (FlipFix's own face
and Samsung's widget screen); they stay in memory, are never saved, and are replaced on the next swipe.
FlipFix has **no internet permission**, so nothing leaves your phone.

Many banking apps refuse to run while any accessibility service is on. So FlipFix switches its service **on
only while the cover screen is on** and **off the moment you open the phone**. Banking apps on the main
screen never see it. This switching needs a one-time permission granted from a PC (below).

One UI 9 doesn't let banking apps run on the cover screen at all (checked with two banks), so this already
covers them. For any app that does open on the cover and complains, tick it under **Choose banking apps…**
in the FlipFix app: FlipFix then switches itself off while that app is open. FlipFix also asks once when an
app's name looks financial. A **Quick Settings tile** switches FlipFix on or off at any time.

## Requirements

- Galaxy Z Flip on **One UI 9** (tested: Z Flip6 `SM-F741B` and Z Flip8 `SM-F776B`, One UI 9.0 / Android 17).
  Flip 5 / 7 should behave the same; reports welcome.
- `adb` once, for one permission: [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
  on a computer, or an on-phone ADB app using **Wireless debugging** (no computer needed).

## Install

1. Download the latest **`FlipFix-vX.Y.Z.apk`** from [Releases](../../releases).
2. Turn on **USB debugging** (or **Wireless debugging**): **Settings → About phone → Software information**,
   tap **Build number** 7 times, then **Settings → Developer options**.
3. Install it and grant the one permission Android only allows over ADB:
   ```bash
   adb install FlipFix-vX.Y.Z.apk
   adb shell pm grant io.github.avengerdeveloper.flipfix android.permission.WRITE_SECURE_SETTINGS
   ```
4. Open **FlipFix** and tap anything marked in orange: **Notification access** opens the right settings page,
   and **Notifications** asks once. Add the **Quick Settings tile** from there too.
5. Fold the phone, unlock — your video stays.

There is no need to turn the accessibility service on by hand; FlipFix manages it.

> Installed the APK from the phone (browser or file manager) instead of `adb install`? Android may block
> notification access for it ("restricted setting"). Open **Settings → Apps → FlipFix → ⋮ → Allow restricted
> settings**, then grant it.

**Build from source** instead: `./gradlew :app:installDebug` (Android Studio or JDK 21). Every push to `main`
also builds a debug APK on GitHub Actions; tags like `v1.2.3` publish a signed release.

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

- More Samsung clock styles (the one-line and stacked styles are matched today)
- Pre-built APKs on GitHub Releases

## Disclaimer

FlipFix is an independent project, not affiliated with or endorsed by Samsung. Galaxy, One UI and Samsung
Wallet are trademarks of Samsung Electronics. It relies on undocumented One UI behaviour that a future
update may change.

## Licence

[MIT](LICENSE)
