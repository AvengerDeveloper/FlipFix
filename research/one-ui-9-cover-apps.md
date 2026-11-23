# Apps on the cover screen (One UI 9), and making FlipFix the cover "home"

Research date: 2026-11-23. Device tests: Galaxy Z Flip6 test phone (SM-F741B, One UI 9.0, Android 17 / SDK 37,
build `CP2A.260605.016` / `F741BXXS4EZJ1`), FlipFix 0.10771 (debug build), three-button navigation, Swipe lock.

**Question.** Many owners run full apps on the cover, through Samsung's own options, Good Lock's MultiStar or other
launchers. After they press Back or Home, or close or swipe away an app on the cover, they should land on FlipFix's
face, not on Samsung's widget screen.

## Summary

- **What exists.** One UI 9 gives the unlocked cover a real app drawer (swipe up), favourite apps, an Apps widget,
  recents (Recents button or swipe up and hold) and a navigation bar. Samsung's own and "Labs" apps run there
  without extras. MultiStar (Good Lock) adds any other app to the same drawer, and no longer needs its Launcher Widget
  since 11.1.04. Third-party cover launchers (OpenCover, CoverScreen OS, DroidOS Portals, 9 Flip Launcher) all use an
  accessibility service. None can replace Samsung's cover home. They bring themselves back when Samsung's home shows.
- **On the Flip6 today, the face comes back in 4 of 8 cases.** If the app was opened **over the face** (from a
  notification, Wallet, or another launcher), Back, Home, swiping it away in recents and "Close all" all return to
  the face. If the user first **swiped the face away** and opened the app from Samsung's widgets or app drawer, all
  four ways land on Samsung's home, with only FlipFix's edge strip. The cause is `CoverState.dismissed`: it stays set
  until the screen goes off, and opening an app does not clear it.
- **No app can become the cover launcher.** On the Flip, Home always starts SystemUI's `SubHomeActivity` with
  `SECONDARY_HOME`, even though the framework's own config points at One UI Home. `framework.jar` contains that
  component name. Only root could change it.
- **Recommendation:** extend the trigger FlipFix already uses. Once a real app has been in front on the unlocked
  cover since the face was swiped away, treat the next arrival of `SubHomeActivity` as "back home" and open the face.
  This needs no new permission or event type, and it keeps every FlipFix promise. Measured on the Flip6: in the cases
  that already work this way, the face has drawn 55 ms after Samsung's home starts, inside Samsung's own closing
  animation.
- **Built** the same day (FlipFix 0.2.1, button navigation, Flip6 folded for real). The face came back after
  Calculator or Clock, whether opened from the widget page, the drawer or a recents card, and left by Back, Home,
  swiping the last card away or "Close all". Opening Recents alone, then Back, stayed on Samsung's widgets, and so did
  Home with the drawer open. Not checked yet: gesture navigation, and a Flip8 film of leaving by Back.

---

## 1. What exists today

### 1.1 Samsung's built-in options

| What | Details | Applies to | Source |
|---|---|---|---|
| **Labs > "Apps allowed on cover screen"** | Settings > Advanced features > Labs. Turn on the switch, then the apps you want. Samsung does not publish a list. Its Flip5 page gives examples (Messages, WhatsApp, YouTube, Netflix, Google Maps). | Flip5/Flip6 pages (pre-One UI 9 wording). On the Flip 8 the Labs apps appear in the One UI 9 drawer next to Samsung's. | Samsung Flip6 FlexWindow page; SamMobile (Flip 8) |
| **Apps supported out of the box** | Flip 8: 16 apps. Twelve Samsung apps are in the drawer (Bixby, Browser, Calculator, Calendar, Camera, Clock, Contacts, Gallery, Health, Notes, Phone, Voice Recorder), plus four Labs apps (Maps, Messages, Netflix, YouTube). 9to5Google says "about 50". On the Flip6 test phone the drawer showed Bixby, Calculator, Calendar, Camera, Clock, Contacts, Gallery, Health, Messages, Phone and SmartThings. | Flip 8 (One UI 9); Flip6 observed | Digital Trends, 9to5Google; device |
| **App drawer** | Swipe up on the unlocked cover home. MultiStar-enabled apps appear here too. Apps not built for the cover are resized. | One UI 9 (Flip 7/8 press; seen on the Flip6) | SamMobile ×2; Samsung JP Good Lock page |
| **Favourite apps** | Up to 5 at the bottom of the Now Brief page. Only cover-compatible apps can be picked. | One UI 9 | Samsung CA Flip8 page |
| **Apps widget** | A widget on the cover's widget pages that holds chosen apps. | Flip8 page | Samsung US Flip8 page |
| **Recents** | Recents button, or swipe up and hold. The Flip6 cover shows One UI Home's `RecentsActivity` with cards and a "Close all" button. | One UI 9 | SamMobile; device |
| **Notifications** | Tap a notification to open its app. Some apps ask you to unfold the phone. | All | Samsung Flip8 pages |
| **Aspect-ratio button** | Built into One UI 9 on the Flip 8. Before, it needed MultiStar. | Flip 8 | Android Authority (via search; page returned 403) |
| **Older Flips** | Digital Trends says the Flip 7 gets the same treatment and that it is unclear for older models. The Flip6 test phone on One UI 9 has the drawer, recents and nav bar. | — | Digital Trends; device |

Samsung's support pages document how to open apps, but not how to leave them. On the Flip6 the cover shows a
navigation bar under apps and on Samsung's home: **Recents | Home | Back**. The test phone's main screen also uses
buttons; the cover in swipe-gesture mode was not tested (§2.5).

### 1.2 Good Lock: MultiStar ("I ♡ Galaxy Foldable")

- **Before One UI 9:** Good Lock > MultiStar > I ♡ Galaxy Foldable > turn on the Launcher Widget. Then
  Settings > Cover screen > Widgets > MultiStar > Launcher. Apps open from that widget. (Samsung JP page, updated
  29 Oct 2026, Flip5 and later.)
- **One UI 9:** MultiStar 11.0.59 (12 Jun 2026) added One UI 9 support. 11.1.04 (2 Jul 2026) launches apps
  without the Launcher Widget. Chosen apps appear in Samsung's swipe-up app drawer. Some apps work without Good
  Lock; extra apps still need MultiStar (Samsung JP). Selections often did not carry over the update (Android
  Authority, from earlier research). (SamMobile, SammyGuru.)
- **How it works (relevant to FlipFix):** MultiStar is Samsung's own app. It keeps its cover allow-list in the secure
  setting `multistar_setting_repository` (a package list; still present on the Flip6 although MultiStar is no longer
  installed). It also loads into SystemUI as a plugin (`secure all_splugin_load_failed=com.samsung.android.multistar|`
  on the Flip6). It **extends Samsung's cover home; it doesn't replace it.** After a MultiStar app you land on
  Samsung's home like after any other app. OpenCover writes the same allow-list with `WRITE_SECURE_SETTINGS`
  (earlier research).
- Samsung's own list of cover apps, including the Labs ones, sits in `secure sub_home_app_list` and
  `secure cover_app_tray_list`.

### 1.3 Other cover launchers

| App | How it becomes "home" | Permissions | One UI 9 |
|---|---|---|---|
| **OpenCover** (GPL-3.0, Flip 5 / Android 16) | Its accessibility service watches window-state events from SystemUI. When `SubHomeActivity` shows on display 1 while unlocked, it starts its own activity there (also on `ACTION_USER_PRESENT`). It **draws its own navigation bar** (accessibility overlay) exactly over Samsung's, so its Home button goes to OpenCover. On Home it raises a full-screen "curtain" overlay until its home is in front, hiding Samsung's. To follow Samsung's bar it reads the window list (`FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, window roots) with every package in scope. | Accessibility, adb `WRITE_SECURE_SETTINGS` (its own service, MultiStar list), usage access, optional Shizuku, INTERNET for opt-in weather | Not stated |
| **CoverScreen OS** (IJP, closed) | Says it "almost fully replaces" Samsung's cover home with no Samsung screen flashing through. Offers its own Back and Home gestures. | Accessibility, notification access | Claims Flip 5/6/7/7 FE/8 on One UI 9 |
| **DroidOS Portals** (closed) | Home/Recents "portals", ring navigation through accessibility. MultiStar drawer and resize state via Shizuku. Paid add-on shows its pages "over eligible unlocked cover apps". | Accessibility (optional), Shizuku (optional), notification and usage access | Not stated |
| **9 Flip Launcher** | Accessibility + overlay + notification access, "no root, no ADB, no Shizuku" (earlier research; Play page didn't load this time). | Accessibility, overlay, notifications | Claims One UI 9 work; mixed user reports |
| **Cover Launcher** (mazWaz) | "Whenever Samsung's cover home would show, the launcher comes back"; launches apps via Shizuku. | Shizuku, accessibility | Flip 5 |
| **Power Flip Cover** | A widget inside Samsung's widget pages, plus accessibility. | Accessibility, notifications | v0.4.5 mentions One UI 9 |

All of them work the way FlipFix does: an accessibility service watches for Samsung's cover home and starts an
activity over it. None registers as the cover launcher (see §4.3).

### 1.4 How people open and leave apps on the cover

| Open | Leave |
|---|---|
| Swipe up for the drawer; favourites on Now Brief; Apps widget; a notification; recents; MultiStar's apps (in the drawer on One UI 9); a third-party launcher's own list | **Back** (nav button or edge swipe), **Home** (button or swipe up), **recents** then swipe the card away or **Close all**, the app closing itself, the cover timing out (then the lock screen) |

Reddit refused automated fetches (as in the November research), and no thread on leaving cover apps was found
through search. The Flip6 tests below are the primary evidence for "what comes next".

---

## 2. Flip6 tests

### 2.1 Setup

- The phone lay open on the desk, so the fold was emulated with `cmd device_state state 0` and undone with
  `cmd device_state state reset`. Samsung switched to the cover and FlipFix's fold rule switched its own service
  on, as with a real fold. **Caveat:** a real fold could differ in small ways (lid sensor). The window and task
  behaviour below doesn't depend on it.
- Cover timeout raised to 120 s for the test (`system cover_screen_timeout`), then set back to 15. Navigation
  stayed on buttons (an attempt to switch to gestures over adb didn't take, and its setting was put back).
  Screenshots and the log stayed in the scratchpad, and files on the phone were deleted.
- Apps: Calculator and Clock (Samsung cover apps), opened from Samsung's widget page, from its drawer, or with
  `am start --display 1` while the face was in front. The adb launch stands in for a notification tap, Wallet or
  another launcher opening an app over the face.

### 2.2 How the cover display is built (what decides "what comes next")

`dumpsys activity activities`, display 1, top first:

```
Task type=standard  io.github.avengerdeveloper.flipfix.cover   CoverActivity        (face in front)
Task type=home      com.android.systemui                       .subscreen.SubHomeActivity
Task type=recents   com.sec.android.app.launcher               com.android.quickstep.RecentsActivity
(other app tasks)
```

- Samsung's cover home is a real **home task** on display 1. Leaving an app reveals whatever task is next. **Home**
  starts `SubHomeActivity` again:
  `START u0 {act=MAIN cat=[SECONDARY_HOME] pkg=com.android.systemui cmp=com.android.systemui/.subscreen.SubHomeActivity}`.
- Swiping the face away calls `moveTaskToBack`, which puts FlipFix's task at the **bottom**, under Samsung's home.
  Apps opened after that go on top, and leaving them reveals Samsung's home, not the face.
- The cover's Recents screen doesn't list FlipFix (`excludeFromRecents`). "Close all" leaves FlipFix's task alone.
- Visible windows on display 1:
  - Face in front: `ScreenDecorOverlayCover`, `CoverActivity`, `ImageWallpaper`. The face hides the nav bar.
  - Samsung's home: `ScreenDecorOverlayCover`, `FlipFix edge`, Wallet's `CoverHintService`, `NavigationBar1`,
    `SubHomeActivity`, `ImageWallpaper`.
  - App: `ScreenDecorOverlayCover`, `NavigationBar1`, the app.
- Samsung's app drawer is part of `SubHomeActivity`: opening it raises no window of its own.

### 2.3 A: face swiped away, app opened from Samsung's home

| Leave by | Samsung shows next | On top of display 1 | FlipFix logs | Face back? |
|---|---|---|---|---|
| **Back** button | Its home, on the page the app came from: the widget page, or the **drawer**, still open, for an app opened from the drawer | `SubHomeActivity` resumed. Calculator's task finished; Clock's moved to the back. | `hiding edge strip: <app> opened on cover` when the app opened, then `edge strip shown` | **No**, edge strip only |
| **Home** button | Its home | `SubHomeActivity` (fresh `SECONDARY_HOME` start); the app's task stays behind it | `edge strip shown` | **No** |
| **Recents**, swipe the card away | Recents stays open while other cards remain. Tapping outside or the last swipe goes to its home. | `RecentsActivity`, then `SubHomeActivity` | `hiding edge strip: com.sec.android.app.launcher / com.android.quickstep.RecentsActivity opened on cover`, then `edge strip shown` | **No** |
| **Close all** | Its home | `SubHomeActivity`; FlipFix's task untouched at the bottom | `edge strip shown` | **No** |

Samsung's side of the log at each return: `SubLauncherPluginRemoteTransition onExitRemoteTransition` (Samsung
animates the app closing into its home), `setSubHomeActivityResumed: true`, and `NavBarController showHomeNaviBar`.

### 2.4 B: app opened while the face was in front

| Leave by | Samsung shows next | On top of display 1 | FlipFix logs | Face back? |
|---|---|---|---|---|
| **Back** | The face. FlipFix's task was directly under the app. | `CoverActivity` resumed; no launch needed | `sent resume (resumed)` / `(focused)`: the face resuming Samsung's video | **Yes** |
| **Home** | Samsung's home starts, and the face covers it at once | `SECONDARY_HOME` → `SubHomeActivity`, then `CoverActivity` | `launched cover face on display 1 (Samsung cover home shown)` | **Yes** |
| **Recents**, swipe the last card away | Recents starts Samsung's home itself (`SECONDARY_HOME`), then the face | `CoverActivity` | `launched cover face … (Samsung cover home shown)` | **Yes** |
| **Close all** | Same as above | `CoverActivity` | `launched cover face … (Samsung cover home shown)` | **Yes** |

**Timing (Home, B):** `SubHomeActivity` START at 18.406. FlipFix's START came 30 ms later (18.436), and the face's
window had drawn at 18.461 (`performShowLocked … CoverActivity`), 55 ms after Samsung's home started. A cover
screenshot taken 250 ms after the tap showed the face already sliding in beside the closing app; no widget page
showed. Swiping the last recents card away gave 41 ms to START and 88 ms to drawn. In case A, FlipFix heard about
`SubHomeActivity` 17 ms after Samsung's home drew. So a launch at that point would fall inside the same closing
animation.

### 2.5 Other observations

- **Home pressed while Samsung's home is already in front** (drawer open) sends `SECONDARY_HOME` again
  (`onNewIntent`), which closes the drawer. FlipFix logged nothing, so no window event reached its handler (or
  `edge.show()` was a no-op). A deliberate visit to the widgets is therefore not disturbed by Home presses there.
- **Wallet's `CoverHintService`** window (the handle at the bottom of Samsung's home, top at y≈676) sits over the
  lower part of the nav bar, and it took a tap aimed at the Recents button at y=682. That is Samsung's behaviour,
  and a real finger lands higher. FlipFix's edge strip (36 dp ≈ 72 px) also covers the left half of the Recents
  button. It replays taps with `dispatchGesture` (`EdgeReturn`); not checked on the nav bar.
- **Not tested:** swipe-gesture navigation on the cover (Samsung's Settings switch didn't take over adb), MultiStar
  (not installed; needs a Galaxy account), and an app outside every allow-list ("open phone to continue").
  Gesture Back and Home end in the same system actions (task finished or moved back, `SECONDARY_HOME` start), so the
  results should hold. Confirm by hand.

---

## 3. What decides when the face opens today (code)

`CoverAccessibilityService.onWindowChanged` (window-state events on the cover display only):

- `isSubHome && keyguard locked` → unlock poll, clears `dismissed`.
- `isSubHome && CoverState.dismissed` → `edge.show()` only. **This is every A case.**
- `isSubHome` → `maybeLaunch("Samsung cover home shown")`. **This is B-Home, B-recents and B-close-all.**
- `isAppScreen` (package not SystemUI, class not `android.*`) → hides the edge strip and runs `BankGuard`. It does
  **not** touch `dismissed`.

`CoverState.dismissed` is set on swipe-away and cleared on screen off, on unlock (`ACTION_USER_PRESENT`) and by the
edge swipe. `maybeLaunch` skips with "dismissed until screen off" while it is set. Back over the face needs no code:
Android simply shows FlipFix's task again.

---

## 4. Ways to make the face the cover home

### 4.1 React when Samsung's home comes back after an app (recommended)

**What:** an app screen came in front on the **unlocked** cover since the face was swiped away. FlipFix should
remember that, for example by clearing `dismissed` (or setting an "app since swipe-away" flag) in the
`isAppScreen` branch. Then the next `SubHomeActivity` event goes through `maybeLaunch`, exactly as in B today. All
four A cases would then behave like B.

**Needs:** nothing new. `typeWindowStateChanged` is already the only event FlipFix receives. No window content, no
window list, no new permission. The Samsung-specific part (recognising `SubHomeActivity`) is already relied on.

**Fit with FlipFix's promises:** full. It stays offline, `canRetrieveWindowContent` stays false, and the trigger
it adds is one FlipFix already uses.

**Details to settle:**

- **Recents alone shouldn't count.** One UI Home's `RecentsActivity` passes the `isAppScreen` test. Opening recents
  from Samsung's widgets and backing out should stay on the widgets. Count the app picked from recents instead.
- **Drawer.** Samsung returns to its open drawer after Back. With 4.1 the face covers it. The user asked for this,
  but it might suit an option ("Return to FlipFix after apps", on by default).
- **Calls, alarms and Wallet's sheet on the unlocked cover** are app screens too. When they end, the face returns.
  That's wanted.
- **Banking apps:** `BankGuard` pauses FlipFix, which switches its service off. No change.
- **Notification-opened apps on unlock:** `notificationJustOpened` keeps the face back while the app opens. With
  4.1 the face also returns after that app.
- **Another cover launcher installed** (OpenCover, CoverScreen OS) reacts to the same `SubHomeActivity` event, so
  the two would race. Without reading windows, FlipFix can only stand down for a launcher package it knows was just
  in front. Otherwise the README should say "use one".
- **Flash:** measured in §2.4. The face draws ~55 ms after Samsung's home starts, inside Samsung's closing animation.
  Check A-Back on the Flip8 film once built: Back in A uses Samsung's CLOSE transition, while Home uses OPEN.

### 4.2 A HOME intent on the cover display

Home on the cover is `MAIN` + `SECONDARY_HOME`, pinned to `com.android.systemui`. If FlipFix fires a HOME or
SECONDARY_HOME intent on display 1, it only opens Samsung's home. **Not useful.**

### 4.3 Registering as the cover launcher

In AOSP, a secondary display's home comes from the default home app's package (its `SECONDARY_HOME` activity),
unless `config_useSystemProvidedLauncherForSecondary` forces `config_secondaryHomePackage`. On the Flip6:

- `config_useSystemProvidedLauncherForSecondary = false`, `config_secondaryHomePackage = com.sec.android.app.launcher`
  (from `framework-res.apk`).
- One UI Home (the default home) has a `SECONDARY_HOME` activity, `com.honeyspace.dexservice.SecondaryLauncher`.
- Yet the cover's Home starts `com.android.systemui/.subscreen.SubHomeActivity` with `pkg=com.android.systemui`.
  `framework.jar` contains the string `com.android.systemui/com.android.systemui.subscreen.SubHomeActivity`.

So Samsung pins display 1's home to SystemUI. Not even One UI Home gets the cover, and a third-party default launcher
wouldn't either. Changing it would take root (an LSPosed hook into the framework or SystemUI). **Not possible**
for FlipFix. It would also mean replacing the user's main-screen launcher.

### 4.4 FlipFix's own navigation bar (OpenCover's way)

FlipFix would draw an accessibility overlay over Samsung's bar, with its own Home going to the face and a curtain
hiding Samsung's home. **It breaks a promise:** following Samsung's bar (when it shows, where, over which app) needs
the window list, and `getWindows()` needs `canRetrieveWindowContent=true`. OpenCover also widens its event scope to
every package for this. It would also replace Samsung's look and need edge strips for gesture Back. **Not
recommended.** CoverScreen OS's "own Back and Home gestures" are the same idea (closed source).

### 4.5 Catching Home as a key event

Accessibility key filtering (`canRequestFilterKeyEvents`) would see every key press, including a hardware keyboard.
It wouldn't see gesture Home at all, and the nav bar's Home may never reach the filter. **Not recommended**:
heavy on privacy, partial at best.

### 4.6 Keeping the face's task above Samsung's home when swiped away

FlipFix would leave its task in place, transparent and untouchable, instead of `moveTaskToBack`, so apps open above
it and Back lands on FlipFix. But Samsung's home would then sit behind a translucent activity: paused and not focused,
so its widgets, Wallet handle and video may misbehave. Home would still start `SubHomeActivity` above it.
Speculative and untested. **Not worth it next to 4.1.**

### 4.7 How Good Lock does it

MultiStar doesn't take over Home. It's Samsung-signed, loads as a SystemUI plugin, and feeds Samsung's own allow-list
and app drawer. Nothing in it is open to a third-party app beyond writing the same secure setting with
`WRITE_SECURE_SETTINGS`. FlipFix doesn't need that: the apps MultiStar adds open from Samsung's drawer, and 4.1
brings the face back after them.

---

## 5. Recommendation

Build **4.1**:

1. In the `isAppScreen` branch, on the unlocked cover, note that a real app came in front since the swipe-away,
   unless it is One UI Home's `RecentsActivity`.
2. Let the next `SubHomeActivity` event launch the face, as `maybeLaunch` already does when nothing was dismissed.
3. Keep "swipe away to see Samsung's widgets" as it is. The face stays away until an app has been opened and left.
4. Consider an on-by-default switch, and a README line about not running two cover launchers.

Test on the Flip6 with the four A cases (Back, Home, recents swipe-away, Close all), from the widget page and from
the drawer, in both button and gesture navigation. Then check A-Back for a widget flash in a Flip8 film (user's go
needed).

It adds no permission, reads no screen content and needs no network, so it fits every FlipFix promise.

---

## Sources

**Samsung**

- Use and customize the Galaxy Z Flip6's FlexWindow (Labs > Apps allowed on cover screen): https://www.samsung.com/us/support/answer/ANS10003463
- Use the Cover screen on your Galaxy Z Flip8 (US): https://www.samsung.com/us/support/answer/ANS10013200/
- Customize the Cover screen on your Galaxy Z Flip8 (US): https://www.samsung.com/us/support/answer/ANS10013199/
- How to use the Cover screen on your Galaxy Z Flip8 (CA, 28 Oct 2026): https://www.samsung.com/ca/support/mobile-devices/use-the-cover-screen-on-your-samsung-galaxy-z-flip8/
- Using apps on the cover screen with Good Lock (JP, 29 Oct 2026; One UI 9 vs earlier): https://www.samsung.com/jp/support/mobile-devices/coverdisplay-goodlock

**Press**

- 9to5Google, Flip 8 cover screen app improvements (22 Jul 2026): https://9to5google.com/2026/07/22/samsung-galaxy-z-flip-8-cover-screen-app-improvements/
- Digital Trends, running multiple apps on the Flip 8's cover screen (22 Jul 2026): https://www.digitaltrends.com/phones/samsung-makes-it-easier-to-run-multiple-apps-on-galaxy-z-flip-8s-cover-screen/
- SamMobile, How One UI 9 transforms the Flip 7 cover screen (13 Sep 2026): https://www.sammobile.com/news/how-one-ui-9-transforms-your-galaxy-z-flip-7-cover-screen/
- SamMobile, One UI 9 on the Flip 8 cover screen: https://www.sammobile.com/news/one-ui-9-amazing-galaxy-z-flip-8-cover-screen/
- SamMobile, MultiStar compatible with One UI 9.0 (11.0.59, 12 Jun 2026): https://www.sammobile.com/news/good-locks-multistar-now-compatible-with-one-ui-9-0/
- SamMobile, three Good Lock apps updated for One UI 9 (MultiStar 11.1.04, 2 Jul 2026): https://www.sammobile.com/news/samsung-updates-three-good-lock-apps-one-ui-9/
- SammyGuru, MultiStar One UI 9 update (no Launcher Widget needed): https://sammyguru.com/samsung-multistar-update-one-ui-9/
- Android Authority, cover screen backlash (3 Nov 2026), Flip 8 cover apps, aspect-ratio button (403 to automated fetches; via search and earlier research): https://www.androidauthority.com/galaxy-z-flip-one-ui-9-cover-screen-backlash-3731117/, https://www.androidauthority.com/galaxy-z-flip-8-cover-screen-apps-3688143/, https://www.androidauthority.com/samsung-galaxy-z-flip-8-cover-screen-aspect-ratio-button-3692241/

**Launchers**

- OpenCover (README; `watcher/CoverAccessibility.kt`, `nav/NavBar.kt` read through the GitHub API): https://github.com/Shmubbier/OpenCover
- CoverScreen OS: https://ijp.app/apps/coverscreen-os/ and https://ijp.app/posts/one-ui-9-cover-screen-problems/
- DroidOS Portals: https://katsuyamaki.github.io/droidos-portals.html
- 9 Flip Launcher (Play page didn't load this time): https://play.google.com/store/apps/details?id=duongth.dev.ninefliplaucher
- Cover Launcher: https://github.com/mazWaz/cover-launcher
- Earlier FlipFix research: `research/one-ui-9-cover-screen-research.md` (sections 2 and 3)

**Device evidence (Flip6, not published):** `dumpsys window`, `dumpsys activity activities`, `logcat`,
`pm query-activities -c android.intent.category.SECONDARY_HOME`, and `framework-res.apk` / `framework.jar` from
`/system/framework`, all read on 2026-11-23.
