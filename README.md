# FlipFix

**Keep your Galaxy Z Flip cover-screen video playing after you unlock — the way it worked before One UI 9.**

<!-- Replace the line below with a short screen recording (demo.gif) or side-by-side screenshot -->
<!-- ![FlipFix Demo](demo.gif) -->

For four years, the Galaxy Z Flip cover screen worked the same way: the wallpaper or video you set *was* your cover screen. Swipe for widgets, swipe down for notifications, swipe up for Samsung Wallet. One UI 9 changed that by turning your cover video into a lock-screen-only view — the moment you unlock, Samsung pauses it and covers it with Now Brief or a flat grey widget grid.

**FlipFix** restores the original flow with **no root** and **no Shizuku**. It uses Samsung's own wallpaper engine directly, so what plays after you unlock is the exact video you configured in **Wallpaper & style**.

---

## Compatibility

| Device | Model | Status |
| :--- | :--- | :--- |
| **Galaxy Z Flip6** | `SM-F741B` (One UI 9.0 / Android 17) | Tested daily — clock, icons, battery pill & music pill match within a few pixels |
| **Galaxy Z Flip8** | `SM-F776B` (One UI 9.0 / Android 17) | Tested daily — clock, icons, battery pill & music pill match within a few pixels |
| **Galaxy Z Flip5 / Z Flip7** | One UI 9.0+ | Not tested yet; should work the same way — reports welcome |

> **Using a Z Flip5 or Z Flip7?** If your cover video stays active after unlock but the clock doesn't render, run `tools/probe.ps1` (see [Troubleshooting](#troubleshooting--diagnostic-tools)) and open a GitHub Issue with the output so support for your clock layout can be added. **Check the output before posting**: it includes device settings and app lists that can contain things like Wi-Fi or Bluetooth device names.

---

## What You Get

Once unlocked while folded, FlipFix keeps your cover screen intact:

| Action / Element | Behavior with FlipFix |
| :--- | :--- |
| **Cover video** | Keeps playing seamlessly — FlipFix takes over within a frame of unlock |
| **Clock & date** | Stays in place using the same font, size, color, and position as Samsung's lock screen |
| **Notifications & battery** | Samsung-style notification icons top-left; battery pill top-right with charging bolt or protection shield |
| **Music pill** | Samsung's Now bar player with album artwork, scrolling title, and working previous / play-pause / next controls |
| **Swipe left (or right)** | Opens Samsung's widgets as before — your cover face shrinks away as a card following your finger |
| **Swipe right from left edge** | Returns from widgets back to your video, with the card sliding back in |
| **Swipe up** | Opens Samsung Wallet |
| **Swipe down from top edge** | Opens Samsung's native notification panel (FlipFix does not intercept it) |
| **Unfold the phone** | Automatically switches FlipFix's accessibility service off |

---

## How It Works

Samsung never actually removes your cover video upon unlock. The video wallpaper stays loaded on the cover display, while Samsung's cover launcher sends it a `samsung.android.wallpaper.pause` command and draws its widget UI on top.

FlipFix reverses that handoff in three steps:
1. Opens a transparent window on the cover display the instant you unlock, making it the active window displaying the cover wallpaper.
2. Sends the matching `samsung.android.wallpaper.resume` command so Samsung's player keeps running smoothly.
3. Draws your clock, status icons, and music pill on top, matched to Samsung's within a few pixels.

### Runtime Clock Matching (Zero Bundled Samsung Assets)
When you customize your cover face in **Wallpaper & style**, Samsung saves a rendered template (`cover_clock.png`) alongside your cover video. FlipFix inspects that template to detect your active clock layout, coordinates, size, and color, then draws it at runtime using assets loaded directly from Samsung's Always On Display (AOD) package (FlipFix ships none of Samsung's proprietary fonts or graphics):

| Clock Style | Typical Device | How FlipFix Renders It |
| :--- | :--- | :--- |
| **Single-line** (`13:49  Sun 15 November`) | Z Flip6 | Samsung's `ClockModex` system font |
| **Stacked** (date over large rounded digits) | Z Flip8 | Samsung's own digit vector paths (`big_clock_num_*`), stroked as rounded lines |
| **Any other style** | — | Hides the clock as a safe fallback rather than rendering a mismatched layout |

> **Why is the weather space empty on the stacked clock?** Samsung pulls cover-screen weather from a private system provider that standard apps cannot read. Rather than requesting internet and location permissions to fetch third-party weather, FlipFix keeps the date in its exact original position and leaves the weather slot clean.

Full technical details and reverse-engineering notes are documented in [`research/`](research/).

---

## Privacy, Accessibility & Banking Apps

FlipFix is built to stay out of the way and keep everything on-device:

* **Minimal Accessibility footprint:** Android only lets an app open a screen from the background in a few cases, and an Accessibility Service is one of them. FlipFix listens *only* for top-window state changes and **never** reads the text or structure of other apps' screens (window-content access is switched off in its configuration).
* **In-memory swipe snapshots only:** During the swipe-to-widgets card animation, FlipFix captures temporary snapshots of its own cover face and Samsung's widget screen. They stay strictly in RAM, are never written to storage, and are overwritten on the next swipe.
* **Zero internet permission:** FlipFix declares no `INTERNET` permission — nothing ever leaves your phone.
* **Automatic banking app protection:** Many banking apps refuse to open while any accessibility service is active. Because FlipFix enables its service **only while the cover screen is on** and disables it the moment you unfold the phone (using `WRITE_SECURE_SETTINGS`), banking apps on the inner display never see it running.
* **Cover-screen banking exclusions:** One UI 9 already blocks banking apps from launching on the cover screen (verified with two banks). If an app does open on the cover screen and complains, select it under **Choose banking apps…** inside FlipFix — FlipFix will automatically disable itself whenever that app is in the foreground. When an app with a finance-sounding name opens on the cover screen, FlipFix also asks once, by notification, whether to pause for it.
* **Manual toggle:** A **Quick Settings tile** lets you switch FlipFix on or off anytime.

---

## Installation

### Prerequisites
* A Windows, macOS, or Linux computer with [Android SDK Platform-Tools (`adb`)](https://developer.android.com/tools/releases/platform-tools) for the one-time permission grant — **or**, with no computer, an on-phone ADB app connected through **Wireless debugging**.
* *(Optional, only if building from source)* Android Studio or JDK 21.

### Step 1: Enable USB Debugging on Your Z Flip
1. Open **Settings → Security and privacy → Auto Blocker** and turn it **Off** *(if left on, Samsung greys out USB debugging)*.
2. Go to **Settings → About phone → Software information** and tap **Build number** 7 times until Developer options are enabled.
3. Open **Settings → Developer options**, turn **USB debugging** **On** (or **Wireless debugging** for an on-phone ADB app), connect your Z Flip to your PC via USB, and tap **Allow** on the authorization prompt.

### Step 2: Install FlipFix

**Option A — Install prebuilt APK (Recommended):**
Download [`FlipFix.apk`](../../releases/latest/download/FlipFix.apk) from the latest [release](../../releases) and install it via ADB (installing through ADB automatically avoids Android's *"Restricted settings"* block):
```bash
adb install FlipFix.apk
```

**Option B — Build from source:**
```bash
./gradlew :app:installDebug
```
> Builds you make yourself are signed with a different key from the releases. To switch between a self-built copy and a release APK, uninstall FlipFix first, then repeat Step 3.

### Step 3: Grant the Two One-Time Permissions
Run both commands in your terminal while your phone is connected:
```bash
adb shell pm grant io.github.avengerdeveloper.flipfix android.permission.WRITE_SECURE_SETTINGS
```
```bash
adb shell cmd notification allow_listener io.github.avengerdeveloper.flipfix/io.github.avengerdeveloper.flipfix.FlipNotificationListener
```
*(Notification access can also be toggled manually under **Settings → Notifications → Device & app notifications**, or from the FlipFix app.)*

### Step 4: Open FlipFix Once
Open the **FlipFix** app on the main screen. Everything should show as ready; tap anything marked in orange. Allow **notifications** when asked (used for the banking-app question and the "paused" notice) and add the **Quick Settings tile** from there.

### Step 5: Fold and Unlock
1. Fold your Z Flip, wake the cover screen, and unlock — your video will continue playing uninterrupted.
2. You do **not** need to enable FlipFix manually in Accessibility settings; the app manages its service state automatically.
3. *(Recommended)* Open **Settings → Apps → FlipFix → Battery** and set it to **Unrestricted** so One UI never puts the listener to sleep, then turn **Auto Blocker** back **On** under **Settings → Security and privacy**.

Open the **FlipFix** app on your main screen anytime to check service status, configure banking exclusions, or toggle it off.

---

## Troubleshooting & Diagnostic Tools

The [`tools/`](tools/) folder includes Windows PowerShell scripts to inspect how One UI handles the cover display on your firmware. They only read from your phone, apart from clearing its log buffer before recording and briefly storing a screenshot there (deleted straight after):

| Script | Purpose |
| :--- | :--- |
| `tools/watch.ps1` | Streams live FlipFix logs alongside Samsung's `pause` / `resume` wallpaper commands as you fold, unlock, and swipe |
| `tools/cover-shot.ps1` | Captures cover-screen screenshots (Samsung locked vs. FlipFix unlocked) for side-by-side comparison |
| `tools/probe.ps1` | Dumps read-only display, window, wallpaper, and package information from your device (attach when reporting Flip 5/7 issues — check it first) |
| `tools/capture-unlock.ps1` | Records the phone's log while you unlock and runs `probe.ps1` before and after, to help diagnose firmware-specific timing issues |

---

## Disclaimer & License

FlipFix is an independent open-source project and is not affiliated with, endorsed by, or associated with Samsung Electronics. Galaxy, One UI and Samsung Wallet are trademarks of Samsung Electronics. It relies on undocumented One UI behaviour that a future update may change.

Licensed under the [MIT License](LICENSE).
