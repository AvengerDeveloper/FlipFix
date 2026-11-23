# FlipFix disclaimer

> **FlipFix is experimental test software.** It is new, it was built and tested by one developer on two phones, and it depends on undocumented Samsung behaviour that any update can change. Expect bugs. Install it only if you are comfortable with that, and use it entirely at your own risk.

## Use at your own risk

FlipFix is provided **"as is", without warranty of any kind**, under the [MIT License](LICENSE). By installing it you accept that:

* You use FlipFix, and the steps needed to install it (Developer options, USB or Wireless debugging, the setup page or ADB commands, turning Auto Blocker off), **entirely at your own risk**.
* The author and contributors are **not responsible** for any damage or problem that comes from installing, using or removing FlipFix, including but not limited to: data loss, battery drain or overheating, a cover screen that behaves wrongly or stops responding, missed notifications or alarms, problems with banking or other apps, lost settings, or any warranty, support or account issue with Samsung, Google or your carrier.
* FlipFix is an independent project. It is **not made, endorsed or supported by Samsung**. Don't contact Samsung support about problems FlipFix causes; uninstall it first.

Don't rely on FlipFix for anything important. If a notification, an alarm or the time on your cover screen matters, check it on Samsung's own screens.

## How FlipFix was tested

FlipFix was built and tested over a few days in November 2026. Testing so far:

| What | Covered |
| :--- | :--- |
| **Phones** | One Galaxy Z Flip8 (`SM-F776B`) on One UI 9.0 / Android 17 as the test phone. A Galaxy Z Flip6 (`SM-F741B`, One UI 9.0) was used during early development. |
| **Cover styles** | The nine cover presets in the test phone's cover-screen list, all filmed on 20 November; the two that drew their clock wrongly then were fixed on 22 November. All nine are drawn from their own templates. |
| **Fallback clock** | Tested only on the Z Flip6, with every preset's template left out on purpose: it found the time on 9 of the 11 presets (one date came out as just the month) and showed no clock on the other two (a heavy stencil-style clock and a thin, light one). It hasn't been needed, or tested, on the Z Flip8. |
| **How** | By the developer, by hand: screenshots of Samsung's locked cover compared pixel by pixel with FlipFix's screen after unlocking, filmed unlocks, and the phone's own logs. There are no automated tests. |
| **Settings** | English, 24-hour clock, dark mode, fingerprint unlock, the phone folded. |
| **Widgets** | A few widget layouts with the widgets in left-aligned rows. |
| **Banking apps** | Two banks' apps, which One UI 9 kept off the cover screen. |

## Known limitations

* **Widgets** under the clock are blurred, non-working placeholders. The real widgets are a swipe away.
* **Clock styles FlipFix doesn't recognise** get a plain fallback clock in the same spot, in a standard font rather than Samsung's, and its date can come out shorter. When FlipFix can't clearly find the time, as with analog dials with shaped or illustrated hands, it shows no clock rather than a wrong one. Presets restored from another phone's backup are treated the same way; making the preset again on this phone brings back the exact clock.
* **After switching cover presets**, FlipFix needs one look at the locked cover to recognise the new one. Until then it may show no clock or the previous one.
* **No weather** on the stacked clock: FlipFix has no internet access.
* **Small mismatches**: a clock or date can be a few pixels or a shade off from Samsung's.
* **At the unlock**, the video can pause for a split second, and a frame or two of plain video can show before FlipFix's screen.
* **Right after FlipFix is installed or updated**, the first unlock can briefly show Samsung's widget screen.
* **Samsung updates** can break FlipFix at any time, partly or completely, because it relies on how One UI 9.0 behaves today.

## Not tested

Everything not listed under "How FlipFix was tested" is untested. In particular:

* **Other phones**: Galaxy Z Flip5, Z Flip7 and any other model; regional or carrier firmware other than the test phone's.
* **Other software**: One UI versions other than 9.0, betas, and future Samsung updates.
* **Other settings**: languages other than English, right-to-left languages, 12-hour clock, other digit sets and calendars, light mode, large font or screen zoom, power saving modes, Modes and Routines.
* **Other ways to unlock**: face recognition, PIN, pattern or password, Smart Lock or Extend Unlock, other auto-lock timings.
* **Other apps and features running alongside**: other accessibility services (including TalkBack, magnification and colour correction), Good Lock or other cover-screen apps, Secure Folder, second users or work profiles, Dual Messenger, DeX.
* **Other cover presets and widgets**: clock styles and presets that weren't on the test phone, and widgets in centred or right-aligned rows.
* **Other banking, music and messaging apps** than the ones on the test phone.
* **Long-term use**: weeks of daily use, battery over many days, heat, and behaviour after Samsung moves FlipFix to sleeping apps.

## Battery use

On the test phone, over one day of normal use (17-18 November 2026, about 18 minutes with the cover screen on), Android's battery statistics put FlipFix at **3.84 mAh of the 2,685 mAh** the phone used: about **0.14%**, with about 52 seconds of processor time and no network use. A day with FlipFix's accessibility service off (19 November) showed no measurable difference in the phone's drain with the screen off (about 91 mAh an hour without FlipFix, 88 with it); screen-on use differed too much between the two days to compare.

Keep in mind:

* **This is one day on one phone.** Your use will differ, and so will the cost.
* **The video playing after you unlock is Samsung's own player.** Keeping it playing is the point of FlipFix, and that cost shows under Samsung's system entries, not under FlipFix. It hasn't been measured separately. Expect it to cost more than Samsung's paused widget screen.
* **Some things cost more**: using the cover screen a lot, analog clocks with a second hand (redrawn every second while shown), widgets under the clock (Samsung's live blur), and switching cover presets (FlipFix then takes up to three snapshots of the locked cover and spends a second or two comparing each).
* **"Always On Display and Ambient display"** in Settings → Battery is Samsung's always-on display, not FlipFix.

If you notice more drain with FlipFix installed, turn it off with the Quick Settings tile for a day and compare.

## If something goes wrong

* **Turn FlipFix off**: use its Quick Settings tile, or open the FlipFix app and switch it off. Unfolding the phone also switches its accessibility service off.
* **Uninstall it**: **Settings → Apps → FlipFix → Uninstall**, or `adb uninstall io.github.avengerdeveloper.flipfix` from a computer. Uninstalling removes FlipFix, its permissions and its settings.
* **After installing**, turn **USB debugging** off and **Auto Blocker** back on (**Settings → Security and privacy → Auto Blocker**).

## Reporting problems

Open a GitHub issue with your phone model, One UI version and what happened. If you attach output from the `tools/` scripts, check it first: it can contain app lists, notification text and device names.
