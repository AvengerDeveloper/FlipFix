# FlipFix

**Keep your Galaxy Z Flip cover-screen video playing after you unlock — the way it worked before One UI 9.**

> **Experimental test software.** FlipFix is new, was built and tested by one developer on two phones, and relies on undocumented Samsung behaviour. Expect bugs, and use it at your own risk: read the [disclaimer](DISCLAIMER.md) before installing.

[ELI5 Version](#eli5-version) · [Nerd Stuff](#nerd-stuff) · [Disclaimer](DISCLAIMER.md)

<!-- Replace the line below with a short screen recording (demo.gif) or side-by-side screenshot -->
<!-- ![FlipFix Demo](demo.gif) -->

---

## ELI5 Version

### What is this?
Samsung's One UI 9 update changed the cover screen on the Galaxy Z Flip. Now, the second you unlock your phone, Samsung pauses your cover video and covers it with a grey widget screen.

**FlipFix** puts things back the way they used to be. When you unlock your folded phone, your video wallpaper, clock, notifications, and music player stay right on screen.

### What works after installing?
* **Your video keeps playing** after you unlock — using the exact video you already set in Samsung's Wallpaper settings.
* **Your notifications and music controls** stay in their exact spots, and so does **your clock**: an exact copy for the clock styles FlipFix recognises, and a plainer clock in the same spot for the others.
* **Swipe left or right** to see your Samsung widgets; **swipe back** to return to your video.
* **Made for cover screens without widgets.** If your cover style has widgets under the clock, FlipFix shows each one in its spot and shape as a frosted glass pane with your video moving behind it, the widget blurred and not tappable: showing them live would need permissions (health, alarms, usage) that FlipFix doesn't ask for. The real ones are a swipe away.
* **Swipe down from the top** for your normal notifications and quick settings.
* **Open your phone** and FlipFix turns itself off automatically.

### Is it safe?
* **100% offline:** FlipFix has no internet access, so nothing ever leaves your phone.
* **Private:** Nothing is sent, and no snapshot or notification is ever saved: FlipFix remembers only its settings, your banking-app choices and where each clock sits. FlipFix looks at your notifications only to show their icons and your music player, and the quick snapshots it takes of the cover screen (for the swipe animations and to recognise your cover style) stay in memory and are thrown away. The one thing kept is a heavily blurred copy of the text and icons of any widgets under your clock, too blurry to read, in memory only, to draw their placeholders.
* **Banking-friendly:** Because FlipFix turns itself off the moment you unfold your phone, banking apps work normally without complaining.

### How to install (about 5 minutes)
Because Android blocks normal apps from changing how the cover screen behaves, you need to plug your phone into a computer **once** to give FlipFix permission. After that, you never need the computer again.

Open the **[FlipFix setup page](https://avengerdeveloper.github.io/FlipFix/)** in **Chrome or Edge** on your computer and follow its five steps. Nothing to download and no commands to type: the page talks to your phone over the USB cable.

1. **Turn Auto Blocker off:** **Settings → Security and privacy → Auto Blocker** *(Samsung blocks USB setup while it's on)*.
2. **Turn on Developer options:** **Settings → About phone → Software information**, then tap **Build number** 7 times.
3. **Turn on USB debugging:** **Settings → Developer options → USB debugging**.
4. **Connect and install:** plug your phone in, click **Connect phone** on the setup page, pick your phone, and tap **Allow** on the phone. The page installs FlipFix and gives it its two permissions.
5. **Finish on your phone:** turn USB debugging off and Auto Blocker back on, open FlipFix once, and *(recommended)* set **Settings → Apps → FlipFix → Battery** to **Unrestricted**. Then fold, wake the cover screen and unlock: your video keeps playing.

Prefer the command line? See [Installation](#installation) below.

---

## Nerd Stuff

### Compatibility

| Device | Model | Status |
| :--- | :--- | :--- |
| **Galaxy Z Flip6** | `SM-F741B` (One UI 9.0 / Android 17) | Tested during development — clock, icons, battery pill & music pill match within a few pixels |
| **Galaxy Z Flip8** | `SM-F776B` (One UI 9.0 / Android 17) | Tested daily — clock, icons, battery pill & music pill match within a few pixels |
| **Galaxy Z Flip5 / Z Flip7** | One UI 9.0+ | Not tested yet; should work the same way — reports welcome |

> **Using a Z Flip5 or Z Flip7?** If your cover video stays active after unlock but the clock is missing or plainer than Samsung's, run `tools/probe.ps1` (see [Troubleshooting](#troubleshooting--diagnostic-tools)) and open a GitHub Issue with the output so support for your clock layout can be added. **Check the output before posting**: it includes device settings and app lists that can contain things like Wi-Fi or Bluetooth device names.

---

### What You Get

Once unlocked while folded, FlipFix keeps your cover screen intact:

| Action / Element | Behavior with FlipFix |
| :--- | :--- |
| **Cover video** | Keeps playing seamlessly — FlipFix takes over within a frame of unlock |
| **Clock & date** | Stays in place using the same font, size, color, and position as Samsung's lock screen, for the clock styles FlipFix recognises; other styles get a plainer clock in the same place |
| **Notifications & battery** | Samsung-style notification icons top-left; battery pill top-right with charging bolt or protection shield |
| **Music pill** | A copy of Samsung's Now bar player, with album artwork, scrolling title, and working previous / play-pause / next controls |
| **Widgets under the clock** | Frosted glass, non-working placeholders in the same spots and shapes, your video moving behind them. FlipFix is optimized for widgetless cover screens: live widgets would need extra permissions. Swipe left or right for the real ones |
| **Swipe left (or right)** | Opens Samsung's widgets as before — your cover face shrinks away as a card following your finger |
| **Swipe right from left edge** | Returns from widgets back to your video, with the card sliding back in |
| **Leave an app on the cover** (Back, Home, or closing it in Recents) | Returns to your video, whether the app opened over FlipFix or from Samsung's widgets or app drawer. Looking at Samsung's widgets, its app drawer or Recents without opening an app keeps you there. Use one cover-screen launcher app at a time: another one would also try to come back after each app |
| **Swipe up** (or the Wallet handle at the bottom) | Opens Samsung Wallet's quick-pay cards, as Samsung's own handle does. The handle is also kept on the locked cover, where Samsung's disappears while FlipFix is on. It shows only if Samsung Wallet is installed and its own cover handle is on (**Wallet → Quick access → Cover screen**). Wallet keeps that setting private, so FlipFix follows what it last saw on Samsung's widget screen |
| **Swipe down from top edge** | Opens Samsung's native notification panel (FlipFix does not intercept it) |
| **Leave it untouched** | The cover turns off after Samsung's cover screen timeout (**Settings → Cover screen → Clock style**), exactly as it does without FlipFix. **Screen timeout** in the FlipFix app opens that setting directly |
| **Unfold the phone** | Automatically switches FlipFix's accessibility service off |

---

### How It Works

Samsung never actually removes your cover video upon unlock. The video wallpaper stays loaded on the cover display, while Samsung's cover launcher sends it a `samsung.android.wallpaper.pause` command and draws its widget UI on top.

FlipFix reverses that handoff in three steps:
1. Opens a transparent window on the cover display the instant you unlock, making it the active window displaying the cover wallpaper.
2. Sends the matching `samsung.android.wallpaper.resume` command so Samsung's player keeps running smoothly.
3. Draws your clock, status icons, and music pill on top, matched to Samsung's within a few pixels.

#### Runtime Clock Matching (Zero Bundled Samsung Assets)
When you customize your cover face in **Wallpaper & style**, Samsung saves a rendered template (`cover_clock.png`) for each cover preset. FlipFix inspects those templates to detect each preset's clock layout, coordinates, size, and color, then draws it at runtime using assets loaded directly from Samsung's Always On Display (AOD) package (FlipFix ships none of Samsung's proprietary fonts or graphics).

**Switching presets** (long-press the locked cover): nothing an app can read says which preset is active, so after each switch FlipFix looks at Samsung's locked cover (up to three snapshots a wake, a second or two apart, while Samsung's clock animates in), draws each preset's clock offscreen for that minute, and uses the one that lands on Samsung's pixels (allowing a small offset, since Samsung's lock screen sometimes places a clock a few pixels from where the template has it, and remembering that offset). FlipFix needs one such look per switch: if you unlock before it gets one, it looks again the next time the cover wakes locked. If none matches, FlipFix draws the plain fallback clock (last row below). Presets carried over from another phone (e.g. a Flip6 backup on a Flip8) keep that phone's template, which Samsung lays out differently, so they get the fallback clock too; making the preset again on this phone brings back the exact one.

| Clock Style | Typical Device | How FlipFix Renders It |
| :--- | :--- | :--- |
| **Single-line** (`13:49  Sun 15 November`) | Z Flip6 | Samsung's `ClockModex` system font |
| **Stacked** (date over large digits) | Z Flip8 | Samsung's own digit vector paths (`big_clock_num_*`), stroked as rounded lines at the template's thickness, or the preset's clock font when it uses one |
| **Hours over minutes** (`12` over `45`, date above or below, left, centred or right) | Z Flip8 | Samsung's clock fonts, or its rounded or layered digit paths (`layered_clock_num_*`), whichever matches the template |
| **Sideways** (`Wed 1 February 12:45` reading upwards) | Z Flip8 | Samsung's clock font for the time; the date in SamsungOne UI |
| **Analog** (numerals or ticks, round-ended hands) | Z Flip8 | The template's own dial with live hands measured from it (length, width, centre pin, a ticking second hand with its tail); the date in SamsungOne UI. Dials with shaped or illustrated hands show no clock (there are no digits for the fallback to find) |
| **Any other style** | Any | **Plain fallback clock:** FlipFix finds the time and date on Samsung's locked cover and draws them in the same spot, size and color, in One UI's standard font. Only when it can't clearly find the time does it show no clock, rather than a wrong one |

FlipFix works out which font, weight, digit set and thickness a preset uses by drawing each candidate and comparing it with the preset's template, once per preset. On the Z Flip8 the date line is in SamsungOne UI, loaded from Samsung's own font package on the phone (with a close system font if that package is missing).

> **Why is the weather space empty on the stacked clock?** Samsung pulls cover-screen weather from a private system provider that standard apps cannot read. Rather than requesting internet and location permissions to fetch third-party weather, FlipFix keeps the date in its exact original position and leaves the weather slot clean.

Full technical details and reverse-engineering notes are documented in [`research/`](research/).

---

### Privacy, Permissions & Banking Apps

FlipFix is built to stay out of the way and keep everything strictly on-device.

#### Permissions FlipFix Uses

| Permission / Capability | Why It's Needed & Privacy Guardrails |
| :--- | :--- |
| **Accessibility Service**<br>(`BIND_ACCESSIBILITY_SERVICE`) | Android only allows an app to open a window on the cover screen from the background through a few privileged mechanisms. FlipFix listens *only* for top-window state changes and **never** reads screen text or UI structure (`canRetrieveWindowContent` is turned off in its XML configuration). The one input it sends: a tap that lands on FlipFix's thin edge strip over Samsung's widgets is passed on to the widgets underneath (`canPerformGestures`). Snapshots used for the swipe-to-widgets card animation, plus a few snapshots of Samsung's locked cover after you switch cover presets (to tell which preset is active; it can show notification previews, so it is compared and dropped within a fraction of a second), stay strictly in RAM, are never written to storage, and are released when the screen turns off. The one exception: if your cover style has widgets under the clock, a heavily blurred copy of each widget's text and icons (too blurry to read) is kept in RAM to draw its placeholder. It is never written to storage either, and after a restart it is rebuilt from one new snapshot of the locked cover. |
| **Write Secure Settings**<br>(`WRITE_SECURE_SETTINGS`) | Lets FlipFix turn its own Accessibility Service on only while the cover screen is active, and turn it off the instant you unfold the phone or open an excluded app. |
| **Notification Access**<br>(`BIND_NOTIFICATION_LISTENER_SERVICE`) | Reads active notifications locally on-device solely to render the top-left notification icons and the Now bar music player pill (album artwork, track title, and playback controls). |
| **Notifications**<br>(`POST_NOTIFICATIONS`, asked when you open the app) | Only for the one-time "Pause FlipFix for this banking app?" question and the notice that FlipFix is paused. |
| **Your list of apps**<br>(launcher apps, plus Samsung's AOD, font and Wallet packages) | To list your apps under **Choose banking apps…**, to load the small icons of apps whose notifications it shows, and to load Samsung's clock fonts and digit shapes from the phone. Nothing about your apps leaves the phone. |

#### Permissions FlipFix Does Not Use

* **No Internet (`INTERNET`):** Completely offline — no telemetry, no crash reporting, and nothing ever leaves your phone.
* **No Storage or Media access:** Never touches your photos, files or downloads. The only files it reads are Samsung's cover-screen templates and video thumbnails, which any app can read.
* **No Location, Camera, Microphone, or Contacts:** Requests zero personal sensor or account permissions.

#### Banking App Protection

* **Invisible to banking apps when unfolded:** Many banking apps refuse to run while any accessibility service is active. Because FlipFix disables its service the moment you open the phone, banking apps on the main screen never see it running.
* **Cover-screen exclusions:** One UI 9 already blocks banking apps from launching on the cover screen (verified with two banks). If an app does open on the cover screen and complains, select it under **Choose banking apps…** inside FlipFix so FlipFix pauses itself whenever that app is in the foreground. When an app with a finance-sounding name opens on the cover screen, FlipFix also asks once via notification whether to pause for it.
* **One-tap manual toggle:** A **Quick Settings tile** lets you switch FlipFix on or off anytime.

---

### Installation

The [setup page](https://avengerdeveloper.github.io/FlipFix/) does Steps 2 and 3 below from Chrome or Edge, over WebUSB: it installs the latest release and runs the same two commands. Its source is in [`site/`](site/) (`npm ci`, `npm run build`, `npm run serve` for http://localhost:8642). The steps below do the same by hand.

#### Prerequisites
* A Windows, macOS, or Linux computer with [Android SDK Platform-Tools (`adb`)](https://developer.android.com/tools/releases/platform-tools) for the one-time permission grant — **or**, with no computer, an on-phone ADB app connected through **Wireless debugging**.
* *(Optional, only if building from source)* Android Studio or JDK 21.

#### Step 1: Enable USB Debugging on Your Z Flip
1. Open **Settings → Security and privacy → Auto Blocker** and turn it **Off** *(if left on, Samsung greys out USB debugging)*.
2. Go to **Settings → About phone → Software information** and tap **Build number** 7 times until Developer options are enabled.
3. Open **Settings → Developer options**, turn **USB debugging** **On** (or **Wireless debugging** for an on-phone ADB app), connect your Z Flip to your PC via USB, and tap **Allow** on the authorization prompt.

#### Step 2: Install FlipFix

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

#### Step 3: Grant the Two One-Time Permissions
Run both commands in your terminal while your phone is connected:
```bash
adb shell pm grant io.github.avengerdeveloper.flipfix android.permission.WRITE_SECURE_SETTINGS
```
```bash
adb shell cmd notification allow_listener io.github.avengerdeveloper.flipfix/io.github.avengerdeveloper.flipfix.FlipNotificationListener
```
*(Notification access can also be toggled manually under **Settings → Notifications → Device & app notifications**, or from the FlipFix app.)*

#### Step 4: Open FlipFix Once
Open the **FlipFix** app on the main screen. Everything should show as ready; tap anything marked in orange. Allow **notifications** when asked (used for the banking-app question and the "paused" notice) and add the **Quick Settings tile** from there.

#### Step 5: Fold and Unlock
1. Fold your Z Flip, wake the cover screen, and unlock — your video will continue playing uninterrupted.
2. You do **not** need to enable FlipFix manually in Accessibility settings; the app manages its service state automatically.
3. *(Recommended)* Open **Settings → Apps → FlipFix → Battery** and set it to **Unrestricted** so One UI never puts the listener to sleep, then turn **Auto Blocker** back **On** under **Settings → Security and privacy**.

Open the **FlipFix** app on your main screen anytime to check service status, configure banking exclusions, or toggle it off.

---

### Troubleshooting & Diagnostic Tools

The [`tools/`](tools/) folder includes Windows PowerShell scripts to inspect how One UI handles the cover display on your firmware. They only read from your phone, apart from clearing its log buffer before recording, briefly storing a screenshot there (deleted straight after), and `run-covertest.ps1` opening its test app:

| Script | Purpose |
| :--- | :--- |
| `tools/watch.ps1` | Streams live FlipFix logs alongside Samsung's `pause` / `resume` wallpaper commands as you fold, unlock, and swipe |
| `tools/cover-shot.ps1` | Captures cover-screen screenshots (Samsung locked vs. FlipFix unlocked) for side-by-side comparison |
| `tools/probe.ps1` | Dumps read-only display, window, wallpaper, and package information from your device (attach when reporting Flip 5/7 issues — check it first) |
| `tools/capture-unlock.ps1` | Records the phone's log while you unlock and runs `probe.ps1` before and after, to help diagnose firmware-specific timing issues |
| `tools/run-covertest.ps1` | Opens a separate test app on the cover screen after unlock, to check whether Samsung's cover video can be paused and resumed on your firmware. Install the app first from the [`covertest/`](covertest/) module (`./gradlew :covertest:installDebug`) |

---

## Disclaimer & License

FlipFix is experimental test software, provided "as is" without warranty of any kind: you install and use it at your own risk, and the author and contributors are not responsible for any problem it causes. Read the full [disclaimer](DISCLAIMER.md) for how it was tested, what wasn't tested, its battery use and how to remove it.

FlipFix is an independent open-source project and is not affiliated with, endorsed by, or associated with Samsung Electronics. Galaxy, One UI and Samsung Wallet are trademarks of Samsung Electronics. It relies on undocumented One UI behaviour that a future update may change.

Licensed under the [MIT License](LICENSE).
