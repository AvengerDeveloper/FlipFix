# One UI 9 Flip cover screen: research for FlipFix

Research date: 2026-11-15. Scope: Galaxy Z Flip 8 (ships with One UI 9.0 / Android 17) and Flip 5/6/7/7 FE updated to One UI 9.

**Confidence tags used throughout**

- **[CONFIRMED]**: several independent sources agree, or I read primary source code that shows it.
- **[SINGLE]**: one source only (a single article, forum post, README, or decompiled file).
- **[SPEC]**: my inference or speculation. Test it on a device before relying on it.
- **[UI9]**: the fact is about One UI 9. **[UI8]** / **[UI7]**: older One UI. **[UI?]**: version unclear.

Important caveat about the "code" evidence: the decompiled Samsung sources I could read are **One UI 8.0 SystemUI and framework** (repo `488315/android_samsung_frameworks_base`, imported from Galaxy S25+ firmware `S936USQU4ZYFA`/`S936USQU5ZYHB`, mid-2025). This SystemUI is shared across devices, and the Flip cover-screen code paths (`subscreen`, `CoverWallpaperController`, `LsRune.SUBSCREEN_UI`, and so on) are present but feature-flagged. I found **no published decompiled One UI 9 SystemUI**. `cybr47/sc_oneui9` is an empty placeholder. So every internal-architecture claim below is **[UI8]** unless it says otherwise, and One UI 9 may have changed it.

Reddit (`www.reddit.com`, `old.reddit.com`) refused automated fetches, so Reddit content appears only where press articles or search snippets quoted it.

---

## Executive summary

**What can be done**

- An accessibility-service overlay of type `TYPE_ACCESSIBILITY_OVERLAY` on the cover display, built with `createDisplayContext(coverDisplay).createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)`, is a proven technique on Flip cover screens. An open-source MIT project (`tyejaedon/Cover-Screen-OS`) uses exactly this design. Commercial apps (CoverScreen OS, DroidOS Portals, 9 Flip Launcher) say they draw over Samsung's unlocked cover UI on One UI 9.
- An **Activity launched on display 1** is the other proven route. OpenCover (GPL-3.0) re-launches its own home Activity whenever `ACTION_USER_PRESENT` fires while folded, or whenever Samsung's `SubHomeActivity` comes to the front.
- The cover display is **display ID 1** on Flip 5+ [CONFIRMED, UI8].

**What cannot be done (without root)**

- No source shows anyone reading the currently set cover video file without root. All the candidate locations are private app directories of system processes.
- No native toggle brings back "wallpaper and clock as the unlocked cover home" on One UI 9. Samsung has made no statement and announced no fix as of early November 2026.

**Top 5 findings**

1. **The change is real and widespread [UI9, CONFIRMED].** The wallpaper/clock page is now effectively the cover lock screen. After unlock you land on Now Brief plus favorite apps. If you turn that off with *Settings > Cover screen > "Show the Now brief and favorite apps"*, you land on the widget pages instead. Samsung has not responded. Android Authority's poll found 70% of voters saying "Worse."
2. **Samsung's cover wallpaper, including video, is a real `WallpaperService` surface on display 1 [UI8, SINGLE source but primary code].** SystemUI's `ImageWallpaper` creates a `VideoEngine` for wallpaper type 8 (video) on the cover. It reads the path through `CoverWallpaperController`. A Flip 8 owner reported that after unlock "the GIF kept playing behind a blurry overlay" [UI9]. Together these suggest a better design than decoding the video yourself: **a transparent Activity on display 1 with `windowShowWallpaper=true` may reveal Samsung's own live cover wallpaper** [SPEC, needs one device test].
3. **The overlay approach is validated, with caveats [CONFIRMED UI7/UI8, vendor claims for UI9].** Accessibility overlays receive touch on the cover. Samsung's top-edge pull-down is an `InputMonitor` gesture monitor that calls `pilferPointers()` [UI8 code]. So Samsung's swipe-down probably still works *over* your overlay, and OpenCover observed exactly that. Also, banking apps refuse to run while an accessibility service is on, which two projects work around.
4. **The video asset is not readable without root [CONFIRMED that it is private; SPEC that nothing leaks].** Lock-screen video wallpapers are copied to `/data/user_de/0/com.android.systemui/files/video_wallpaper_<user>_<which>.<ext>` [UI8 code]. Pre-unlock (FBE) cover copies go to `/data/user_de/0/com.android.systemui/files/fresh_pack_sub/` [UI8 code]. The cover wallpaper itself is supplied by Wallpaper & style (dressroom, which runs as **system UID 1000**). `SemWallpaperManager.getVideoFilePath(which)` returns only a path string. Treat a user-picked file (photo picker) as the main path, not a fallback.
5. **Native partial workaround to test first [SINGLE].** Samsung has a "**Stay on Lock screen (until swipe)**" option for face recognition. OpenCover's README also describes a fingerprint variant on a Flip 5 / One UI 8. With it on, a biometric unlock leaves you on the cover lock screen (video + clock) until you swipe. That is close to the old behavior for free. Its behavior on One UI 9 cover screens is unverified.

---

## 1. Complaints and scope

### 1.1 What changed in One UI 9 [UI9]

| Behavior | Before (One UI 5–8) | One UI 9 | Confidence |
|---|---|---|---|
| Landing view after unlocking on the cover | Wallpaper + clock (the "unlocked lock screen"). Home returned you there. | Now Brief card + up to 5 favorite apps. If the toggle is off, the first custom widget page. The wallpaper/clock page is now effectively lock-screen only; to see it again you must lock the phone. | CONFIRMED (Android Authority, SamMobile ×2, Android Police, AndroidPure, SammyGuru, Samsung Community) |
| Video/GIF wallpaper after unlock | Kept playing as the cover home background | Plays only on the lock screen. Behind the unlocked home, owners see a **blurred** background or a flat colour. | CONFIRMED that blurred backgrounds are a complaint (AA, SammyGuru). SINGLE: the GIF keeps playing *behind* the blur on a Flip 8 (Samsung Community, Sep 2026). SINGLE: "moving wallpapers play only on lock screen" (CoverScreen OS developer). |
| Notifications gesture | Swipe right (from the left edge) | Swipe down from the top-left = notifications; swipe down from the top-right = quick panel. Or swipe down, then left. | CONFIRMED (SamMobile, Android Police, Samsung CA support page for Flip 8, Samsung Community) |
| Swipe up | Samsung Wallet (from the cover home) | On the unlocked home: **app drawer** (new). Samsung's Flip 8 support pages still list "Swipe up: Samsung Wallet" (apparently on the lock-screen/clock view). Swipe up and hold = recents. | CONFIRMED for the app drawer; SINGLE/unclear for where Wallet lives now |
| Sideways swipe | Widgets carousel | Widgets carousel. Now Brief is a fixed first page that cannot be moved. | CONFIRMED |
| Double-tap to turn off the unlocked cover | Worked | Gone | CONFIRMED (AA, SammyGuru) |
| Widgets | Third-party widgets allowed via MultiStar | More restricted customization. Some users report third-party widgets removed; widgets can be added from the cover itself; new Bixby and Internet widgets; app shortcuts in a smaller card. | CONFIRMED for restriction complaints; SINGLE for third-party removal |
| Cover quick panel | Editable? | Cannot be edited, no long-press on toggles | CONFIRMED (SamMobile, Samsung support) |
| MultiStar cover apps | Launcher Widget required | Native app drawer; MultiStar adds apps to it. On Flip 8 the aspect-ratio button is built in. User selections often **did not migrate** across the update. | CONFIRMED |
| Work-profile apps on the cover | Accessible | Some users lost easy access | SINGLE (AA) |

Timeline [CONFIRMED]: One UI 9 shipped on the Flip 8 (Unpacked, July 2026). It reached Flip 7 in the US in early October 2026 (Verizon 10/02, AT&T around 10/05), and Verizon's Flip 6 also got it. A Flip 5 owner reported One UI 9 by early November. Flip 3/4 do not get One UI 9 (per the CoverScreen OS developer [SINGLE]).

### 1.2 Has Samsung acknowledged it or promised a fix? [UI9]

- **No.** As of Nov 3–7, 2026, Android Authority, SammyGuru and AndroidPure all report that Samsung had not responded and that nothing in Settings suggests the old behavior is returning [CONFIRMED].
- On the 374-reply Samsung Community thread "[Z Flip 7] ONE UI9 problems with the cover screen", a moderator was tagged but no staff reply is visible [CONFIRMED by reading the thread].
- On the Flip 8 thread "Z Flip 8 Cover Screen issue disabling Now Brief", the only moderator advice was *Settings > Lock screen and AOD > Now Bar > toggle off Now Brief*. That disables Now Brief everywhere [SINGLE].
- Samsung reportedly plans an Always On Display update in November with no date. That came from a blog, not Samsung [SINGLE, SPEC].
- Good Lock: MultiStar 11.0.59 (June 2026) and LockStar ~9.0.00.5 (July 2026) added One UI 9 support. **LockStar adds cover-screen unlock *animations*** (Swirl, Wave & Ripple, Curtain, Mosaic), not a persistent unlocked wallpaper. No Good Lock module is reported to restore the old unlocked cover home [CONFIRMED for the updates; no source for a restore feature].

### 1.3 Settings and "hidden" options people found [UI9]

| Option | Effect | Confidence |
|---|---|---|
| Settings > Cover screen > **Show the Now brief and favorite apps** (on by default) | Off: unlock goes to the first widget page. Does **not** restore the wallpaper/clock home. | CONFIRMED |
| Settings > Lock screen and AOD > Now Bar > Now Brief | Disables Now Brief system-wide | SINGLE (Samsung moderator) |
| Set a PIN / secure lock | Several owners say it restored the clock, battery and camera shortcut on the cover, but only as a *lock* screen; others say it did nothing | SINGLE, contradictory |
| Turn on AOD + "Show on wallpaper"; screen lock = Swipe; uninstall Good Lock | One user restored the time/date on the cover; another confirmed it worked | SINGLE (anecdotal) |
| Double-tap the *locked* cover | Shows the custom wallpaper/clock screen | CONFIRMED that it is the only way to see it |
| Face recognition > **Stay on Lock screen until swipe** | After a face unlock you stay on the lock screen (on the cover: wallpaper + video + clock) until you swipe. OpenCover describes a fingerprint "Stay on Lock screen" setting on Flip 5 / One UI 8 that it toggles with Shizuku. | SINGLE for fingerprint; Samsung support documents the face option; **cover behavior on One UI 9 untested** [SPEC] |
| Clear "Cover Screen Services" cache, reapply wallpaper via Themes | Suggested for a Flip 8 whose wallpaper became a plain colour | SINGLE (search snippet of Samsung Members) |

No ADB `settings put` key, `cmd` command or hidden toggle that restores the old flow was found anywhere (Samsung Community, XDA via search, press, GitHub) [CONFIRMED absence, as far as search reaches].

---

## 2. Community fixes: what works and what fails

### 2.1 Third-party apps

| App | How it works technically | Restores "video as main unlocked cover view"? | Permissions | Notes |
|---|---|---|---|---|
| **CoverScreen OS** (IJP, `apps.ijp.coverscreenos`, Play open testing, updated Nov 13, 2026) | Accessibility service (Back/Home) + notification listener. The developer says it "almost fully replaces Samsung's cover screen home" on Flip 5/6/7/7 FE/8 with One UI 9, "with no Samsung screen flashing through". Closed source. | **Partly**: its own "clockface" accepts photos, GIFs or **videos** (a user-chosen file, not Samsung's). | Accessibility, Notification access | Vendor claims [SINGLE]. Its Play description still lists only Flip 3–7 FE. Paid tiers. |
| **Cover-Screen-OS / "CoverOS"** (`tyejaedon/Cover-Screen-OS`, **MIT, open source**, pushed 2026-11-14) | `AccessibilityService` adds a **`TYPE_ACCESSIBILITY_OVERLAY`** window via `createDisplayContext(display)` then `createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)`, hosting a ComposeView. Flags: `FLAG_LAYOUT_IN_SCREEN \| FLAG_NOT_FOCUSABLE`, toggling `FLAG_NOT_TOUCHABLE` to "suppress". Picks the cover via `DisplayManager` (first valid non-default display in ON/DOZE/ON_SUSPEND/VR). Lock state from `SCREEN_ON/OFF`, `USER_PRESENT`, `USER_UNLOCKED` + `KeyguardManager` polling. Reclaims the overlay when `com.sec.android.app.launcher` comes forward; ignores SystemUI/AOD windows. Hides itself when unfolded past 30° (Jetpack `FoldingFeature`). | Its own wallpaper layer supports MP4/WebM video (≤10 s, ≤64 MiB, muted), from a user-picked file | Accessibility, notification listener, foreground service. **No SYSTEM_ALERT_WINDOW.** | **Closest match to FlipFix's planned architecture; read it first.** Its doc says it was verified on One UI 6.1/7. Its own device test table is empty. One UI 9 status unknown. |
| **OpenCover** (`Shmubbier/OpenCover`, **GPL-3.0**) | **Activity-based.** Starts its home `CoverActivity` on display 1 with `ActivityOptions.setLaunchDisplayId(1)` **from the accessibility service context** (exempt from background-activity-start limits). Re-opens on `ACTION_USER_PRESENT` when the cover is active, and whenever a `TYPE_WINDOW_STATE_CHANGED` event on display 1 shows `com.android.systemui.subscreen.SubHomeActivity` while unlocked. Draws its own nav bar over Samsung's. Detects Samsung's cover panel window (`SubScreenQuickPanel`) and closes it with `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`. | Animated GIF/WebP backgrounds (converted to video once, hardware decoded); no video import mentioned | Accessibility (required), overlay (required), notification access; optional `WRITE_SECURE_SETTINGS` (adb) and Shizuku | Tested on Flip 5, One UI 8 (Android 16). Has a "banking mode" because banking apps refuse to run while accessibility is on. Writes the MultiStar cover allow-list (see 3.3). |
| **Cover Launcher** (`mazWaz/cover-launcher`) | Forces the folded device state with `cmd device_state` (shell); launches apps on display 1 via Shizuku; "whenever Samsung's cover home would show, the launcher comes back" | Not a wallpaper app | Shizuku, accessibility | For phones with a broken inner screen; leaves a shell process running |
| **DroidOS Portals** (KatsuyaMaki LLC, Play internal/closed testing; privacy policy updated Nov 14, 2026) | Cover-screen "Home/Recents portals" and **Ambient pages**. "Flash-ring" / **ring navigation** uses an Accessibility service. The paid "Visibility Triggers+" mod "can show Ambient pages **over eligible unlocked cover apps**" using the accessibility service, and explicitly **not** "Display over other apps". Optional lock/AOD Ambient widgets; Shizuku for MultiStar drawer/resize state. | Not specifically; "background choices" exist | Accessibility, Notification access, Usage access, optional Shizuku, RECORD_AUDIO for the visualizer | Closed source. Same developer as the open-source **DroidOS** launcher (`Katsuyamaki/DroidOS`, Shizuku + accessibility, launches on specific display IDs, bypasses Good Lock allow-lists). |
| **9 Flip Launcher** (`duongth.dev.ninefliplaucher`, updated Nov 10, 2026) | Accessibility + notification access + overlay; "No root, no ADB, no Shizuku" | Has "wallpaper personalization"; no claim about video | Accessibility, Notification, overlay | Changelog says "Continued optimization for One UI 9". The developer replied to a review that they tested "extensively on One UI 9" with Flip 5s. A Flip 5 / One UI 9 user could not get it working. |
| **Power Flip Cover** (`com.powerflip.cover`) | A launcher **widget** inside Samsung's cover widgets + accessibility (touch injection for mirroring, floating dock, fold detection) | No | Accessibility, Notification | A search snippet says v0.4.5 opens window-mode apps fullscreen by default for One UI 9 [SINGLE] |
| **Cover HA Overlay** (`KwintenSchram/cover-ha-overlay`) | `TYPE_APPLICATION_OVERLAY` (SYSTEM_ALERT_WINDOW) attached via `createDisplayContext()` to the cover; `WRAP_CONTENT` + `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCH_MODAL` for pass-through; shows on the **locked** cover | n/a | Overlay | Proves SAW overlays render on the Flip 7 cover (One UI 7/8) [SINGLE] |
| **CoverLED** (`luksgrin/CoverLED`, GPL-3.0) | `CoverIndicatorActivity` on the cover display (`showWhenLocked`) + SAW | n/a | Overlay, notification access | Tested Flip 5 / One UI 8.0, Flip 7 / One UI 8.5 |
| **FlipLauncher** (`rungruch/fliplauncher`) | Native cover **widget** (AppWidget) relaying home widgets; MultiStar for activity launches | n/a | — | Flip 5 / 7, One UI 8.5 |
| **flip-ctl** (`RakinRkz/flip-ctl`) | Shizuku `cmd device_state state <id>` to force the cover on (CLOSED=0, TENT=1, HALF_OPENED=2, OPENED=3, CONCURRENT_INNER_DEFAULT=4 on Flip 5) | n/a | Shizuku | Flip 5, One UI 8.0 |

### 2.2 Good Lock tricks

- **MultiStar** (I ♡ Galaxy Foldable): adds apps to the cover app drawer. On One UI 9 it no longer needs the Launcher Widget. Selections often need re-doing after the update. Does **not** affect the post-unlock landing page [CONFIRMED].
- **LockStar**: One UI 9 version adds cover unlock animations only [CONFIRMED]. No persistent-wallpaper option found.
- **NavStar / QuickStar**: nothing cover-home related found.

### 2.3 ADB, Shizuku, Tasker and root

| Approach | Status |
|---|---|
| `settings put` keys to restore the old cover home | **None found** anywhere [CONFIRMED absence]. |
| `pm disable-user` / `pm uninstall --user 0 com.samsung.android.smartsuggestions` | This is the Now Brief host (plus Content Capture/Augmented Autofill), per a debloat list built on a Fold 8 / One UI 9 [SINGLE]. It removes Now Brief, but the official toggle already does that for the cover. It will **not** bring back the wallpaper page [SPEC]. |
| Disabling the cover widget host | **Do not do this.** `com.samsung.android.app.aodservice` hosts AOD **and** the cover-screen widgets ("SubLauncher") [SINGLE, debloat list]. Samsung's cover home is `com.android.systemui/.subscreen.SubHomeActivity` (`SECONDARY_HOME`) with a `SubScreenFallback` [UI8 code]. Disabling either would likely break the cover [SPEC]. |
| `WRITE_SECURE_SETTINGS` via `pm grant` + secure setting `multistar_setting_repository` | OpenCover adds every app to slot 26 (`"pkg,userId;..."`) so Samsung lets them open on the cover without "Open phone to continue" [SINGLE, UI8]. |
| Shizuku `am start --display 1 ...` | Works for launching arbitrary apps on the cover (OpenCover, DroidOS, Cover Launcher) [CONFIRMED, UI8] |
| Tasker / Bixby Routines | No source found that restores the cover home. Theoretically Tasker plus Shizuku/ADB-WiFi could run `am start --display 1 <your activity>` on the "unlocked" event [SPEC]. |
| Root (Magisk/LSPosed) | **No One UI 9 cover-home module found.** LSPosed modules for One UI 9 exist for other things (`rushiranpise/prism`, `rushiranpise/oneui-recents-toolkit`), so hooking SystemUI's SubScreen/plugin is plausible but undone [SPEC]. |
| Rollback to One UI 8 | Users report they could not roll back (anti-rollback/bootloader) [SINGLE]. |

---

## 3. Technical feasibility of the overlay approach

### 3.1 Cover display identity [CONFIRMED, UI8]

- **Logical display ID 1.** OpenCover hard-codes `COVER_DISPLAY = 1`. Samsung's own `SubHomeActivity.isSubDisplay()` checks `display.getDisplayId() == 1`, and `SubScreenManager` checks `runningTaskInfo.displayId == 1`.
- Samsung's cover quick-panel controller selects displays with the category `"com.samsung.android.hardware.display.category.BUILTIN"`.
- CoverOS discovers the cover dynamically (presentation category first, then any valid non-default display). Cover HA Overlay matches by name/resolution and rejects `FLAG_PRIVATE`, cast and DeX displays. **Recommendation:** do not hard-code 1; prefer a non-default `BUILTIN` display and fall back to ID 1.
- "Cover active" heuristic (OpenCover): `display(1).state == STATE_ON && display(0).state != STATE_ON`. CoverOS also requires the `FoldingFeature` posture to be CLOSED.
- Samsung wallpaper "which" flags (framework constants, UI8): `FLAG_SYSTEM=1`, `FLAG_LOCK=2`, `FLAG_DISPLAY_PHONE=4`, `FLAG_DISPLAY_DEX=8`, **`FLAG_DISPLAY_SUB=16`**, `FLAG_DISPLAY_VIRTUAL=32`. The Flip cover uses `which = 17` (system|sub) when `WALLPAPER_SUB_WATCHFACE` is set. `WhichChecker.isSupportLock()` returns false for the watch-face cover, so in UI8 the cover wallpaper has **no separate lock variant**.

### 3.2 Can third-party windows draw above Samsung's unlocked cover UI?

| Window kind | Evidence | Version |
|---|---|---|
| `TYPE_ACCESSIBILITY_OVERLAY` via `createDisplayContext` + `createWindowContext` | CoverOS (open source); its docs say "Verified to accept `TYPE_ACCESSIBILITY_OVERLAY` via `createDisplayContext(coverDisplay)` on One UI 6.1 / 7" | CONFIRMED UI7 (single primary source plus a design that works). One UI 9: vendor claims from CoverScreen OS and DroidOS Portals ("over eligible unlocked cover apps" via accessibility) [SINGLE each]. |
| `AccessibilityService.attachAccessibilityOverlayToDisplay(displayId, SurfaceControl)` (API 34+) | Documented AOSP API, independent of the active windows on that display | Not seen used on Flip [SPEC]; a cleaner alternative to a window |
| `TYPE_APPLICATION_OVERLAY` (SYSTEM_ALERT_WINDOW) | Cover HA Overlay, CoverLED, OpenCover (keep-awake/pop-ups) | CONFIRMED UI7/UI8 |
| Activity with `setLaunchDisplayId(1)` | OpenCover, DroidOS, Cover Launcher, Samsung's own apps | CONFIRMED UI8. Samsung blocks non-allow-listed apps with "Open phone to continue" unless they are on MultiStar's list or launched from the shell [CONFIRMED]. OpenCover reports its *own* activity, started from its accessibility service, works. |

**Touch input:** CoverOS's overlay is touchable (it toggles `FLAG_NOT_TOUCHABLE` to pass through). OpenCover's activity obviously receives touch [CONFIRMED UI7/8].

**Gotchas found**

- **Samsung's top-edge pull-down cannot be blocked by your window.** `SubScreenQSEventHandler` registers `InputManager.monitorGestureInput("quickpanel", coverDisplayId)` and calls `inputMonitor.pilferPointers()` once it decides the drag is a panel swipe [UI8 code]. OpenCover confirms Samsung's panel opens over its activity, and has an experimental "Top-edge priority" mode to close it again. **That is good for FlipFix:** "swipe down = notifications" may work natively over the overlay [SPEC for UI9].
- **Samsung re-asserts its cover home.** Both OpenCover and CoverOS watch window events and reclaim the screen. Expect SystemUI to bring `SubHomeActivity` / the Now Brief page forward on fold, unlock and Home.
- **Banking apps** refuse to run while any accessibility service is enabled. OpenCover and mazWaz both implement a banking mode that toggles their own service off (needs `WRITE_SECURE_SETTINGS`) [CONFIRMED].
- **Sideloaded accessibility on Android 13+** needs *Settings > Apps > (app) > ⋮ > Allow restricted settings* [CONFIRMED].
- **One UI background killing:** use a foreground service, battery-optimization exemption, `START_STICKY`, and a boot receiver. Force-stop disables the accessibility service [CONFIRMED].
- **During calls/alarms:** hide the overlay (CoverOS has call passthrough policies; OpenCover checks the window list so it never covers a call or alarm).

---

## 4. The video asset

### 4.1 How Samsung stores and renders it [UI8 code unless noted]

- **Setting the cover video [UI9 support page]:** Settings > Cover screen > Add new > Gallery > pick a video > position/pinch-zoom > optional Frame/Effect/Weather, and Trim for video. Supported on Flip 6, 7, 7 FE and 8. The applied asset is therefore a **trimmed/cropped copy**, not the gallery original [SPEC].
- **Who owns it:** Wallpaper & style (`com.samsung.android.app.dressroom`) runs as **`android.uid.system` (UID 1000)** [SINGLE: Djini.ai advisory, Jul 2026, tested on S24/S25/S26]. It hands wallpapers to SystemUI through the "plugin lock" wallpaper path. `PluginLockWallpaper` stores a path, bitmap or URI per screen. Path markers include `com.samsung.custompack`, `SamsungUX.DW.FreshP` (DLS pack), `SamsungUX.DW.Stub` and multipack.
- **Candidate on-device locations (all private):**
  - `/data/user_de/0/com.android.systemui/files/video_wallpaper_<userId>_<which>.<ext>` (and a `_temp` variant), written by SystemUI's `VideoFileSaveService` for video lock wallpapers set through `setVideoLockscreenWallpaper(path, …, which)`.
  - `/data/user_de/0/com.android.systemui/files/fresh_pack_sub/`: FBE (before first unlock) copies of the sub/cover wallpaper (`PluginWallpaperManagerImpl.FBE_SUB_PATH`); main screen uses `fresh_pack/`.
  - `/data/overlays/homewallpaper/<uri host+path>/wallpaper_N.(gif|jpg|png|mp4)`: multipack wallpapers (scanned by `CoverWallpaperController`).
  - `/data/user_de/0/com.samsung.android.app.dressroom/…`: the dressroom app's own store (as the brief believes; not confirmed by any source I found).
  - `/data/system/users/0/` holds AOSP wallpaper files (images, not Samsung video) [SINGLE, low-authority blog].
- **APIs that expose metadata** (`android.app.SemWallpaperManager`, implemented by `WallpaperManager` on Samsung):
  - `semGetWallpaperType(which)`: `SEM_WALLPAPER_TYPE_IMAGE=0`, `MOTION=1`, `MULTIPLE=3`, `ANIMATED=4`, `GIF=5`, `EXTERNAL_LIVE=7`, **`VIDEO=8`**, `DEPRECATED_DLS=9`, `DLS=1000`.
  - `getVideoFilePath(which)`, `getVideoFileName(which)`, `getVideoPackage(which)`, `semGetUri(which)`, `isVideoWallpaper()`, `getLidState()` (0 = closed).
  - All hidden/OEM APIs reached via reflection. Whether the server side enforces a permission is **unknown** [SPEC]. Even with a path, the file sits in a private directory that untrusted apps and shell cannot open [SPEC, consistent with SELinux defaults].
- **Settings keys seen in code** (Settings.System; meaning unclear): `lockscreen_wallpaper`, `lockscreen_wallpaper_sub`, `lockscreen_wallpaper_transparent`, **`sub_display_lockscreen_wallpaper_transparency`**, `sub_display_system_wallpaper_transparency`, `plugin_lock_wallpaper_type`, **`plugin_lock_wallpaper_type_sub`**, `dls_state`, `current_sec_active_themepackage`. These describe type/source flags, **not a file URI** [UI8 code].
- **Content providers:** `content://com.samsung.android.app.dressroom.provider` (methods called by framework backup code: `custom_pack`, `layered_image`, `reset_edit_info`). The dressroom receiver needs `WRITE_SECURE_SETTINGS`. Provider access for third parties is unknown; assume signature-protected [SPEC].
- **Logcat leaks the path string:** SystemUI logs `KeyguardVideoWallpaper: update new video wallpaper! path = …` and `PluginLockWallpaper getWallpaperPath() path: <last 80%>`. An app granted `READ_LOGS` via `adb shell pm grant <pkg> android.permission.READ_LOGS` could learn the filename and type, but not read the bytes [SPEC].
- **Smart Switch backups** include `WALLPAPER_SETTING/WALLPAPER_SETTING.zip` with AES-128-CBC encrypted members (`profiles.zip.enc` …) [SINGLE]. Not a practical extraction route [SPEC].

### 4.2 Has anyone read it without root?

**No source found** [CONFIRMED absence]. Every third-party app with video clock faces (CoverScreen OS, CoverOS, OpenCover) makes the user **pick the media themselves**.

---

## 5. Gestures

| Need | Finding | Confidence |
|---|---|---|
| `performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)` on the cover | CoverOS maps swipe-down to it, but no source reports what it does on the cover. In UI8 SystemUI, the cover quick-panel controller (`SubScreenQuickPanelWindowController`, a `CommandQueue.Callbacks`) implements **`animateExpandSettingsPanel`** and `animateCollapsePanels`, but **not** `animateExpandNotificationsPanel`. So `GLOBAL_ACTION_QUICK_SETTINGS` likely opens the cover panel. `GLOBAL_ACTION_NOTIFICATIONS` may open the (invisible) main-display shade or nothing. | UI8 code [SINGLE]; behavior on UI9 [SPEC] |
| Dismissing Samsung's cover panel | `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` removes it; `GLOBAL_ACTION_BACK` only collapses it and leaves an invisible window that "swallows touches for ~2.5 s" | SINGLE (OpenCover, UI8) |
| Letting Samsung handle the gesture | Samsung's top-edge swipe uses a pilfering input monitor (see 3.2), so it likely works natively over FlipFix's overlay. On One UI 9: top-left down = notifications, top-right down = quick panel. | UI8 code + UI9 docs [SPEC that it passes through on UI9] |
| Sideways swipe = reveal widgets | Make the overlay non-touchable (`FLAG_NOT_TOUCHABLE`) or detach it, then optionally replay the swipe with `dispatchGesture()` using `GestureDescription.Builder.setDisplayId(coverId)` (API 30+) so Samsung's carousel moves | SPEC (standard APIs; CoverOS uses the touchable toggle) |
| Samsung Wallet onto the cover | Package **`com.samsung.android.spay`** (also `com.samsung.android.spaymini` on some devices). Samsung's side-key "Quick access" uses **`Intent(ACTION_VIEW, Uri.parse("samsungpay://simplepay/sidekey"))`** (from SecSettings `SamsungPayAction`). SystemUI's cover policy treats `com.samsung.android.spay` as allowed on the cover / when cover-locked (`SubScreenManager`; `CoverScreenManager` skips re-launching cover home when Samsung Pay is on top). Try `startActivity(viewIntent, ActivityOptions.makeBasic().setLaunchDisplayId(1).toBundle())` from the accessibility service. The `.provisioning.wallet.WalletProvisioningActivity` component is not exported. | Package CONFIRMED; deep link SINGLE (decompiled settings, One UI version unconfirmed); cover launch SPEC |
| One UI 9 cover gesture map (stock) | Swipe down top-left = notifications; top-right = quick panel; sideways = widgets; up (unlocked home) = app drawer; up + hold = recents; Samsung's docs still say swipe up = Wallet (likely from the lock/clock view) | CONFIRMED (Samsung support, SamMobile) |

---

## 6. Rendering details (to mimic, or reuse, Samsung's look)

- **The cover wallpaper is a `WallpaperService` surface [UI8 code, SINGLE].** SystemUI's `com.android.systemui.ImageWallpaper` (a Samsung `LiveWallpaperService` subclass) creates one `IntegratedEngine` per display/which. Engine choice by `semGetWallpaperType(which)`:
  - 0 → image (canvas/GL) engine
  - 1 → `MotionEngine`
  - 3 → `MultipackEngine`
  - 4 → `AnimatedEngine`
  - 5 → `GifEngine` (`GifSource`; `LsRune.WALLPAPER_PLAY_GIF` gates GIF on display 1)
  - **8 → `VideoEngine(new VideoSource(...))`**. For the watch-face/cover "which", `VideoSource` gets the path from `CoverWallpaperController.getWallpaperPath()`, which comes from the plugin wallpaper manager or the FBE `fresh_pack_sub` copy.
- **The lock-screen video is a keyguard view [UI8 code].** `KeyguardVideoWallpaper` is a `SystemUIWallpaper` view that adds a `SurfaceView` and plays with **`SemMediaPlayer`**. It reads `WallpaperManager.getVideoFilePath(which)`; defaults live under `/prism/etc/common/` or in a theme package.
- **Implication for One UI 9 [SPEC]:** if One UI 9 still renders the cover video via `ImageWallpaper` on display 1, a FlipFix Activity on display 1 whose theme sets `android:windowShowWallpaper=true` with a transparent background would show **Samsung's own live cover wallpaper** (exact crop, trim and effects), with no file access needed. The Flip 8 report of the GIF "still playing behind a blurry overlay" after unlock supports this. Two risks: One UI 9 could have moved the cover video into the keyguard view (then it would vanish on unlock), or the `VideoEngine` may pause when keyguard is not showing (`VideoEngine` takes a `KeyguardUpdateMonitor`).
- **Clock and cover home:** the cover home is SystemUI `SubHomeActivity` / `SubScreenPresentation` hosting a **SubScreen plugin** (`PluginSubScreen`). The cover widgets/"SubLauncher" live in `com.samsung.android.app.aodservice`. The clock style picker offers style, font, colour and "displayed details" (Settings > Cover screen > Manage, or long-press the cover). LockStar on UI9 adds unlock animations. No source documented the exact cover clock fonts or metrics. Inspect `/system/fonts` and screenshots on a device [SPEC].
- **Cover display traits:** SubScreenManager requests a 120 Hz minimum refresh-rate token for "subhome" [UI8 code]. The cover quick panel adjusts for rotation 2 (upside down). The Flip 4 cover wallpaper was 512×260 (older). Measure the Flip 8 cover with `DisplayManager` at runtime.

---

## Implications for FlipFix

### Confirmed by this research

- **AccessibilityService + `TYPE_ACCESSIBILITY_OVERLAY` on the cover via `createDisplayContext` + `createWindowContext`** is a working pattern; `tyejaedon/Cover-Screen-OS` (MIT) is essentially the same architecture. No `SYSTEM_ALERT_WINDOW` is needed [CONFIRMED UI7, vendor-claimed UI9].
- **`ACTION_USER_PRESENT` while folded** is the right trigger. OpenCover uses exactly this, plus a re-trigger when `SubHomeActivity` appears on display 1. Add `SCREEN_ON/OFF` and a `KeyguardManager.isKeyguardLocked` check [CONFIRMED UI8].
- **Overlays receive touch** on the cover [CONFIRMED UI7/8].
- **The cover is display 1**, but discover it dynamically [CONFIRMED UI8].
- **NotificationListenerService, TextClock and `ACTION_BATTERY_CHANGED`** for the status UI is what every comparable app does [CONFIRMED].

### Contradicted, or in need of adjustment

- **"Read the currently set cover video automatically" is not achievable without root** on the evidence available. Make **user-picked video** (Android Photo Picker / SAF) the primary flow, with crop and position, like CoverOS/CoverScreen OS. Read `semGetWallpaperType(17/18)` by reflection only to *detect* that a video is set and to prompt the user [CONFIRMED that no one does it; SPEC on API access].
- **`GLOBAL_ACTION_NOTIFICATIONS` is probably the wrong tool for "swipe down".** Samsung's cover panel is driven by its own input monitor and responds to `animateExpandSettingsPanel`, not the notifications callback (UI8 code). Better: leave a top strip where your overlay is not touchable, or simply rely on Samsung's pilfering monitor, and hide the overlay when SystemUI's panel window appears on display 1. As a fallback, use `GLOBAL_ACTION_QUICK_SETTINGS` or replay a top-left swipe with `dispatchGesture` + `setDisplayId` [SPEC].
- **Sideways swipe:** in One UI 9 the widgets page may already be what sits under your overlay (if the Now Brief toggle is off). "Hide overlay" is enough; set `FLAG_NOT_TOUCHABLE` + alpha 0 instead of detaching, for instant restore [SPEC].
- **Swipe up:** on unlocked One UI 9 this is Samsung's app drawer. For Wallet, fire `samsungpay://simplepay/sidekey` (ACTION_VIEW) or the `com.samsung.android.spay` launcher intent with `setLaunchDisplayId(coverId)` from the service context [SPEC; test].
- **Accessibility side effects:** plan a banking-app pause mode and the "Allow restricted settings" onboarding [CONFIRMED].

### Better approach worth testing first: "Wallpaper-window" mode [SPEC, high value]

Instead of (or in addition to) an accessibility overlay playing ExoPlayer:

1. Ship a minimal transparent Activity `CoverHomeActivity` with `android:windowShowWallpaper="true"`, `windowIsTranslucent`, no background, `showWhenLocked=false`, and the clock/date/battery/notification-icon views on top.
2. On `ACTION_USER_PRESENT` while folded (and whenever `SubHomeActivity` / the Now Brief window comes forward on display 1), start it on display 1 from the AccessibilityService context with `ActivityOptions.setLaunchDisplayId(1)`. This is OpenCover's proven mechanism. Your own package might need to be on MultiStar's cover list (`multistar_setting_repository` slot 26) or launched via Shizuku if Samsung blocks it with "Open phone to continue".
3. If Samsung's `ImageWallpaper` still renders the cover video on display 1 in One UI 9, you get the **exact current video with no file access, no decoding cost, and no sync problems**.
4. Gestures: sideways swipe or Home calls `finish()` / `moveTaskToBack`, so Samsung's widgets/Now Brief appear. Down is handled by Samsung's monitor. Up launches Wallet.
5. Keep the accessibility-overlay + ExoPlayer path as the fallback when the wallpaper window shows only grey or a blurred image.

**Quick device experiments to settle the unknowns (no root)**

- `adb shell dumpsys wallpaper`: is there a wallpaper connection/engine on display 1, and what type/which?
- `adb shell dumpsys window windows | grep -iE "wallpaper|SubHome|SubScreen"`, both locked and unlocked on the cover.
- `adb logcat -s ImageWallpaper KeyguardVideoWallpaper CoverWallpaperController PluginLockWallpaper SubScreenManager VideoEngine` while setting a cover video and unlocking. This shows which component plays it and the path string.
- `adb shell settings list system | grep -iE "sub_display|lockscreen_wallpaper|plugin_lock"` and `adb shell settings list secure | grep -iE "multistar|cover|sub_"`.
- Launch a test Activity using `Theme.Wallpaper.NoTitleBar` on display 1: `adb shell am start --display 1 -n <pkg>/<activity>`. Do you see the moving cover video, a blurred frame, or grey?
- Accessibility `performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)` vs `GLOBAL_ACTION_QUICK_SETTINGS` while folded: which panel opens, and on which display?
- `adb shell am start --display 1 -a android.intent.action.VIEW -d samsungpay://simplepay/sidekey`.
- Settings > Biometrics: find "Stay on Lock screen (until swipe)" for fingerprint/face. If it keeps the cover lock screen (video) after unlock on One UI 9, document it in FlipFix's README as the zero-install option.

### Still unknown

- Whether One UI 9 changed the cover wallpaper from the "watch-face/system" model (which = 17, `ImageWallpaper`) to a keyguard-only rendering. This is the single most important unknown.
- Whether third-party activities and overlays on display 1 are restricted further in One UI 9. CoverScreen OS and 9 Flip claim One UI 9 support; mixed user reports.
- Exact One UI 9 package/class of the Now Brief cover page. The Now Brief host is `com.samsung.android.smartsuggestions`; the cover page host is unconfirmed.
- Whether the fingerprint "Stay on Lock screen" exists on Flip 8 / One UI 9.

---

## Sources

### Press and news (One UI 9)

- Android Authority, "Samsung changed Galaxy Z Flip's cover screen with One UI 9, and a lot of owners are not happy" (Nov 3, 2026): https://www.androidauthority.com/galaxy-z-flip-one-ui-9-cover-screen-backlash-3731117/
- Android Police, "One UI 9 ruined my Samsung Galaxy Z Flip cover screen; here's how I fixed it": https://www.androidpolice.com/one-ui-9-ruined-my-samsung-galaxy-z-flip-7-cover-screen-how-i-fixed-it/
- SamMobile, "How One UI 9 transforms your Galaxy Z Flip 7 cover screen" (Sep 13, 2026): https://www.sammobile.com/news/how-one-ui-9-transforms-your-galaxy-z-flip-7-cover-screen/
- SamMobile, "One UI 9 is amazing on the Galaxy Z Flip 8, and the cover screen is why": https://www.sammobile.com/news/one-ui-9-amazing-galaxy-z-flip-8-cover-screen/
- SammyGuru, "Some Galaxy Z Flip users aren't impressed with One UI 9's cover screen overhaul" (Nov 4, 2026): https://sammyguru.com/galaxy-z-flip-users-arent-impressed-with-one-ui-9-cover-screen-overhaul/
- AndroidPure, "Hide Now Brief on your Galaxy Z Flip cover screen after One UI 9" (Nov 7, 2026): https://www.androidpure.com/galaxy-z-flip-one-ui-9-cover-screen-now-brief/
- Gadget Hacks, "Samsung One UI 9 changes the Galaxy Z Flip cover screen" (returned 403; search snippets only): https://samsung.gadgethacks.com/news/one-ui-9-galaxy-z-flip-cover-screen-changes-explained/
- Chasms blog, "How to fix Galaxy Z Flip 7 cover screen missing clock and widgets after One UI 9": https://www.chasms.com/blog/how-to-fix-galaxy-z-flip-7-cover-screen-one-ui-9/
- Android Authority, "Galaxy Z Flip 8 cover screen aspect ratio button" (Jul 29, 2026): https://www.androidauthority.com/samsung-galaxy-z-flip-8-cover-screen-aspect-ratio-button-3692241/
- SamMobile, "Good Lock's MultiStar now compatible with One UI 9.0": https://www.sammobile.com/news/good-locks-multistar-now-compatible-with-one-ui-9-0/
- SamMobile, "Good Lock's LockStar now compatible with One UI 9.0": https://www.sammobile.com/news/good-locks-lockstar-now-compatible-one-ui-9-0/
- NPowerUser, "LockStar and NavStar get One UI 9 support, new cover screen unlock animations": https://nokiapoweruser.com/samsung-good-lock-one-ui-9-lockstar-navstar-flip-update/
- Android Authority, "One UI 9 Motion Photo wallpaper": https://www.androidauthority.com/one-ui-9-motion-photo-wallpaper-3699446/

### Samsung official

- Customize the Cover screen on Galaxy Z Flip8 (US): https://www.samsung.com/us/support/answer/ANS10013199/
- Customize the Cover screen on Galaxy Z Flip8 (CA): https://www.samsung.com/ca/support/mobile-devices/customize-the-cover-screen-on-your-samsung-galaxy-z-flip8/
- Set photos or videos as wallpaper (includes the cover screen video): https://www.samsung.com/us/support/answer/ANS10001632/
- Facial recognition, "Stay on Lock screen until swipe": https://www.samsung.com/us/support/answer/ANS10002519/

### Samsung Community threads

- "[Z Flip 7] ONE UI9 problems with the cover screen" (374 replies): https://community.samsung.com/us/conversations/Galaxy-Z-Series/Z-Flip-7-ONE-UI9-problems-with-the-cover-screen/us_3681657
- "Z Flip 8 Cover Screen issue disabling Now Brief": https://community.samsung.com/us/conversations/Galaxy-Z-Series/Z-Flip-8-Cover-Screen-issue-disabling-Now-Brief/us_3633806
- "Z Flip8 Cover Screen Issue" (GIF behind blur): https://community.samsung.com/us/conversations/Galaxy-Z-Series/Z-Flip8-Cover-Screen-Issue/us_3661866
- "Help // Complaint // One UI 9 Update ruined every cover screen feature": https://community.samsung.com/us/conversations/Galaxy-Z-Series/Help-Complaint-One-UI-9-Update-ruined-every-cover-screen-feature/us_3689595
- "One UI 9 Galaxy Z Flip cover screen problems and a cover screen launcher fix" (CoverScreen OS developer): https://community.samsung.com/us/conversations/Galaxy-Z-Series/One-UI-9-Galaxy-Z-Flip-cover-screen-problems-and-a-cover-screen/us_3696809

### Apps and vendor pages

- CoverScreen OS FAQ/page: https://ijp.app/apps/coverscreen-os/
- IJP, "One UI 9 cover screen problems on Z Flip" (vendor): https://ijp.app/posts/one-ui-9-cover-screen-problems/
- CoverScreen OS on Play: https://play.google.com/store/apps/details?id=apps.ijp.coverscreenos
- 9 Flip Launcher on Play: https://play.google.com/store/apps/details?id=duongth.dev.ninefliplaucher
- Power Flip Cover on Play: https://play.google.com/store/apps/details?id=com.powerflip.cover
- DroidOS Portals: https://katsuyamaki.github.io/droidos-portals.html
- DroidOS Portals privacy policy: https://katsuyamaki.github.io/droidos-portals-privacy.html
- DroidOS on F-Droid: https://f-droid.org/en/packages/com.katsuyamaki.DroidOSFOSSLauncher/

### Open-source projects (GitHub)

- Cover-Screen-OS / CoverOS (MIT): https://github.com/tyejaedon/Cover-Screen-OS
  - Read: `CoverComposeSurface.kt`, `CoverAccessibilityService.kt`, `CoverDisplayHelper.kt`, `LockStatusReceiver.kt`, `docs/architecture/Overlay-architecture-shift-plan.md`, `docs/wallpaper-media.md`
- OpenCover (GPL-3.0): https://github.com/Shmubbier/OpenCover
  - Read: `watcher/CoverAccessibility.kt`, `watcher/CoverWatcher.kt`, `apps/AppLauncher.kt`, `apps/CoverAllowList.kt`, `docs/SETUP.md`
- DroidOS: https://github.com/Katsuyamaki/DroidOS
- Cover Launcher (Flip 5, Shizuku): https://github.com/mazWaz/cover-launcher
- flip-ctl: https://github.com/RakinRkz/flip-ctl
- Cover HA Overlay: https://github.com/KwintenSchram/cover-ha-overlay
- CoverLED: https://github.com/luksgrin/CoverLED
- FlipLauncher: https://github.com/rungruch/fliplauncher
- Samsung debloat list (Fold 8 / One UI 9 package notes): https://github.com/itxjobe/samsungdebloat

### Decompiled Samsung code (One UI 8.0, S25+ firmware; not Samsung's original source)

- Repository: https://github.com/488315/android_samsung_frameworks_base
- Files read:
  - `packages/SystemUI/src/com/android/systemui/wallpaper/CoverWallpaperController.java`
  - `packages/SystemUI/src/com/android/systemui/wallpaper/engines/video/VideoSource.java`
  - `packages/SystemUI/src/com/android/systemui/wallpaper/video/VideoFileSaveService.java`
  - `packages/SystemUI/src/com/android/systemui/wallpapers/ImageWallpaper.java`
  - `packages/SystemUI/src/com/android/systemui/pluginlock/PluginWallpaperManagerImpl.java`
  - `packages/SystemUI/src/com/android/systemui/pluginlock/component/PluginLockWallpaper.java`
  - `packages/SystemUI/src/com/android/systemui/subscreen/SubScreenManager.java`
  - `packages/SystemUI/src/com/android/systemui/subscreen/SubHomeActivity.java`
  - `packages/SystemUI/src/com/android/systemui/subscreen/SubScreenQuickPanelWindowController.java`
  - `packages/SystemUI/src/com/android/systemui/subscreen/SubScreenQSEventHandler.java`
  - `packages/SystemUI/src/com/android/systemui/cover/CoverScreenManager.java`
  - `src/android/app/WallpaperManager.java`
  - `src/com/samsung/android/wallpaper/utils/WhichChecker.java`
  - `src/com/samsung/android/wallpaperbackup/WallpaperBackupRestoreManager.java`
- Older SystemUI Jadx (`KeyguardVideoWallpaper.java`): https://github.com/488315/samsung_framework
- Samsung Settings decompile (`SamsungPayAction.smali`, `samsungpay://simplepay/sidekey`): https://github.com/SameerAlSahab/twentysixification
- Samsung Settings (`UsefulfeatureUtils.java`): https://github.com/488315/android_samsung_packages_apps_settings

### Security and other

- Djini.ai, "Samsung Dressroom (Wallpaper & Style) arbitrary file write at system UID" (Jul 30, 2026): https://djini.ai/posts/technical-advisory-samsung-dressroom-wallpaper-style-arbitrary-file-write-at-system-uid/
- AOSP `AccessibilityService` reference (`attachAccessibilityOverlayToDisplay`, `createWindowContext`): https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Samsung developer forum (spay WalletProvisioningActivity not exported), via search snippet: https://forum.developer.samsung.com/t/test-samsung-wallet-using-appium/40768
