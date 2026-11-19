package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.max

/**
 * Works out which cover preset is active, so FlipFix draws that preset's clock.
 *
 * Samsung keeps one template per preset and nothing an app can read says which preset is in use; switching
 * presets only changes a counter-like setting ([PRESET_SETTING]). So after each switch, FlipFix takes one
 * snapshot of Samsung's locked cover screen, draws what each preset's clock would look like at that minute
 * (offscreen), and keeps the preset whose clock lands on Samsung's pixels. The snapshot stays in memory and
 * is dropped straight after.
 *
 * Templates show each preset as it was made, and Samsung's own rendering moves on (on One UI 9, several
 * presets show bolder digits than their templates have): where and how big comes from the template, the
 * weight, size, offset and colours from the snapshot. A clock in a font FlipFix doesn't know is drawn in
 * the system font at the template's place (CoverClockLayout.variants). If no template's clock lands (a
 * template FlipFix can't read, or none at all), the time is looked for on the snapshot itself and a plain
 * clock is drawn where it is ([FALLBACK]). If that isn't clearly there either (or something covers the
 * clock), the face shows no clock rather than a wrong one, and the next few lock screens are checked
 * again; a weak match is checked again too, and the best fit stays.
 */
object CoverFaceMatcher {

    /** Samsung setting that changes whenever the cover preset is switched (One UI 9). */
    const val PRESET_SETTING = "favorite_list_changed"

    private const val PREFS = "cover_face_match"
    private const val KEY_STAMP = "stamp"
    private const val KEY_FOLDER = "folder"
    private const val KEY_TRIES = "tries"
    private const val KEY_VERSION = "version"
    private const val KEY_COLORS = "colors"
    private const val KEY_COLORS_BRIGHTNESS = "colors_brightness"
    private const val NO_STAMP = "?"

    /** Wakes checked per preset switch when nothing matches, before waiting for the next switch. */
    private const val MAX_TRIES = 5

    /** Without a readable [PRESET_SETTING], re-check at most this often. */
    private const val UNSTAMPED_RECHECK_MS = 10 * 60_000L

    /** Comparison at half the cover's resolution: plenty for clock strokes, a quarter of the work. */
    private const val SCALE = 2

    /** Pixels at least this opaque in FlipFix's drawing count as clock ink. */
    private const val INK_ALPHA = 200

    /**
     * Per-channel colour difference still counted as "the same colour" (lock screen effects, scaling); less
     * for a clock close in colour to its background (half that difference, at least [MIN_TOLERANCE]).
     */
    private const val COLOR_TOLERANCE = 56
    private const val MIN_TOLERANCE = 16

    /** Drawn colours closer than this (per channel) are one colour of the clock (anti-aliasing, scaling). */
    private const val KEY_TOLERANCE = 24

    /** A snapshot with fewer lit pixels than this (per thousand) is blank: the clock alone lights more. */
    private const val MIN_LIT_PER_MILLE = 5

    /** Samsung's tint of a clock colour keeps its lightness to within this (luma, 0..255). */
    private const val MAX_TINT_LUMA = 90

    /** Samsung's colour under a clock colour's ink counts as measured if this share of it is one flat colour. */
    private const val MIN_FLAT_SHARE = 0.2f

    /** Digits whose upper and lower halves Samsung shows this far apart (summed RGB) are shaded, not flat. */
    private const val MIN_SHADE = 24

    /** Background ring around the ink, in comparison pixels. */
    private const val RING = 3

    /**
     * Minimum (ink match - background match) for a preset to count as the active one. Seen on a Flip6:
     * right clocks fit at 0.43 and up; other presets' clocks over styles FlipFix can't draw at 0.30 to 0.38.
     */
    private const val MIN_SCORE = 0.40f

    /** Largest offset tried between the template's clock and Samsung's, in comparison pixels (x2 on the cover). */
    private const val MAX_SHIFT = 20
    private const val SHIFT_STEP = 4

    /** A coarse search's scores look at every this many ink and ring pixels (see [bestFit]). */
    private const val COARSE_STRIDE = 3
    private const val KEY_SHIFT_X = "shift_x"
    private const val KEY_SHIFT_Y = "shift_y"

    /**
     * Clock sizes tried against Samsung's: Samsung's editor lets the user resize a preset's clock, and the
     * template keeps the size it was made at. A walk in [SIZE_STEP]s from the template's size, as long as the
     * clock fits better, then [FINE_SIZE_STEP]s around the best.
     */
    private const val MIN_SIZE = 0.6f
    private const val MAX_SIZE = 1.6f
    private const val SIZE_STEP = 0.05f
    private const val FINE_SIZE_STEP = 0.01f
    private const val FINE_SIZES = 4

    /** A resized clock has to fit this much better than the template's size (noise doesn't resize it). */
    private const val MIN_SIZE_GAIN = 0.02f

    /**
     * Presets (best fitting at the template's size first) whose clock size is fitted: the next one only while
     * none fits at least [SURE_SCORE] (a clock resized a lot fits poorly at the template's size).
     */
    private const val SIZED_CANDIDATES = 2
    private const val SURE_SCORE = 0.5f

    /** Fits this close to the best one are equally good: colour decides. */
    private const val TIE_SCORE = 0.02f

    /** Fits this close to the best one that land on it (see [SAME_PLACE]) are equally good too. */
    private const val NEAR_TIE = 0.06f

    /**
     * A clock colour this saturated (max - min channel, 0..255) was chosen in Samsung's editor: Samsung shows
     * it as it is, where it tints white and grey to suit the wallpaper. Shown within [MAX_HUE_GAP] degrees
     * of its hue and at least a third as saturated, it counts as the same colour.
     */
    private const val CHOSEN_CHROMA = 96

    /** Sizes (against the template's) a fit under [SURE_SCORE] may have. */
    private val WEAK_SIZES = 0.8f..1.25f
    private const val MAX_HUE_GAP = 45f
    private const val MIN_OWN_SHARE = 0.15f

    /** [colourGap] of a colour half that couldn't be measured. */
    private const val UNMEASURED_GAP = 3 * 255

    /**
     * Samsung's preset picker's background (#2D2D2D on One UI 9), the share (%) of the edge strips it fills,
     * and the strips' width (1/[PICKER_EDGE] of the cover's).
     */
    private const val PICKER_GREY = 0xFF2D2D2D.toInt()
    private const val PICKER_TOLERANCE = 3
    private const val PICKER_PERCENT = 98
    private const val PICKER_EDGE = 64

    /** While trying sizes: offsets tried around the last best one (comparison pixels). */
    private const val NEAR_SHIFT = 4
    private const val NEAR_STEP = 2
    private const val KEY_SIZE = "size"
    private const val KEY_PIVOT_X = "pivot_x"
    private const val KEY_PIVOT_Y = "pivot_y"

    /**
     * Digit weights tried against Samsung's, for clocks drawn in a font with weights (CoverClockLayout.digitWeight):
     * a walk in [WEIGHT_STEP]s from the template's weight while the clock fits better, then half a step
     * each side. Kept only if it fits [MIN_SIZE_GAIN] better.
     */
    private const val WEIGHT_STEP = 100
    private const val MIN_WEIGHT = 100
    private const val MAX_WEIGHT = 900
    private const val KEY_WEIGHT = "weight"
    private const val KEY_VARIANT = "variant"

    /**
     * One template's clock in two fonts of a family fits alike, within this, when the time shown has no digit
     * the two draw apart (seen on a Flip6: 0.89 to 0.91 for both, 0.14 apart with a "1" in the time).
     */
    private const val VARIANT_TIE = 0.03f

    /** Per template (folder and date), the font variant that fitted clearly better on a check: "key=variant;...". */
    private const val KEY_FONT_VARIANTS = "font_variants"
    private const val MAX_FONT_VARIANTS = 40

    /** The kept clock's fonts fitted alike and none was told apart before: look again on the next wakes. */
    private const val KEY_FONT_UNDECIDED = "font_undecided"

    /**
     * Besides the best [SIZED_CANDIDATES], clocks drawn in a font with weights are sized and weighed too if
     * they fit within [REFINE_GAP] of the best at the template's weight (a thin template clock that Samsung
     * shows bold fits poorly until it is drawn bold), and clocks in a stand-in font always; at most
     * [REFINED_CANDIDATES] in all.
     */
    private const val REFINE_GAP = 0.25f
    private const val REFINED_CANDIDATES = 5

    /** Contenders whose clocks land this much on one another (intersection over union) are one clock to the eye. */
    private const val SAME_PLACE = 0.8f

    /** A snapshot kept despite doubts (see [Doubt.LookAgain]): look again on the next wake. */
    private const val KEY_LOOK_AGAIN = "look_again"

    /**
     * The kept match's score. One under [SURE_SCORE] is looked at again on the next wakes (up to
     * [MAX_TRIES] per switch), and the best fit since the switch stays: over a moving wallpaper, one frame
     * may hide the clock in a like-coloured background that the next one doesn't (seen on a Flip6: orange
     * digits over an orange cat fitted thin, at 0.41; over the floor, bold, at 0.55).
     */
    private const val KEY_SCORE = "score"

    /**
     * Hours over minutes in a font (CoverClockLayout.hasLines): the matched clock's hours line and date are
     * tried up to [MAX_LINE_STEPS] [LINE_STEP]s (cover pixels) up and down, the date also back at the
     * template's size if the clock was resized.
     */
    private const val LINE_STEP = 4
    private const val MAX_LINE_STEPS = 12

    /** A clock line moved (or resized) has to fit this much better on its own ink ([score] of its parts). */
    private const val MIN_LINE_GAIN = 0.1f
    private const val KEY_HOURS_DY = "hours_dy"
    private const val KEY_DATE_DY = "date_dy"
    private const val KEY_DATE_SIZE = "date_size"

    /**
     * Wallpaper tie-break (see [closestWallpaper]): each preset folder's thumbnail and the snapshot as
     * [WALL_GRID] x [WALL_GRID] average colours, compared as the root mean square of summed RGB differences.
     * A thumbnail is one frame of the preset's video, so a moving video's lock screen may look nothing like
     * it. Seen on a Flip6 (11 presets, 3 sweeps): over still scenes the right thumbnail at 35 to 116, the
     * next closest of all at twice that or more; over moving ones (cats, a lamp) the right one at 123 to
     * 280, some other preset's within 1.25 times of the closest, or closer.
     */
    private const val WALL_GRID = 24
    private const val WALL_THUMB = "thumbnail.jpg"
    private const val WALL_CLOSE = 150f
    private const val WALL_LEAD = 1.5f

    /**
     * The fallback clock, for a lock screen no template's clock fits (a preset whose cover_clock.png FlipFix
     * can't read, or none at all after a Samsung update moves them): the time's digits are looked for on the
     * snapshot itself, in the generic font (CoverClockFonts.genericFont), laid out each way Samsung lays out
     * a cover clock, and a plain clock is drawn where they are, its date where Samsung's is. [KEY_FOLDER]
     * then holds [FALLBACK] and [KEY_FALLBACK] the clock (see [encodeLayout]); a template's match always
     * replaces it, and it never replaces one.
     */
    const val FALLBACK = "~fallback"
    private const val KEY_FALLBACK = "fallback"

    /**
     * Debug builds only: with a file of this name in the app's files folder, templates are left out, so every
     * preset gets the fallback clock (`adb shell run-as <package> touch files/fallback_test`; rm to undo).
     */
    private const val FALLBACK_TEST = "fallback_test"

    /** Text sizes (of the cover's height) the time is looked for at: on one line, hours over minutes, on its side. */
    private val FALLBACK_ONE_LINE = floatArrayOf(0.08f, 0.10f, 0.125f, 0.155f, 0.19f, 0.235f)
    private val FALLBACK_STACKED = floatArrayOf(0.13f, 0.16f, 0.2f, 0.25f, 0.31f, 0.38f)
    private val FALLBACK_SIDEWAYS = floatArrayOf(0.06f, 0.075f, 0.095f, 0.12f, 0.15f)

    /** Hours over minutes: the minutes' baseline this many text sizes below the hours' (then fitted, see relined). */
    private const val FALLBACK_LINE_GAP = 0.9f

    /** Date text sizes (of the cover's height) looked for around the time, each with a short and a long month. */
    private val FALLBACK_DATE_SIZES = floatArrayOf(0.036f, 0.044f, 0.054f, 0.066f, 0.08f)

    /**
     * The time is looked for thin and bold: the quick look ([quickHits]) only finds a clock drawn about as bold
     * as Samsung's (then fitted, see reweighed).
     */
    private val FALLBACK_WEIGHTS = intArrayOf(350, 750)

    /**
     * The quick look ([quickHits]): [QUICK_SAMPLES] ink and ring pixels per place, alike within
     * [QUICK_AGREE] (largest channel difference), places [QUICK_STEP] or more apart; [QUICK_MIN] to count.
     */
    private const val QUICK_SAMPLES = 32
    private const val QUICK_AGREE = 40
    private const val QUICK_STEP = 2
    private const val QUICK_MIN = 0.5f

    /** Places from the quick look scored ([score]), per layout; the best placed exactly: of the time in all, of the date in all. */
    private const val QUICK_PER_SHAPE = 3
    private const val FALLBACK_PLACES = 12
    private const val FALLBACK_DATE_PLACES = 4

    /** Times that fit (the best few) looked at with their dates, see the fallback's choice between them. */
    private const val FALLBACK_TIMES = 4

    /**
     * A fallback clock's time must fit at least this well (a template's clock: [MIN_SCORE]), its date
     * [FALLBACK_DATE_MIN]. A clock drawn in the wrong place is worse than none: over three sweeps of a Flip6's
     * presets, every time found in the right place fitted 0.77 or better, and the shapes found elsewhere (a
     * tree trunk, dark confetti when the real clock's font was too far from the system font, a time a few
     * letters off on a light background) 0.61 at most.
     */
    private const val FALLBACK_MIN = 0.7f
    private const val FALLBACK_DATE_MIN = 0.35f

    /** A long month ("November") is kept over a short one if it fits nearly as well (see the fallback's longer). */
    private const val LONG_DATE_TIE = 0.15f

    /**
     * The date is looked for in about the time's colour (largest channel difference): Samsung draws both in
     * one colour, and darker or lighter strokes nearby aren't the date (seen on a Flip6: a dark frame's edge
     * next to a light clock taken for its date).
     */
    private const val DATE_COLOUR_GAP = 80

    /** A fallback clock this near the middle (of the cover's width) is centred; further left or right, aligned that way. */
    private const val CENTRED = 0.08f

    /** Off the canvas: a fallback layout's date (or time) placed here isn't drawn on the cover. */
    private const val OFF = -1f

    private val observers = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var lastUnstampedCheck = 0L

    fun observe(observer: () -> Unit) = observers.add(observer)
    fun stopObserving(observer: () -> Unit) = observers.remove(observer)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Current value of Samsung's preset-switch setting, or [NO_STAMP] if this firmware doesn't have it. */
    fun presetStamp(context: Context): String =
        runCatching { Settings.System.getString(context.contentResolver, PRESET_SETTING) }.getOrNull() ?: NO_STAMP

    /**
     * Template of the active preset. Before the first check: the newest template (best guess). After a
     * preset switch that hasn't been checked yet, or when nothing matched: null, i.e. no clock.
     */
    fun activeTemplate(context: Context): File? {
        val p = prefs(context)
        val stamp = p.getString(KEY_STAMP, null) ?: return CoverClockLayout.newestTemplate(context)
        if (stamp != presetStamp(context)) return null
        val folder = p.getString(KEY_FOLDER, "").orEmpty()
        if (folder.isEmpty()) return null
        return File(File(CoverClockLayout.TEMPLATE_ROOT, folder), "cover_clock.png").takeIf { it.canRead() }
    }

    /**
     * Folder of the active preset (its video and thumbnail live there too), if known. For a fallback clock
     * ([FALLBACK]), a folder of that name that isn't there.
     */
    fun activeFolder(context: Context): File? =
        activeTemplate(context)?.parentFile ?: activeFallback(context)?.let { File(CoverClockLayout.TEMPLATE_ROOT, FALLBACK) }

    /** The active preset's clock as found on its lock screen ([FALLBACK]), if that's how it was found. */
    fun activeFallback(context: Context): CoverClockLayout? {
        val p = prefs(context)
        if (p.getString(KEY_FOLDER, null) != FALLBACK || p.getString(KEY_STAMP, null) != presetStamp(context)) return null
        return p.getString(KEY_FALLBACK, null)?.let { decodeLayout(it) }
    }

    /** Whether the active preset's clock is known, by its template or as a fallback clock. */
    private fun known(context: Context) = activeTemplate(context) != null || activeFallback(context) != null

    /** Debug builds: whether templates are left out, see [FALLBACK_TEST]. */
    private fun fallbackTest(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && File(context.filesDir, FALLBACK_TEST).exists()

    /** Whether the lock screen should be checked now. */
    fun needsCheck(context: Context): Boolean {
        val p = prefs(context)
        val now = presetStamp(context)
        if (now == NO_STAMP) return SystemClock.uptimeMillis() - lastUnstampedCheck > UNSTAMPED_RECHECK_MS ||
            lastUnstampedCheck == 0L
        val stamp = p.getString(KEY_STAMP, null) ?: return true
        if (stamp != now) return true
        // After an update: look again (it may draw this clock now, or read its place and colours better).
        if (p.getLong(KEY_VERSION, -1) != appVersion(context)) return true
        // The widgets' blurred look is kept in memory only: look again once after a restart, and again
        // while they may still be fading in.
        if (CoverWidgets.row?.stamp != now || CoverWidgets.wantsAnotherLook(now)) return true
        // A match from a doubtful snapshot: confirm it on a steady lock screen.
        if (p.getBoolean(KEY_LOOK_AGAIN, false)) return true
        // Nothing matched, only weakly, or only the fallback clock (a template may fit a steadier lock screen):
        // try a few more wakes.
        val folder = p.getString(KEY_FOLDER, "").orEmpty()
        // So does a clock whose fonts fit alike at the time it was checked (see KEY_FONT_UNDECIDED).
        val weak = folder.isEmpty() || folder == FALLBACK || p.getFloat(KEY_SCORE, SURE_SCORE) < SURE_SCORE ||
            p.getBoolean(KEY_FONT_UNDECIDED, false)
        return weak && p.getInt(KEY_TRIES, 0) < MAX_TRIES
    }

    /**
     * How far to trust a snapshot, judged once it has been compared (see CoverAccessibilityService): fully,
     * not at all ([Discard], e.g. the preset changed meanwhile), or enough to keep a sure match but not a
     * miss ([LookAgain], e.g. the cover unlocked just after it, so the lock screen may have been moving).
     */
    sealed interface Doubt {
        data object None : Doubt
        data class Discard(val why: String) : Doubt
        data class LookAgain(val why: String) : Doubt
    }

    private fun appVersion(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)

    /**
     * Compares [shot] (Samsung's locked cover, taken at [at] while [stamp] was current) with every
     * preset's clock and records the active preset. Runs off the main thread; recycles [shot].
     * [newTry]: the first snapshot of a wake (later snapshots of the same wake count as the same try).
     * Returns whether another snapshot is worth taking (nothing matched, but some preset could have; or
     * no widgets showed yet, see CoverWidgets.wantsAnotherLook). [doubt], asked once the snapshot has been
     * compared and before anything is kept: how far to trust it (see [Doubt]).
     */
    fun match(
        context: Context, shot: Bitmap, at: Date, stamp: String, newTry: Boolean, doubt: () -> Doubt = { Doubt.None },
    ): Boolean {
        val started = SystemClock.uptimeMillis()
        val coverW = shot.width
        val coverH = shot.height
        val w = coverW / SCALE
        val h = coverH / SCALE
        val screen = IntArray(w * h)
        Bitmap.createScaledBitmap(shot, w, h, true).also { it.getPixels(screen, 0, w, 0, 0, w, h) }.recycle()
        shot.recycle()
        // Brightness only (no image is kept): tells a real lock screen from a blacked-out snapshot.
        var lit = 0; var lumaSum = 0L
        for (c in screen) {
            val luma = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
            lumaSum += luma
            if (luma > 16) lit++
        }
        val shotInfo = "snapshot ${coverW}x$coverH, mean brightness ${lumaSum / screen.size}, lit ${lit * 100 / screen.size}%"
        // A black snapshot (the cover went dark as it was taken) shows no clock: matching it would forget
        // the preset already found, and its colours.
        if (lit * 1000L < screen.size * MIN_LIT_PER_MILLE) {
            Log.d(CoverState.TAG, "cover preset check: blank snapshot ($shotInfo), skipped")
            return true
        }
        if (isPresetPicker(screen, w, h)) {
            Log.d(CoverState.TAG, "cover preset check: Samsung's preset picker is open ($shotInfo), skipped")
            return true
        }

        // Every way each preset's clock may be drawn (CoverClockLayout.variants). Presets with the same
        // clock (same style, place, size and colour) draw the same: compare once.
        val testFallback = fallbackTest(context)
        val templates = if (testFallback) emptyList() else CoverClockLayout.templates()
        val byLayout = templates
            .filter { CoverClockLayout.sameShape(it, coverW, coverH) }
            .flatMap { file -> CoverClockLayout.variants(context, file).map { file to it } }
            .groupBy({ it.second }, { it.first })

        val renderer = CoverClockRenderer(context)
        val drawn = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val clock = IntArray(w * h)

        /** [layout] at [at], resized by [size] about ([pivotX], [pivotY]) (cover pixels), as a [Probe]. */
        fun probeOf(layout: CoverClockLayout, size: Float = 1f, pivotX: Float = 0f, pivotY: Float = 0f): Probe? {
            drawn.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(drawn).apply {
                scale(1f / SCALE, 1f / SCALE)
                if (size != 1f) scale(size, size, pivotX, pivotY)
            }
            renderer.draw(canvas, layout, at, coverW.toFloat(), coverH.toFloat())
            drawn.getPixels(clock, 0, w, 0, 0, w, h)
            return probe(clock, w, h)
        }

        /** [c] at the size Samsung shows its clock in, if a size other than the template's fits clearly better. */
        fun resized(c: Candidate): Candidate {
            val p = c.probe ?: return c
            // About the middle of the template's clock (Samsung's own pivot shows up as part of the offset).
            val pivotX = p.box.centerX() * SCALE
            val pivotY = p.box.centerY() * SCALE
            /** At [size], placed near [near], or anywhere in reach if null. */
            fun at(size: Float, near: Fit?): Candidate? {
                val sized = probeOf(c.layout, size, pivotX, pivotY) ?: return null
                val fit = if (near == null) bestFit(sized, screen, w, h) else bestFit(sized, screen, w, h, near.dx, near.dy, NEAR_SHIFT, NEAR_STEP)
                return c.copy(probe = sized, fit = fit, size = size, pivotX = pivotX, pivotY = pivotY)
            }
            // The first [SIZE_STEP] each way placed anew: at the wrong size, the best place may line up only part
            // of the clock (seen on a Flip6: a one-line clock Samsung shows 13% bigger fitted 40 px left on its
            // time, or 26 px right on its date), and the right place for the next size is then out of a near
            // search's reach.
            var best = c
            for (direction in intArrayOf(1, -1)) {
                var step = 1
                while (true) {
                    val size = 1f + direction * step * SIZE_STEP
                    if (size < MIN_SIZE || size > MAX_SIZE) break
                    val next = at(size, if (step == 1) null else best.fit) ?: break
                    if (next.fit.score <= best.fit.score) break
                    best = next
                    step++
                }
                if (best !== c) break
            }
            val coarse = best.size
            for (i in -FINE_SIZES..FINE_SIZES) {
                val size = Math.round((coarse + i * FINE_SIZE_STEP) * 100) / 100f
                if (i == 0 || size == 1f || size < MIN_SIZE || size > MAX_SIZE) continue
                val next = at(size, best.fit) ?: continue
                if (next.fit.score > best.fit.score) best = next
            }
            return if (best.fit.score - c.fit.score >= MIN_SIZE_GAIN) best else c
        }

        /** [c] with its digits at the weight Samsung shows them in, if one other than the template's fits clearly better. */
        fun reweighed(c: Candidate): Candidate {
            val from = c.layout.digitWeight()?.takeIf { weighable(context, c.layout) } ?: return c
            fun at(weight: Int, near: Fit): Candidate? {
                val layout = c.layout.withDigitWeight(weight)
                val p = probeOf(layout, c.size, c.pivotX, c.pivotY) ?: return null
                val fit = bestFit(p, screen, w, h, near.dx, near.dy, NEAR_SHIFT, NEAR_STEP)
                return c.copy(layout = layout, probe = p, fit = fit, weight = weight)
            }
            var best = c
            for (direction in intArrayOf(1, -1)) {
                var weight = from + direction * WEIGHT_STEP
                while (weight in MIN_WEIGHT..MAX_WEIGHT) {
                    val next = at(weight, best.fit) ?: break
                    if (next.fit.score <= best.fit.score) break
                    best = next
                    weight += direction * WEIGHT_STEP
                }
                if (best !== c) break
            }
            val coarse = best.weight ?: from
            for (weight in intArrayOf(coarse - WEIGHT_STEP / 2, coarse + WEIGHT_STEP / 2)) {
                if (weight !in MIN_WEIGHT..MAX_WEIGHT || weight == from) continue
                val next = at(weight, best.fit) ?: continue
                if (next.fit.score > best.fit.score) best = next
            }
            return if (best.fit.score - c.fit.score >= MIN_SIZE_GAIN) best else c
        }

        /**
         * [c] with its hours line and date where Samsung shows them (see [LINE_STEP]), each moved only if that
         * fits its own ink clearly better ([MIN_LINE_GAIN]). Each is a fraction of the clock's ink: wherever it
         * goes, the whole clock fits about as well (seen on a Flip6: 0.80 to 0.83 for a date moved over 96 px),
         * and the whole clock would shift to make up for it. And moved a step, a line fits no better until it
         * lands on Samsung's, so every place in reach is tried, the rest of the clock held where it fits.
         */
        fun relined(c: Candidate, date: Boolean = true): Candidate {
            if (!c.layout.hasLines()) return c
            val (digitsColor, dateColor) = c.layout.inkColors() ?: return c
            val step = LINE_STEP.toFloat() / coverH
            fun render(lines: Lines, near: Fit, reach: Int): Candidate? {
                val layout = c.layout.withLines(lines.hoursDy, lines.dateDy, lines.dateSize)
                val p = probeOf(layout, c.size, c.pivotX, c.pivotY) ?: return null
                val fit = bestFit(p, screen, w, h, near.dx, near.dy, reach, if (reach == 0) 1 else NEAR_STEP)
                return c.copy(layout = layout, probe = p, fit = fit, lines = lines)
            }
            fun partScore(cand: Candidate, parts: (Probe) -> Set<Int>): Float {
                val p = cand.probe ?: return 0f
                return parts(p).takeIf { it.isNotEmpty() }?.let { score(p, screen, w, h, cand.fit.dx, cand.fit.dy, it) } ?: 0f
            }
            fun scan(from: Candidate, sizes: List<Float>, move: (Lines, Int, Float) -> Lines, parts: (Probe) -> Set<Int>): Candidate {
                val lines = from.lines ?: Lines()
                val base = partScore(from, parts)
                var best = from
                var bestScore = base
                var bestAt = 0
                fun at(n: Int, size: Float) {
                    val next = render(move(lines, n, size), from.fit, 0) ?: return
                    val s = partScore(next, parts)
                    if (s > bestScore) { best = next; bestScore = s; bestAt = n }
                }
                // Two steps at a time, then the steps either side of the best.
                for (size in sizes) {
                    for (n in -MAX_LINE_STEPS..MAX_LINE_STEPS step 2) if (n != 0 || size != 1f) at(n, size)
                }
                val size = best.lines?.dateSize ?: 1f
                for (n in intArrayOf(bestAt - 1, bestAt + 1)) if (n in -MAX_LINE_STEPS..MAX_LINE_STEPS) at(n, size)
                return if (bestScore - base >= MIN_LINE_GAIN) best else from
            }
            val hours = { p: Probe -> setOf(2 * p.groupOf(digitsColor)) }
            val dateParts = { p: Probe ->
                p.groupOf(dateColor).let { g -> if (g == p.groupOf(digitsColor)) emptySet() else setOf(2 * g, 2 * g + 1) }
            }
            var best = scan(c, listOf(1f), { l, n, _ -> l.copy(hoursDy = n * step) }, hours)
            // The whole clock placed again with its hours where they are now.
            if (best !== c) render(best.lines ?: Lines(), best.fit, NEAR_SHIFT)?.let { if (it.fit.score >= best.fit.score) best = it }
            if (!date) return best
            return scan(best, listOf(1f, 1f / c.size).distinct(), { l, n, size -> l.copy(dateDy = n * step, dateSize = size) }, dateParts)
        }

        /** [c] at [r]'s size and weight ([r]: refined, the same clock in other colours), if that fits it better. */
        fun likeRefined(c: Candidate, r: Candidate): Candidate {
            if (r.size == 1f && r.weight == null) return c
            val layout = r.weight?.let { c.layout.withDigitWeight(it) } ?: c.layout
            val p = probeOf(layout, r.size, r.pivotX, r.pivotY) ?: return c
            val fit = bestFit(p, screen, w, h, r.fit.dx, r.fit.dy, NEAR_SHIFT, NEAR_STEP)
            if (fit.score <= c.fit.score) return c
            return c.copy(layout = layout, probe = p, fit = fit, size = r.size, pivotX = r.pivotX, pivotY = r.pivotY, weight = r.weight)
        }

        /** Where [c]'s ink lands on the lock screen (comparison pixels). */
        fun onScreen(c: Candidate) = RectF(c.probe!!.box).apply { offset(c.fit.dx.toFloat(), c.fit.dy.toFloat()) }

        /** [c] drawn as [layout] instead (for the record: [parsed]), placed again near where it is. */
        fun relayout(c: Candidate, parsed: CoverClockLayout, layout: CoverClockLayout): Candidate? {
            val p = probeOf(layout, c.size, c.pivotX, c.pivotY) ?: return null
            return c.copy(parsed = parsed, layout = layout, probe = p, fit = bestFit(p, screen, w, h, c.fit.dx, c.fit.dy, NEAR_SHIFT, NEAR_STEP))
        }

        /**
         * Each of [shapes] at the [n] places the quick look finds for it in [window], away from [avoid] (and in
         * about the colour [like], if given), each scored there (in the colour it found, and a pixel either way);
         * the [places] best placed exactly.
         */
        fun placed(shapes: List<CoverClockLayout>, n: Int, places: Int, window: RectF?, avoid: RectF?, like: Int? = null): List<Candidate> {
            class Hit(val shape: CoverClockLayout, val colour: Int, val score: Float, val dx: Int, val dy: Int)
            val hits = ArrayList<Hit>()
            for (shape in shapes) {
                val p = probeOf(shape) ?: continue
                for (q in quickHits(p, screen, w, h, n, window, avoid, like)) {
                    val keyed = Probe(p.ink, p.byGroup, p.ring, p.ringGroup, IntArray(p.keys.size) { q.colour }, p.splitY, p.box, w)
                    var best: Hit? = null
                    for (oy in -1..1) for (ox in -1..1) {
                        val score = score(keyed, screen, w, h, q.dx + ox, q.dy + oy, stride = 2)
                        if (best == null || score > best.score) best = Hit(shape, q.colour, score, q.dx + ox, q.dy + oy)
                    }
                    hits += best!!
                }
            }
            return hits.sortedByDescending { it.score }.take(places).mapNotNull { hit ->
                val layout = moved(hit.shape, hit.dx * SCALE.toFloat() / coverW, hit.dy * SCALE.toFloat() / coverH)
                    .withColors(hit.colour, hit.colour)
                val p = probeOf(layout) ?: return@mapNotNull null
                Candidate(emptyList(), layout, layout, p, bestFit(p, screen, w, h, 0, 0, NEAR_SHIFT, NEAR_STEP))
            }
        }

        var fallbackInfo = ""

        /**
         * The fallback clock (see [FALLBACK]). The time as it is at [at], in the generic font, is looked for all
         * over the lock screen, on one line, hours over minutes and on its side, at a few sizes ([quickHits]);
         * the best places are placed exactly, then sized and weighed as a template's clock is. Then the date is
         * looked for around the time, and the clock lined up as Samsung lines up its own (left, centred or
         * right; on its side, the date before or after the time). Null if the time isn't clearly there
         * ([FALLBACK_MIN]). No date found: the clock has none.
         */
        fun fallback(): Candidate? {
            val font = CoverClockFonts.genericFont(context) ?: return null
            val shapes = ArrayList<CoverClockLayout>()
            for (weight in FALLBACK_WEIGHTS) {
                for (size in FALLBACK_ONE_LINE) shapes += fallbackLine(font, size, 0f, weight)
                for (size in FALLBACK_STACKED) shapes += fallbackLine(font, size, FALLBACK_LINE_GAP, weight)
                for (size in FALLBACK_SIDEWAYS) shapes += fallbackSideways(font, size, weight)
            }
            val times = placed(shapes, QUICK_PER_SHAPE, FALLBACK_PLACES, null, null).sortedByDescending { it.fit.score }
            val first = times.firstOrNull()
            if (first == null) {
                fallbackInfo = " (fallback: no time found)"
                return null
            }
            val refined = times.take(FALLBACK_TIMES).filter { it.fit.score >= first.fit.score - REFINE_GAP }
                .map { reweighed(resized(reweighed(it))) }
            fun kind(l: CoverClockLayout) = when (l) {
                is CoverClockLayout.TwoLine -> if (l.oneLine) "on one line" else "hours over minutes"
                is CoverClockLayout.Sideways -> "on its side"
                else -> "?"
            }
            fun textPx(l: CoverClockLayout, size: Float) = size * coverH * when (l) {
                is CoverClockLayout.TwoLine -> l.textSizeFrac
                is CoverClockLayout.Sideways -> l.timeTextSizeFrac
                else -> 0f
            }
            fun describe(c: Candidate) = "the time ${kind(c.layout)}, ${"%.0f".format(textPx(c.layout, c.size))} px" +
                (c.weight?.let { " at weight $it" } ?: "") + ", fitted ${"%.2f".format(c.fit.score)}"
            // (Its colour was measured where it was found: a time whose shading Samsung varies fits without one.)
            val sure = refined.filter { it.fit.score >= FALLBACK_MIN }
            if (sure.isEmpty()) {
                fallbackInfo = " (fallback: best ${describe(refined.maxBy { it.fit.score })}, not clearly there)"
                return null
            }

            /** [time] lined up as it sits on the cover (it was looked for centred): left, centred or right. */
            fun aligned(time: Candidate): Candidate {
                val centredAs = time.layout as? CoverClockLayout.TwoLine ?: return time
                val box = onScreen(time)
                val centre = box.centerX() / w
                val align = when {
                    centre < 0.5f - CENTRED -> CoverClockLayout.Align.LEFT
                    centre > 0.5f + CENTRED -> CoverClockLayout.Align.RIGHT
                    else -> CoverClockLayout.Align.CENTER
                }
                if (align == centredAs.align) return time
                // Its ink stays put: the anchor moves to the ink's edge (then placed again for the bearings).
                val half = box.width() * SCALE / time.size / coverW / 2
                val l = centredAs.copy(
                    align = align,
                    anchorXFrac = centredAs.anchorXFrac + if (align == CoverClockLayout.Align.LEFT) -half else half,
                )
                return relayout(time, l, l)?.takeIf { it.fit.score >= time.fit.score - TIE_SCORE } ?: time
            }

            /** A time and the date found next to it (if any), and the date with its month written out (if tried). */
            class Dated(val time: Candidate, val date: Candidate?, val long: Candidate?)

            /** [t] with its date: in a band either side of it (across the cover for a clock on its side), in its colour. */
            fun dated(t: Candidate): Dated {
                val tl = t.layout
                val timeBox = onScreen(t)
                val window = if (tl is CoverClockLayout.Sideways) {
                    val m = max(timeBox.width(), w * 0.2f)
                    RectF(timeBox.left - m, 0f, timeBox.right + m, h.toFloat())
                } else {
                    val m = max(timeBox.height(), h * 0.2f)
                    RectF(0f, timeBox.top - m, w.toFloat(), timeBox.bottom + m)
                }
                val dateShapes = FALLBACK_DATE_SIZES.flatMap { size -> listOf(false, true).mapNotNull { long -> fallbackDate(tl, size, long) } }
                val found = placed(dateShapes, QUICK_PER_SHAPE, FALLBACK_DATE_PLACES, window, timeBox, tl.inkColors()?.first)
                    .maxByOrNull { it.fit.score }?.takeIf { it.fit.score >= FALLBACK_DATE_MIN } ?: return Dated(t, null, null)

                /**
                 * [d] with the month written out, starting where [d] starts (its left end; on its side, its
                 * bottom end): a short date fits the start of a long one nearly as well as the long one does
                 * (seen on a Flip6: "Thu 19 Nov" over "Thu 19 November", 0.98).
                 */
                fun longer(d: Candidate): Candidate? {
                    val l = d.layout
                    val long = when (l) {
                        is CoverClockLayout.TwoLine -> l.takeIf { !it.dateLongMonth }?.copy(dateLongMonth = true)
                        is CoverClockLayout.Sideways -> l.takeIf { !it.dateLongMonth }?.copy(dateLongMonth = true)
                        else -> null
                    } ?: return null
                    val short = d.probe?.box ?: return null
                    val drawn = probeOf(long)?.box ?: return null
                    val (ex, ey) = if (l is CoverClockLayout.Sideways) 0f to short.bottom - drawn.bottom else short.left - drawn.left to 0f
                    val placedLong = moved(long, ex * SCALE / coverW, ey * SCALE / coverH)
                    val p = probeOf(placedLong) ?: return null
                    return d.copy(parsed = placedLong, layout = placedLong, probe = p, fit = bestFit(p, screen, w, h, d.fit.dx, d.fit.dy, NEAR_SHIFT, NEAR_STEP))
                }
                val long = longer(found)
                return Dated(t, resized(long?.takeIf { it.fit.score >= found.fit.score - LONG_DATE_TIE } ?: found), long)
            }

            // Of the times that fit, the one with a date beside it in its colour: Samsung shows them together, and
            // a time "found" elsewhere has none (seen on a Flip6: a dark tree trunk fitted a sideways time 0.61).
            val chosen = sure.map { dated(aligned(it)) }.maxBy { it.time.fit.score + (it.date?.fit?.score ?: 0f) }
            val t = chosen.time
            val tl = t.layout
            val timeBox = onScreen(t)
            val date = chosen.date
            val long = chosen.long
            val timeInfo = describe(t) + "; weighed: " + refined.joinToString { c ->
                val box = onScreen(c)
                "${kind(c.layout)} at ${(box.left * SCALE).toInt()},${(box.top * SCALE).toInt()} ${"%.2f".format(c.fit.score)}"
            }
            // Where the date is, in the time's own frame (the face resizes and moves the clock as fitted).
            val s = t.size
            fun ownX(x: Float) = (t.pivotX + (x - t.fit.dx * SCALE - t.pivotX) / s) / coverW
            fun ownY(y: Float) = (t.pivotY + (y - t.fit.dy * SCALE - t.pivotY) / s) / coverH
            val dl = date?.layout
            val composed: CoverClockLayout = when {
                date == null -> tl
                tl is CoverClockLayout.TwoLine && dl is CoverClockLayout.TwoLine -> {
                    val d = onScreen(date)
                    val anchor = SCALE * when (tl.align) {
                        CoverClockLayout.Align.LEFT -> d.left
                        CoverClockLayout.Align.CENTER -> d.centerX()
                        CoverClockLayout.Align.RIGHT -> d.right
                    }
                    // The date's baseline as drawn: resized about its pivot, then moved.
                    val baseline = date.pivotY + (dl.dateBaselineFrac * coverH - date.pivotY) * date.size + date.fit.dy * SCALE
                    tl.copy(
                        dateLongMonth = dl.dateLongMonth, dateAnchorXFrac = ownX(anchor), dateTextSizeFrac = dl.dateTextSizeFrac * date.size / s,
                        dateBaselineFrac = ownY(baseline), dateColor = dl.dateColor,
                    )
                }
                tl is CoverClockLayout.Sideways && dl is CoverClockLayout.Sideways -> {
                    // Reading upwards: a date above the time on the cover comes after it.
                    val d = onScreen(date)
                    val after = d.centerY() < timeBox.centerY()
                    val gap = SCALE * (if (after) timeBox.top - d.bottom else d.top - timeBox.bottom)
                    val baseline = date.pivotX + (dl.dateBaselineFrac * coverW - date.pivotX) * date.size + date.fit.dx * SCALE
                    tl.copy(
                        timeFirst = after,
                        // Not first, the time's end stays put: the anchor moves there.
                        anchorFrac = if (after) tl.anchorFrac else tl.anchorFrac + timeBox.height() * SCALE / s / coverH,
                        gapFrac = gap / s / coverH, dateLongMonth = dl.dateLongMonth,
                        dateTextSizeFrac = dl.dateTextSizeFrac * date.size / s, dateScaleX = 1f, dateBaselineFrac = ownX(baseline),
                        dateColor = dl.dateColor,
                    )
                }
                else -> tl
            }
            val (digits, dateColour) = composed.inkColors() ?: return null
            var clock = relayout(t, composed, composed.withColors(digits, apart(dateColour))) ?: return null
            clock = relined(clock, date = false)
            fun dateKind(l: CoverClockLayout) = when (l) {
                is CoverClockLayout.TwoLine -> l.dateLongMonth to l.dateTextSizeFrac
                is CoverClockLayout.Sideways -> l.dateLongMonth to l.dateTextSizeFrac
                else -> false to 0f
            }
            fallbackInfo = " (fallback: $timeInfo; " +
                (date?.let {
                    val (long, size) = dateKind(it.layout)
                    "the date with the month ${if (long) "long" else "short"}, ${"%.0f".format(size * it.size * coverH)} px, fitted ${"%.2f".format(it.fit.score)}"
                } ?: "no date") + (long?.let { " (the long month fitted ${"%.2f".format(it.fit.score)})" } ?: "") +
                (if (composed is CoverClockLayout.TwoLine) ", ${composed.align.name.lowercase()}" else "") +
                "; the clock fitted ${"%.2f".format(clock.fit.score)})"
            return clock
        }

        // The preset whose wallpaper the lock screen clearly shows, if any: its clocks are refined whatever
        // their first fit (seen on a Flip6: the tree preset's sideways clock fitted 0.46 unrefined, too weak to
        // draw, while the refining went to other presets').
        val wallpaper = closestWallpaper(screen, w, h)
        fun ofWallpaper(c: Candidate) = wallpaper != null && c.files.any { it.parentFile?.name == wallpaper.folder }
        var fittedAt = 0L
        val fits = byLayout.map { (parsed, files) ->
            // The date drawn a shade apart from the digits, so the two are measured separately: Samsung may
            // tint the digits and leave the date white, though the template has both white.
            val layout = parsed.inkColors()?.let { (digits, date) -> parsed.withColors(digits, apart(date)) } ?: parsed
            val probe = probeOf(layout)
            Candidate(files, parsed, layout, probe, probe?.let { bestFit(it, screen, w, h) } ?: Fit(0f, 0, 0))
        }.sortedByDescending { it.fit.score }.toMutableList().also { fittedAt = SystemClock.uptimeMillis() }.also { ranked ->
            val first = ranked.firstOrNull()?.fit?.score ?: 0f
            var refined = 0
            // Clocks equal but for their colours (e.g. one Z Flip6 one-line clock in blue, another in white)
            // fit alike: one is sized and weighed, the rest take its size and weight.
            val done = HashMap<CoverClockLayout, Candidate>()
            for (i in ranked.indices) {
                val c = ranked[i]
                val shape = c.parsed.uncoloured()
                done[shape]?.let { r -> ranked[i] = likeRefined(c, r); continue }
                val onWallpaper = ofWallpaper(c)
                if (refined >= REFINED_CANDIDATES && !onWallpaper) continue
                val sureYet = i > 0 && ranked.take(i).maxOf { it.fit.score } >= SURE_SCORE
                val topFew = i < SIZED_CANDIDATES && !sureYet
                // A stand-in font is fitted to the template's weight, which Samsung may have left far behind: it
                // fits poorly until weighed (seen on a Flip6: 0.3 behind at first, the best fit once weighed).
                val stand = CoverClockFonts.isGeneric(c.layout.digitFont())
                val weighable = (stand || c.fit.score >= first - REFINE_GAP) && weighable(context, c.layout)
                if (!topFew && !weighable && !onWallpaper) continue
                // Weight first (a clock drawn far thinner than Samsung's fits best too small or too big), then
                // size, then the weight again at that size.
                ranked[i] = reweighed(resized(reweighed(c)))
                done[shape] = ranked[i]
                refined++
            }
        }

        // A clock in a colour chosen in Samsung's editor that Samsung doesn't show there is another preset's,
        // however well its shape fits (seen on a Flip6: an orange clock "fitted" over the cyan, grey and
        // brownish vertical clocks FlipFix can't read, at 0.37 to 0.51).
        val (likely, ruledOut) = fits.partition { showsOwnColours(it, screen, w, h) }
        // Presets whose clocks differ only in colour fit equally well (the score allows for Samsung's tint):
        // of those, the one whose own colours are closest to what Samsung shows is the active one (seen: a
        // white clock over a cat matched to the same clock in blue from another preset). A weak fit that
        // another clock equals is a guess between clocks that aren't the one on screen: no match.
        // Weak fits that land on one another draw one clock to the eye (e.g. two presets with the same
        // stacked layout, one of them in the generic font): no guess between different-looking clocks.
        // Clocks that land where the best one does, fitting nearly as well ([NEAR_TIE]), are one clock to the
        // eye too: of those, colour decides (seen on a Flip6: three one-line clocks, one blue, two white,
        // over a blue clock; a white one fitted 0.03 better).
        val top = likely.maxOfOrNull { it.fit.score } ?: 0f
        val leader = likely.maxByOrNull { it.fit.score }
        val contenders = likely.filter {
            it.fit.score >= top - TIE_SCORE || (it.fit.score >= top - NEAR_TIE && onePlace(listOf(leader!!, it)))
        }
        // Of the clocks that fit at all, the preset whose wallpaper clearly looks like the lock screen is the
        // one, even if another clock fits better (seen on a Flip6: the purple preset's white one-line clock
        // and a cat preset's, alike but for their size, fitted 0.45 and 0.47, once 0.45 and 0.52 at 132% size;
        // the tree preset's white sideways clock 0.46, the lamp preset's, thinner at the same place, 0.82).
        val fitting = likely.filter { it.fit.score >= MIN_SCORE }
        val byWallpaper = if (fitting.size > 1) fitting.filter { ofWallpaper(it) }.maxByOrNull { it.fit.score } else null
        val ambiguous = contenders.size > 1 && top < SURE_SCORE && !onePlace(contenders) && byWallpaper == null
        val chosen = byWallpaper ?: if (ambiguous) null else contenders.minWithOrNull(compareBy<Candidate>({ colourGap(it) }, { -it.fit.score }))
        // One template's clock in the fonts of one family (CoverClockLayout.variants) fits alike when the time
        // on screen has no digit they draw apart (seen on a Flip6: the orange cat's clock in ClockModexVF and
        // ClockModexMonoVF fitted 0.90 and 0.89 at 23:33, 0.77 and 0.91 at 23:31, its "1" drawn apart). Then
        // the font that fitted clearly better on an earlier check is kept; never seen apart, the template's
        // own, and the lock screen is looked at again on the next wakes ([KEY_FONT_UNDECIDED]).
        fun variantOf(c: Candidate): Int = c.files.maxByOrNull { it.lastModified() }
            ?.let { CoverClockLayout.variants(context, it).indexOf(c.parsed) } ?: -1
        val siblings = chosen?.let { ch -> likely.filter { it !== ch && it.files.toSet() == ch.files.toSet() } }.orEmpty()
        val tiedSiblings = siblings.filter { it.fit.score >= chosen!!.fit.score - VARIANT_TIE }
        val fontKey = chosen?.files?.maxByOrNull { it.lastModified() }?.let { "${it.parentFile?.name}@${it.lastModified()}" }
        val knownFont = fontKey?.let { knownVariant(context, it) }
        // The font this check tells apart (clearly the best of its template's fonts), to remember.
        val fontSeen = if (chosen != null && siblings.isNotEmpty() && tiedSiblings.isEmpty() && chosen.fit.score >= SURE_SCORE) variantOf(chosen) else null
        var fontUndecided = false
        var fontInfo = ""
        val picked = if (chosen == null || tiedSiblings.isEmpty()) chosen else {
            val tied = listOf(chosen) + tiedSiblings
            tied.firstOrNull { knownFont != null && variantOf(it) == knownFont }
                ?.also { fontInfo = " (its fonts fit alike at this time: ${fontName(it.layout) ?: "the one"} that fitted better before)" }
                ?: tied.minBy { variantOf(it).let { v -> if (v < 0) Int.MAX_VALUE else v } }.also {
                    fontUndecided = true
                    fontInfo = " (its fonts fit alike at this time: the template's, to look again)"
                }
        }
        val refinedAt = SystemClock.uptimeMillis()
        val best = picked?.let { relined(it) }
        val linedAt = SystemClock.uptimeMillis()
        // A weak fit also needs Samsung to show the clock's colour (or a tint of it) somewhere under its ink,
        // and the clock near its template's size (seen on a Flip6: right weak fits at 100 to 108%, a white
        // clock "fitted" at 60% over Samsung's widget-grid layout).
        val weak = best != null && best.fit.score < SURE_SCORE
        val unseen = weak && best?.fit?.shown?.any { it != null } != true
        val oddSize = weak && best?.size?.let { it !in WEAK_SIZES } == true
        val templateFit = best?.takeIf { it.fit.score >= MIN_SCORE && !unseen && !oddSize }
        val matchedFile = templateFit?.files?.maxByOrNull { it.lastModified() }
        val p = prefs(context)
        val sameSwitch = p.getString(KEY_STAMP, null) == stamp
        // A preset found for this switch stays found: a later snapshot that matches nothing (dark, moving,
        // a notification over the clock) says nothing about which preset is active (seen on a Flip6: a sure
        // match erased by a black snapshot as the cover went off). Only another match replaces it.
        val kept = p.getString(KEY_FOLDER, "").orEmpty().takeIf { sameSwitch && it.isNotEmpty() }
        // No template's clock fits: the time looked for on the lock screen itself, unless this switch's preset
        // was found by its template already.
        val fallbackRan = templateFit == null && (kept == null || kept == FALLBACK)
        val fallbackFit = if (fallbackRan) fallback() else null
        drawn.recycle()
        val matchedFit = templateFit ?: fallbackFit
        val matched = matchedFile?.parentFile?.name ?: fallbackFit?.let { FALLBACK }
        val variant = matchedFile?.let { CoverClockLayout.variants(context, it).indexOf(templateFit.parsed) } ?: 0
        val timing = "${SystemClock.uptimeMillis() - started} ms: fitting ${fittedAt - started}, refining ${refinedAt - fittedAt}, " +
            "lines ${linedAt - refinedAt}" + (if (fallbackRan) ", fallback ${SystemClock.uptimeMillis() - linedAt}" else "")
        val version = appVersion(context)
        val sameVersion = p.getLong(KEY_VERSION, -1) == version
        val tries = if (sameSwitch && sameVersion) p.getInt(KEY_TRIES, 0) + (if (newTry) 1 else 0) else 1
        // A snapshot of the previous preset (switched meanwhile) or of a lock screen that may have been moving
        // (the cover unlocked just after it) would overwrite a good match with a miss: keep nothing from it.
        // A sure match from a moving one still holds when nothing was found yet (the shift search finds a
        // clock moved a little); it is checked again on the next wake. It never replaces a match from a
        // steady one (seen on a Flip6: mid-swipe, another stacked clock fitted "surely" and took over).
        val sure = matchedFit != null && matchedFit.fit.score >= SURE_SCORE
        var lookAgain = false
        when (val d = doubt()) {
            Doubt.None -> Unit
            is Doubt.Discard -> {
                Log.d(CoverState.TAG, "cover preset check ($timing, $shotInfo): discarded, ${d.why} (it would have been ${matched ?: "no match"})")
                return false
            }
            is Doubt.LookAgain -> if (sure && kept == null) {
                lookAgain = true
                Log.d(CoverState.TAG, "cover preset check: ${d.why}, but the match is sure: kept, to confirm on the next wake")
            } else {
                Log.d(
                    CoverState.TAG,
                    "cover preset check ($timing, $shotInfo): discarded, ${d.why} (it would have been ${matched ?: "no match"}" +
                        (kept?.let { "; keeping $it" } ?: "") + ")",
                )
                return false
            }
        }
        if (fontSeen != null && fontSeen >= 0 && templateFit === best) rememberVariant(context, fontKey!!, fontSeen)
        // Nor does a weaker fit replace a better one (see [KEY_SCORE]); a match by an earlier version may, a
        // template's match replaces a fallback clock however it scores, and so does the same preset's clock in
        // the font this check tells apart from the one kept.
        val keptScore = if (sameVersion) p.getFloat(KEY_SCORE, SURE_SCORE) else 0f
        val fontChange = fontSeen != null && matched == kept && variant != p.getInt(KEY_VARIANT, 0)
        val weaker = matchedFit != null && matchedFit.fit.score < keptScore && !(kept == FALLBACK && matched != FALLBACK) && !fontChange
        if (kept != null && (matched == null || weaker)) {
            val keptEdit = p.edit().putInt(KEY_TRIES, tries).putLong(KEY_VERSION, version).putFloat(KEY_SCORE, keptScore)
                .putBoolean(KEY_LOOK_AGAIN, false)
            // (The kept clock's font told apart: it is the one, no need to look again.)
            if (fontSeen != null && matched == kept) keptEdit.putBoolean(KEY_FONT_UNDECIDED, false)
            keptEdit.apply()
            CoverWidgets.missedLook(stamp, kept)
            Log.d(
                CoverState.TAG,
                "cover preset check (${SystemClock.uptimeMillis() - started} ms, try $tries, $shotInfo): " +
                    (if (matched == null) "no match this time" else "a weaker fit this time ($matched at ${"%.2f".format(matchedFit!!.fit.score)})") +
                    ", keeping $kept (${"%.2f".format(keptScore)}); scores " +
                    fits.sortedByDescending { it.fit.score }.take(4).joinToString { "${label(it)}=${"%.2f".format(it.fit.score)}" },
            )
            return CoverWidgets.wantsAnotherLook(stamp)
        }
        val edit = p.edit()
            .putString(KEY_STAMP, stamp)
            .putString(KEY_FOLDER, matched.orEmpty())
            .putString(KEY_FALLBACK, fallbackFit?.let { encodeLayout(it.parsed) })
            .putFloat(KEY_SHIFT_X, (matchedFit?.fit?.dx ?: 0).toFloat() / w)
            .putFloat(KEY_SHIFT_Y, (matchedFit?.fit?.dy ?: 0).toFloat() / h)
            .putFloat(KEY_SIZE, matchedFit?.size ?: 1f)
            .putFloat(KEY_PIVOT_X, (matchedFit?.pivotX ?: 0f) / coverW)
            .putFloat(KEY_PIVOT_Y, (matchedFit?.pivotY ?: 0f) / coverH)
            .putInt(KEY_WEIGHT, matchedFit?.weight ?: 0)
            .putFloat(KEY_SCORE, matchedFit?.fit?.score ?: 0f)
            .putFloat(KEY_HOURS_DY, matchedFit?.lines?.hoursDy ?: 0f)
            .putFloat(KEY_DATE_DY, matchedFit?.lines?.dateDy ?: 0f)
            .putFloat(KEY_DATE_SIZE, matchedFit?.lines?.dateSize ?: 1f)
            .putInt(KEY_VARIANT, variant.coerceAtLeast(0))
            .putInt(KEY_TRIES, tries)
            .putLong(KEY_VERSION, version)
            .putBoolean(KEY_LOOK_AGAIN, lookAgain)
            .putBoolean(KEY_FONT_UNDECIDED, fontUndecided && templateFit != null)
        var colorInfo = ""
        val shownColors = matchedFit?.let { clockColors(it, w, h) }
        val brightness = (lumaSum / screen.size).toInt()
        // Where the clock is on Samsung's lock screen: the matched drawing's ink, as placed by the fit.
        val clockOnScreen = matchedFit?.probe?.box?.let { android.graphics.RectF(it).apply { offset(matchedFit.fit.dx.toFloat(), matchedFit.fit.dy.toFloat()) } }
        val widgetInfo = CoverWidgets.look(stamp, matched, screen, w, h, clockOnScreen, matchedFit?.parsed?.alignment())
        // (A fallback clock found again may be another clock: its colours are measured afresh.)
        val samePreset = sameSwitch && p.getString(KEY_FOLDER, null) == matched && matched != FALLBACK
        if (shownColors == null) {
            edit.remove(KEY_COLORS)
        } else if (!samePreset || !sameVersion || brightness >= p.getInt(KEY_COLORS_BRIGHTNESS, -1)) {
            // A lock screen still fading in shows the clock darker: keep the colours of the brightest snapshot
            // (of this preset, measured by this version: an update may measure them better).
            edit.putString(KEY_COLORS, shownColors.encode()).putInt(KEY_COLORS_BRIGHTNESS, brightness)
            colorInfo = ", shown in " + shownColors.describe()
        }
        edit.apply()
        if (stamp == NO_STAMP) lastUnstampedCheck = SystemClock.uptimeMillis()

        Log.d(
            CoverState.TAG,
"cover preset check ($timing, try $tries, $shotInfo, " +
                "${byLayout.size} clocks from ${byLayout.values.flatten().distinct().size} of ${templates.size} templates" +
                (if (testFallback) ", templates left out to test the fallback" else "") + "): " +
                (matched ?: when {
                    ambiguous -> "no match (${contenders.size} clocks fit equally weakly)"
                    unseen -> "no match (the best fit's colours aren't there)"
                    oddSize -> "no match (the best fit is at ${"%.0f".format(best!!.size * 100)}% size)"
                    else -> "no match"
                }) + fallbackInfo +
                (if (byWallpaper != null) " (by its wallpaper, of ${fitting.size} fits: ${"%.0f".format(wallpaper!!.distance)}, the next preset's ${"%.0f".format(wallpaper.next)})"
                else if (!ambiguous && contenders.size > 1) " (closest in colour of ${contenders.size})" else "") +
                (matchedFit?.let { " shifted ${it.fit.dx * SCALE},${it.fit.dy * SCALE} px" } ?: "") +
                (matchedFit?.takeIf { it.size != 1f }?.let { " at ${"%.0f".format(it.size * 100)}% size" } ?: "") +
                (matchedFit?.weight?.let { " at weight $it" } ?: "") +
                (matchedFit?.layout?.let { fontName(it) }?.let { " in $it" } ?: "") +
                (if (templateFit != null) fontInfo else "") +
                (matchedFit?.lines?.let {
                    " with the hours line moved ${"%.0f".format(it.hoursDy * coverH)} px, the date ${"%.0f".format(it.dateDy * coverH)} px" +
                        (if (it.dateSize != 1f) " at ${"%.0f".format(it.dateSize * 100)}%" else "")
                } ?: "") +
                colorInfo + widgetInfo +
                (if (ruledOut.isEmpty()) "" else "; not shown in their colours: " + ruledOut.joinToString { label(it) }) +
                "; scores " + fits.sortedByDescending { it.fit.score }.joinToString { "${label(it)}=${"%.2f".format(it.fit.score)}" },
        )
        observers.forEach { it() }
        return (matched == null && (byLayout.isNotEmpty() || fallbackRan)) || CoverWidgets.wantsAnotherLook(stamp)
    }

    /**
     * Where and how big Samsung's lock screen showed the active preset's clock, against the template:
     * resized by [size] about ([pivotX], [pivotY]), then moved by ([dx], [dy]) (all in fractions of the
     * cover). Samsung doesn't always put the clock exactly where the template has it (e.g. a right-aligned
     * clock sits a little further right), and the user may have resized it in Samsung's editor.
     */
    data class Placement(
        val dx: Float = 0f, val dy: Float = 0f, val size: Float = 1f, val pivotX: Float = 0f, val pivotY: Float = 0f,
    )

    /** The digit weight Samsung's lock screen showed the active preset's clock at, if it differs from the template's. */
    fun activeWeight(context: Context): Int? {
        if (!known(context)) return null
        return prefs(context).getInt(KEY_WEIGHT, 0).takeIf { it > 0 }
    }

    /** Which of the active preset's CoverClockLayout.variants looked like Samsung's lock screen (0: the first). */
    fun activeVariant(context: Context): Int = prefs(context).getInt(KEY_VARIANT, 0)

    /** The font variant remembered for a template ([key]: its folder and date, see [KEY_FONT_VARIANTS]), if any. */
    private fun knownVariant(context: Context, key: String): Int? =
        prefs(context).getString(KEY_FONT_VARIANTS, null)?.split(';')
            ?.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.toIntOrNull()

    private fun rememberVariant(context: Context, key: String, variant: Int) {
        val p = prefs(context)
        val others = p.getString(KEY_FONT_VARIANTS, null)?.split(';')
            ?.filter { it.isNotEmpty() && !it.startsWith("$key=") }.orEmpty()
        p.edit().putString(KEY_FONT_VARIANTS, (others.takeLast(MAX_FONT_VARIANTS - 1) + "$key=$variant").joinToString(";")).apply()
    }

    fun activePlacement(context: Context): Placement {
        if (!known(context)) return Placement()
        val p = prefs(context)
        return Placement(
            p.getFloat(KEY_SHIFT_X, 0f), p.getFloat(KEY_SHIFT_Y, 0f),
            p.getFloat(KEY_SIZE, 1f), p.getFloat(KEY_PIVOT_X, 0f), p.getFloat(KEY_PIVOT_Y, 0f),
        )
    }

    /**
     * A clock's colours as Samsung's lock screen shows them: the digits ([digits] at the top, shaded down to
     * [shade]'s colour if Samsung shades them) and the date.
     */
    class ClockColors(val digits: Int, val date: Int, val shade: CoverClockLayout.Shade? = null) {
        fun encode(): String =
            "$digits,$date" + (shade?.let { ",${it.bottomColor},${it.topFrac},${it.bottomFrac}" } ?: "")

        fun describe(): String = "#%06X".format(digits and 0xFFFFFF) +
            (shade?.let { " to #%06X".format(it.bottomColor and 0xFFFFFF) } ?: "") +
            " / #%06X".format(date and 0xFFFFFF)

        companion object {
            fun decode(s: String): ClockColors? {
                val parts = s.split(',')
                val digits = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val date = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val bottom = parts.getOrNull(2)?.toIntOrNull()
                val top = parts.getOrNull(3)?.toFloatOrNull()
                val bottomFrac = parts.getOrNull(4)?.toFloatOrNull()
                val shade = if (bottom != null && top != null && bottomFrac != null) {
                    CoverClockLayout.Shade(bottom, top, bottomFrac)
                } else {
                    null
                }
                return ClockColors(digits, date, shade)
            }
        }
    }

    /**
     * The digits' and the date's colours as Samsung shows them, from the matched candidate [c] (drawn
     * [w] x [h]), or null for a clock FlipFix doesn't recolour. A colour that wasn't measured (no flat
     * colour there) stays the template's own.
     */
    private fun clockColors(c: Candidate, w: Int, h: Int): ClockColors? {
        val (digits, date) = c.parsed.inkColors() ?: return null
        val (digitsDrawn, dateDrawn) = c.layout.inkColors() ?: return null
        val shown = c.fit.shown ?: return null
        val probe = c.probe ?: return null
        fun whole(g: Int, own: Int): Int {
            val upper = shown[2 * g]; val lower = shown[2 * g + 1]
            return when {
                upper != null && lower != null -> mix(upper, lower, 0.5f)
                else -> upper ?: lower ?: own
            }
        }
        val dateShown = whole(probe.groupOf(dateDrawn), date)
        val g = probe.groupOf(digitsDrawn)
        val upper = shown[2 * g]; val lower = shown[2 * g + 1]
        if (upper == null || lower == null || distance(upper, lower) < MIN_SHADE) {
            return ClockColors(whole(g, digits), dateShown)
        }
        // Shaded: each half's colour sits at its rows' middle; carry the shading on to the ink's top and bottom.
        val rows = probe.byGroup[g].map { probe.ink[it] / w }
        val split = probe.splitY[g]
        val upperY = rows.filter { it <= split }.average().toFloat()
        val lowerY = rows.filter { it > split }.average().toFloat()
        val top = rows.min().toFloat()
        val bottom = rows.max().toFloat() + 1
        if (lowerY - upperY < 1f) return ClockColors(whole(g, digits), dateShown)
        val topColor = mix(upper, lower, -(upperY - top) / (lowerY - upperY))
        val bottomColor = mix(upper, lower, 1f + (bottom - lowerY) / (lowerY - upperY))
        // The face resizes the whole drawing, shading included: give the rows as drawn at the template's size.
        val pivotY = c.pivotY / SCALE
        fun unsized(row: Float) = pivotY + (row - pivotY) / c.size
        return ClockColors(topColor, dateShown, CoverClockLayout.Shade(bottomColor, unsized(top) / h, unsized(bottom) / h))
    }

    /** [a] moved [t] of the way to [b] (t outside 0..1 carries on past them), each channel kept in range. */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /**
     * The active preset's clock colours as Samsung's lock screen showed them, if known: Samsung tints the
     * clock to suit the wallpaper, and the template only has the untinted colour.
     */
    fun activeColors(context: Context): ClockColors? {
        if (!known(context)) return null
        return prefs(context).getString(KEY_COLORS, null)?.let { ClockColors.decode(it) }
    }

    /**
     * A drawn clock's ink pixels and the background ring around them. The clock's colours ([keys]: e.g. the
     * digits' and the date's) number the groups: [byGroup] lists each group's ink pixels (indices into
     * [ink]), [ringGroup] the colour each ring pixel should not have. [splitY]: per group, the last row of
     * its upper half (Samsung may shade a colour from top to bottom, so each half is measured on its own).
     * [box]: the ink's bounding box (right and bottom exclusive).
     */
    private class Probe(
        val ink: IntArray, val byGroup: Array<IntArray>, val ring: IntArray, val ringGroup: IntArray, val keys: IntArray,
        val splitY: IntArray, val box: android.graphics.RectF, w: Int,
    ) {
        /** Index into [shownColors]'s result for [group] at [row]. */
        fun part(group: Int, row: Int) = 2 * group + (if (row > splitY[group]) 1 else 0)

        /** Per [part], its ink pixels (indices into [ink]); and each ring pixel's [part]. Worked out once: a check scores thousands of times. */
        val byPart: Array<IntArray> = Array(keys.size * 2) { part ->
            byGroup[part / 2].filter { part(part / 2, ink[it] / w) == part }.toIntArray()
        }
        val ringPart = IntArray(ring.size) { part(ringGroup[it], ring[it] / w) }

        /** The colour group of a colour the clock was drawn in. */
        fun groupOf(drawnColor: Int): Int = keys.indices.minByOrNull { distance(keys[it], drawnColor or 0xFF000000.toInt()) } ?: 0
    }

    /** [shown]: per colour group's upper and lower half, the colour Samsung's lock screen shows it in (see [shownColors]). */
    private class Fit(val score: Float, val dx: Int, val dy: Int, val shown: Array<Int?>? = null)

    /**
     * A preset ([files] share one clock, [parsed]) as compared: drawn as [layout], resized by [size] about
     * ([pivotX], [pivotY]) (cover pixels), giving [probe], whose best placement on the lock screen is [fit].
     */
    private data class Candidate(
        val files: List<File>, val parsed: CoverClockLayout, val layout: CoverClockLayout, val probe: Probe?,
        val fit: Fit, val size: Float = 1f, val pivotX: Float = 0f, val pivotY: Float = 0f, val weight: Int? = null,
        val lines: Lines? = null,
    )

    /**
     * How Samsung's lock screen spaces a matched clock's lines against its template (see
     * CoverClockLayout.withLines): hours line and date moved down by [hoursDy] and [dateDy] (fractions of
     * the cover's height), the date resized by [dateSize].
     */
    data class Lines(val hoursDy: Float = 0f, val dateDy: Float = 0f, val dateSize: Float = 1f)

    /** How the active preset's clock lines are spaced on Samsung's lock screen, if not as in the template. */
    fun activeLines(context: Context): Lines? {
        if (!known(context)) return null
        val p = prefs(context)
        return Lines(p.getFloat(KEY_HOURS_DY, 0f), p.getFloat(KEY_DATE_DY, 0f), p.getFloat(KEY_DATE_SIZE, 1f))
            .takeIf { it != Lines() }
    }

    /** The preset [folder] whose wallpaper looks like the lock screen, [distance] from it, the next preset's [next]. */
    private class Wallpaper(val folder: String, val distance: Float, val next: Float)

    /**
     * The preset whose wallpaper ([WALL_THUMB]) looks clearly the most like Samsung's lock screen ([screen],
     * [w] x [h]) of every preset's on the phone: within [WALL_CLOSE] of it, and nearer by [WALL_LEAD] times
     * than any other preset's. Null if none stands out (a moving video, presets sharing a wallpaper, or a
     * preset without a thumbnail).
     */
    private fun closestWallpaper(screen: IntArray, w: Int, h: Int): Wallpaper? {
        val seen = grid(screen, w, h)
        val all = File(CoverClockLayout.TEMPLATE_ROOT).listFiles().orEmpty()
            .mapNotNull { folder -> wallpaperGrid(folder)?.let { folder.name to wallDistance(seen, it) } }
            .sortedBy { it.second }
        if (all.size < 2) return null
        val (folder, d) = all[0]
        val next = all[1].second
        return Wallpaper(folder, d, next).takeIf { d <= WALL_CLOSE && next >= d * WALL_LEAD }
    }

    /** [WALL_GRID] x [WALL_GRID] average colours (r, g, b) of a [w] x [h] picture. */
    private fun grid(px: IntArray, w: Int, h: Int): FloatArray {
        val sums = FloatArray(WALL_GRID * WALL_GRID * 3)
        val counts = IntArray(WALL_GRID * WALL_GRID)
        for (y in 0 until h) for (x in 0 until w) {
            val cell = (y * WALL_GRID / h) * WALL_GRID + x * WALL_GRID / w
            val c = px[y * w + x]
            sums[cell * 3] += Color.red(c).toFloat(); sums[cell * 3 + 1] += Color.green(c).toFloat(); sums[cell * 3 + 2] += Color.blue(c).toFloat()
            counts[cell]++
        }
        for (i in sums.indices) sums[i] /= maxOf(1, counts[i / 3])
        return sums
    }

    /** Root mean square, over the cells, of the summed channel differences between two [grid]s. */
    private fun wallDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0.0
        for (cell in 0 until a.size / 3) {
            val d = abs(a[cell * 3] - b[cell * 3]) + abs(a[cell * 3 + 1] - b[cell * 3 + 1]) + abs(a[cell * 3 + 2] - b[cell * 3 + 2])
            sum += d * d
        }
        return kotlin.math.sqrt(sum / (a.size / 3)).toFloat()
    }

    /** [grid] of [folder]'s wallpaper thumbnail, read small (cached per file and its date), or null if it has none. */
    private fun wallpaperGrid(folder: File?): FloatArray? {
        val file = File(folder ?: return null, WALL_THUMB)
        val key = "${file.path}@${file.lastModified()}"
        wallGrids[key]?.let { return it.takeIf { g -> g.isNotEmpty() } }
        val grid = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= WALL_GRID * 4 && bounds.outHeight / (sample * 2) >= WALL_GRID * 4) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            grid(px, bmp.width, bmp.height).also { bmp.recycle() }
        }.getOrNull()
        wallGrids[key] = grid ?: FloatArray(0)
        return grid
    }

    private val wallGrids = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()

    /**
     * The fallback's time on one line ([lineGap] 0) or hours over minutes (the minutes' baseline [lineGap] text
     * sizes below the hours'), [size] of the cover's height, centred on the cover; no date ([OFF]).
     */
    private fun fallbackLine(font: String, size: Float, lineGap: Float, weight: Int): CoverClockLayout {
        // Digits stand about 0.7 of the text size above their baseline: the lines' middle at the cover's.
        val hours = 0.5f + size * (0.35f - lineGap / 2)
        return CoverClockLayout.TwoLine(
            font, weight, CoverClockLayout.Align.CENTER, 0.5f, size, hours, hours + size * lineGap, Color.WHITE,
            dateLongMonth = false, dateAnchorXFrac = 0.5f, dateTextSizeFrac = FALLBACK_DATE_SIZES[0], dateBaselineFrac = OFF,
            dateColor = Color.WHITE, oneLine = lineGap == 0f,
        )
    }

    /** The fallback's time on its side, reading upwards, [size] of the cover's height, about its middle; no date. */
    private fun fallbackSideways(font: String, size: Float, weight: Int): CoverClockLayout = CoverClockLayout.Sideways(
        font, weight, size, 0.5f + size * 0.35f, timeFirst = true, anchorFrac = 0.5f - size * 1.3f, gapFrac = 0f,
        dateLongMonth = false, dateTextSizeFrac = FALLBACK_DATE_SIZES[0], dateScaleX = 1f, dateBaselineFrac = OFF,
        color = Color.WHITE, dateColor = Color.WHITE,
    )

    /**
     * Just the date ([size] of the cover's height, the month [long] or short), about the middle of the cover,
     * as [like] (from [fallbackLine] or [fallbackSideways]) draws it; its time [OFF].
     */
    private fun fallbackDate(like: CoverClockLayout, size: Float, long: Boolean): CoverClockLayout? = when (like) {
        is CoverClockLayout.TwoLine -> like.copy(
            align = CoverClockLayout.Align.CENTER, hoursBaselineFrac = OFF, minutesBaselineFrac = OFF, dateLongMonth = long,
            dateAnchorXFrac = 0.5f, dateTextSizeFrac = size, dateBaselineFrac = 0.5f + size * 0.35f,
        )
        // The time drawn tiny (and off the cover), so the date starts at the anchor: about its middle there.
        is CoverClockLayout.Sideways -> like.copy(
            timeTextSizeFrac = 0.001f, timeBaselineFrac = OFF, timeFirst = true,
            anchorFrac = 0.5f - size * (if (long) 4.5f else 3.5f), gapFrac = 0f, dateLongMonth = long,
            dateTextSizeFrac = size, dateScaleX = 1f, dateBaselineFrac = 0.5f + size * 0.35f,
        )
        else -> null
    }

    /** [l] moved [dx] across (a fraction of the cover's width) and [dy] down (of its height); what's [OFF] stays off. */
    private fun moved(l: CoverClockLayout, dx: Float, dy: Float): CoverClockLayout {
        fun across(v: Float) = if (v == OFF) OFF else v + dx
        fun down(v: Float) = if (v == OFF) OFF else v + dy
        return when (l) {
            is CoverClockLayout.TwoLine -> l.copy(
                anchorXFrac = l.anchorXFrac + dx, hoursBaselineFrac = down(l.hoursBaselineFrac),
                minutesBaselineFrac = down(l.minutesBaselineFrac), dateAnchorXFrac = l.dateAnchorXFrac + dx,
                dateBaselineFrac = down(l.dateBaselineFrac),
            )
            // On its side, the baselines run down the cover, and the line runs up it.
            is CoverClockLayout.Sideways -> l.copy(
                timeBaselineFrac = across(l.timeBaselineFrac), dateBaselineFrac = across(l.dateBaselineFrac), anchorFrac = l.anchorFrac - dy,
            )
            else -> l
        }
    }

    /** A fallback clock ([l]: as [fallbackLine] or [fallbackSideways] make them) as text, for [KEY_FALLBACK]. */
    private fun encodeLayout(l: CoverClockLayout): String? = when (l) {
        is CoverClockLayout.TwoLine -> listOf(
            "T", l.font, l.weight, l.align, l.anchorXFrac, l.textSizeFrac, l.hoursBaselineFrac, l.minutesBaselineFrac,
            l.color, l.dateLongMonth, l.dateAnchorXFrac, l.dateTextSizeFrac, l.dateBaselineFrac, l.dateColor, l.oneLine,
        ).joinToString("|")
        is CoverClockLayout.Sideways -> listOf(
            "S", l.timeFont, l.timeWeight, l.timeTextSizeFrac, l.timeBaselineFrac, l.timeFirst, l.anchorFrac, l.gapFrac,
            l.dateLongMonth, l.dateTextSizeFrac, l.dateScaleX, l.dateBaselineFrac, l.color, l.dateColor,
        ).joinToString("|")
        else -> null
    }

    /** [encodeLayout]'s text as a clock again, or null. */
    private fun decodeLayout(s: String): CoverClockLayout? = runCatching {
        val f = s.split('|')
        when (f[0]) {
            "T" -> CoverClockLayout.TwoLine(
                f[1], f[2].toInt(), CoverClockLayout.Align.valueOf(f[3]), f[4].toFloat(), f[5].toFloat(), f[6].toFloat(),
                f[7].toFloat(), f[8].toInt(), f[9].toBooleanStrict(), f[10].toFloat(), f[11].toFloat(), f[12].toFloat(),
                f[13].toInt(), oneLine = f[14].toBooleanStrict(),
            )
            "S" -> CoverClockLayout.Sideways(
                f[1], f[2].toInt(), f[3].toFloat(), f[4].toFloat(), f[5].toBooleanStrict(), f[6].toFloat(), f[7].toFloat(),
                f[8].toBooleanStrict(), f[9].toFloat(), f[10].toFloat(), f[11].toFloat(), f[12].toInt(), f[13].toInt(),
            )
            else -> null
        }
    }.getOrNull()

    /**
     * [p]'s ink pixels along the middle of its strokes, and its ring pixels not next to its ink ([w]: the
     * comparison width): Samsung's strokes, thinner or bolder than the drawn ones, still cover the first and
     * miss the second (seen on a Flip6: thin clocks missed by the quick look when sampled anywhere on the ink).
     */
    private fun strokeMiddles(p: Probe, w: Int): Pair<IntArray, IntArray> {
        val x0 = p.box.left.toInt(); val y0 = p.box.top.toInt()
        val bw = p.box.width().toInt(); val bh = p.box.height().toInt()
        // City-block distance from each ink pixel to the nearest pixel that isn't ink.
        val d = IntArray(bw * bh)
        for (i in p.ink) d[(i / w - y0) * bw + i % w - x0] = Int.MAX_VALUE / 2
        for (y in 0 until bh) for (x in 0 until bw) {
            val at = y * bw + x
            if (d[at] == 0) continue
            d[at] = minOf(d[at], (if (y > 0) d[at - bw] else 0) + 1, (if (x > 0) d[at - 1] else 0) + 1)
        }
        var most = 0
        for (y in bh - 1 downTo 0) for (x in bw - 1 downTo 0) {
            val at = y * bw + x
            if (d[at] == 0) continue
            d[at] = minOf(d[at], (if (y < bh - 1) d[at + bw] else 0) + 1, (if (x < bw - 1) d[at + 1] else 0) + 1)
            most = max(most, d[at])
        }
        val least = if (most >= 2) max(2, kotlin.math.ceil(most * 0.6f).toInt()) else 1
        val core = p.ink.filter { d[(it / w - y0) * bw + it % w - x0] >= least }.toIntArray()
        fun inkAt(x: Int, y: Int) = x - x0 in 0 until bw && y - y0 in 0 until bh && d[(y - y0) * bw + x - x0] > 0
        val far = p.ring.filter { r ->
            val x = r % w; val y = r / w
            (-1..1).none { dy -> (-1..1).any { dx -> inkAt(x + dx, y + dy) } }
        }.toIntArray()
        return (core.takeIf { it.isNotEmpty() } ?: p.ink) to (far.takeIf { it.isNotEmpty() } ?: p.ring)
    }

    /** A place the quick look found: the drawn clock moved by ([dx], [dy]) comparison pixels, its ink there [colour]. */
    private class Quick(val pre: Float, val dx: Int, val dy: Int, val colour: Int)

    /**
     * A quick look for [p]'s clock all over [screen] ([w] x [h]), or within [window]: at every [QUICK_STEP]th
     * place or so, of [QUICK_SAMPLES] of its ink pixels and as many of its ring's, the share of the ink in one
     * colour (as a clock is drawn) less the share of the ring in that colour too (over plain sky, both are).
     * Places where it would cover [avoid], or whose colour is far from [like] (if given), are left out. The
     * [n] best places, at least half a clock's height apart, best first, scoring [QUICK_MIN] or more.
     */
    private fun quickHits(
        p: Probe, screen: IntArray, w: Int, h: Int, n: Int, window: RectF?, avoid: RectF?, like: Int? = null,
    ): List<Quick> {
        val (core, far) = strokeMiddles(p, w)
        val k = QUICK_SAMPLES
        val inkX = IntArray(k); val inkY = IntArray(k)
        for (i in 0 until k) {
            val at = core[(i.toLong() * core.size / k).toInt()]
            inkX[i] = at % w; inkY[i] = at / w
        }
        val kr = minOf(k, far.size)
        if (kr == 0) return emptyList()
        val ringX = IntArray(kr); val ringY = IntArray(kr)
        for (i in 0 until kr) {
            val at = far[(i.toLong() * far.size / kr).toInt()]
            ringX[i] = at % w; ringY[i] = at / w
        }
        val box = p.box
        val x0 = kotlin.math.ceil(max(0f, window?.left ?: 0f) - box.left).toInt()
        val x1 = kotlin.math.floor(minOf(w.toFloat(), window?.right ?: w.toFloat()) - box.right).toInt()
        val y0 = kotlin.math.ceil(max(0f, window?.top ?: 0f) - box.top).toInt()
        val y1 = kotlin.math.floor(minOf(h.toFloat(), window?.bottom ?: h.toFloat()) - box.bottom).toInt()
        if (x1 < x0 || y1 < y0) return emptyList()
        // Thick strokes are found at a coarser step.
        val step = (minOf(box.width(), box.height()) / 12).toInt().coerceIn(QUICK_STEP, 2 * QUICK_STEP)
        val seen = IntArray(k)
        val bins = IntArray(64)
        val binOf = IntArray(k)
        val found = ArrayList<Quick>()
        for (dy in y0..y1 step step) for (dx in x0..x1 step step) {
            if (avoid != null) {
                val ox = minOf(box.right + dx, avoid.right) - max(box.left + dx, avoid.left)
                val oy = minOf(box.bottom + dy, avoid.bottom) - max(box.top + dy, avoid.top)
                if (ox > 0 && oy > 0 && ox * oy > 0.1f * box.width() * box.height()) continue
            }
            // The most common colour (4 levels a channel) under the samples, then the mean of those near it.
            var top = 0
            for (i in 0 until k) {
                val c = screen[(inkY[i] + dy) * w + inkX[i] + dx]
                seen[i] = c
                val bin = (Color.red(c) shr 6 shl 4) or (Color.green(c) shr 6 shl 2) or (Color.blue(c) shr 6)
                binOf[i] = bin
                if (++bins[bin] > bins[top]) top = bin
            }
            var r = 0; var g = 0; var b = 0; var m = 0
            for (i in 0 until k) if (binOf[i] == top) { r += Color.red(seen[i]); g += Color.green(seen[i]); b += Color.blue(seen[i]); m++ }
            for (i in 0 until k) bins[binOf[i]] = 0
            val mode = Color.rgb(r / m, g / m, b / m)
            var agree = 0; r = 0; g = 0; b = 0
            for (i in 0 until k) {
                if (maxDifference(seen[i], mode) > QUICK_AGREE) continue
                agree++; r += Color.red(seen[i]); g += Color.green(seen[i]); b += Color.blue(seen[i])
            }
            if (agree < k * QUICK_MIN) continue
            val colour = Color.rgb(r / agree, g / agree, b / agree)
            if (like != null && maxDifference(colour, like) > DATE_COLOUR_GAP) continue
            var ringAgree = 0; var ringN = 0
            for (i in 0 until kr) {
                val x = ringX[i] + dx; val y = ringY[i] + dy
                if (x !in 0 until w || y !in 0 until h) continue
                ringN++
                if (maxDifference(screen[y * w + x], colour) <= QUICK_AGREE) ringAgree++
            }
            val pre = agree.toFloat() / k - (if (ringN == 0) 1f else ringAgree.toFloat() / ringN)
            if (pre >= QUICK_MIN) found += Quick(pre, dx, dy, colour)
        }
        val apart = max(4f, minOf(box.width(), box.height()) / 2)
        val picked = ArrayList<Quick>()
        for (q in found.sortedByDescending { it.pre }) {
            if (picked.none { abs(it.dx - q.dx) < apart && abs(it.dy - q.dy) < apart }) picked += q
            if (picked.size == n) break
        }
        return picked
    }

    /** Whether [cs]' clocks, as placed by their fits, all land on the first one's (see [SAME_PLACE]). */
    private fun onePlace(cs: List<Candidate>): Boolean {
        fun box(c: Candidate) = c.probe?.box?.let { android.graphics.RectF(it).apply { offset(c.fit.dx.toFloat(), c.fit.dy.toFloat()) } }
        val first = box(cs.first()) ?: return false
        return cs.drop(1).all { c ->
            val b = box(c) ?: return false
            val i = android.graphics.RectF()
            if (!i.setIntersect(first, b)) return false
            val inter = i.width() * i.height()
            inter / (first.width() * first.height() + b.width() * b.height() - inter) >= SAME_PLACE
        }
    }

    /** [c]'s presets for the log, with the font its digits are drawn in, if any ("158091359086096[Capriola-Regular]"). */
    private fun label(c: Candidate): String =
        c.files.joinToString("+") { it.parentFile?.name.orEmpty() } +
            (fontName(c.parsed)?.let { "[${it.substringBeforeLast('.')}]" } ?: "")

    /** The font file a clock's digits are drawn in (for the log), if it has one. */
    private fun fontName(l: CoverClockLayout): String? = l.digitFont()?.substringAfterLast('/')

    /** Whether [l]'s digits are drawn in a font that comes in weights (so [match] fits the weight). */
    private fun weighable(context: Context, l: CoverClockLayout): Boolean {
        if (l.digitWeight() == null) return false
        return CoverClockFonts.hasWeights(context, l.digitFont() ?: return false)
    }

    private fun probe(clock: IntArray, w: Int, h: Int): Probe? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(clock[y * w + x]) >= INK_ALPHA) {
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return null
        val ink = ArrayList<Int>(); val inkColor = ArrayList<Int>()
        val ring = ArrayList<Int>(); val ringColor = ArrayList<Int>()
        for (y in max(0, y0 - RING)..minOf(h - 1, y1 + RING)) for (x in max(0, x0 - RING)..minOf(w - 1, x1 + RING)) {
            val c = clock[y * w + x]
            val a = Color.alpha(c)
            if (a >= INK_ALPHA) {
                ink += y * w + x; inkColor += c
            } else if (a == 0 && inkWithin(clock, w, h, x, y)) {
                ring += y * w + x; ringColor += nearestInkColor(clock, w, h, x, y)
            }
        }
        if (ink.size < 20) return null
        val keys = ArrayList<Int>()
        fun group(c: Int): Int {
            keys.indexOfFirst { same(it, c, KEY_TOLERANCE) }.let { if (it >= 0) return it }
            keys += c or 0xFF000000.toInt()
            return keys.size - 1
        }
        val inkGroup = IntArray(ink.size) { group(inkColor[it]) }
        val ringGroup = IntArray(ring.size) { i ->
            keys.indices.minByOrNull { distance(keys[it], ringColor[i]) } ?: 0
        }
        val byGroup = Array(keys.size) { g -> ink.indices.filter { inkGroup[it] == g }.toIntArray() }
        val splitY = IntArray(keys.size) { g ->
            val rows = byGroup[g].map { ink[it] / w }.sorted()
            rows.getOrElse(rows.size / 2) { 0 }
        }
        return Probe(
            ink.toIntArray(), byGroup, ring.toIntArray(), ringGroup, keys.toIntArray(), splitY,
            android.graphics.RectF(x0.toFloat(), y0.toFloat(), x1 + 1f, y1 + 1f), w,
        )
    }

    /**
     * The colour Samsung's lock screen shows each of the clock's colours in, with the drawn clock moved by
     * ([dx], [dy]). Samsung tints the clock to suit the wallpaper (the template's white digits come out grey
     * over a moon, cyan over blue water), so the template's colour is only where to start: per colour, the
     * most common colour of Samsung's pixels under that ink, for its upper and its lower half apart
     * (indexed by [Probe.part]). Null (not measured) if no colour is common enough there, or if it is far
     * lighter or darker than the template's (no tint does that, so it's a misplaced clock).
     */
    private fun shownColors(p: Probe, screen: IntArray, w: Int, h: Int, dx: Int, dy: Int, stride: Int = 1): Array<Int?> {
        val bins = colourBins.get()!!
        return Array(p.byPart.size) { part ->
            val g = part / 2
            val px = p.byPart[part]
            // The most common colour under this ink (the clock is one flat colour, or close to it within
            // half of it; what shows through around thin strokes varies), then the mean of the pixels close to it.
            val gen = ++bins.gen
            var n = 0
            var top = 0
            var topCount = 0
            for (k in px.indices step stride) {
                val i = px[k]
                val x = p.ink[i] % w + dx; val y = p.ink[i] / w + dy
                if (x !in 0 until w || y !in 0 until h) continue
                val c = screen[y * w + x]
                val bin = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
                if (bins.stamp[bin] != gen) { bins.stamp[bin] = gen; bins.count[bin] = 0 }
                val count = ++bins.count[bin]
                if (count > topCount || (count == topCount && bin < top)) { top = bin; topCount = count }
                n++
            }
            if (n == 0) return@Array null
            if (topCount < n * MIN_FLAT_SHARE) return@Array null
            val centre = Color.rgb((top shr 8 shl 4) + 8, ((top shr 4) and 15 shl 4) + 8, (top and 15 shl 4) + 8)
            var r = 0L; var gr = 0L; var b = 0L; var m = 0
            for (k in px.indices step stride) {
                val i = px[k]
                val x = p.ink[i] % w + dx; val y = p.ink[i] / w + dy
                if (x !in 0 until w || y !in 0 until h) continue
                val c = screen[y * w + x]
                if (!same(c, centre, 16)) continue
                r += Color.red(c); gr += Color.green(c); b += Color.blue(c); m++
            }
            val shown = if (m == 0) centre else Color.rgb((r / m).toInt(), (gr / m).toInt(), (b / m).toInt())
            shown.takeIf { abs(luma(it) - luma(p.keys[g])) <= MAX_TINT_LUMA && tintOf(it, p.keys[g]) }
        }
    }

    /** [shownColors]' colour counts (16 levels a channel), each bin valid only while its stamp is the current [gen]. */
    private class ColourBins {
        val count = IntArray(16 * 16 * 16)
        val stamp = IntArray(16 * 16 * 16)
        var gen = 0
    }

    private val colourBins: ThreadLocal<ColourBins> = ThreadLocal.withInitial { ColourBins() }

    /**
     * Whether Samsung may show [own] as [seen]: any colour for white and grey (tinted to suit the wallpaper),
     * but a chosen colour ([CHOSEN_CHROMA]) only in its own hue, at least a third as saturated. Another hue
     * under a chosen colour's ink is the wallpaper (seen on a Flip6: orange digits over a cat on a blue rug
     * "measured" orange at the top and blue at the bottom, and drawn shaded from one to the other).
     */
    private fun tintOf(seen: Int, own: Int): Boolean =
        chroma(own) < CHOSEN_CHROMA || (chroma(seen) * 3 >= chroma(own) && hueGap(seen, own) <= MAX_HUE_GAP)

    /**
     * [c] with its red moved [KEY_TOLERANCE] + 16 away (towards the middle): its own colour group in [probe],
     * with almost the same lightness.
     */
    private fun apart(c: Int): Int {
        val r = Color.red(c)
        val shifted = if (r >= 128) r - KEY_TOLERANCE - 16 else r + KEY_TOLERANCE + 16
        return Color.rgb(shifted, Color.green(c), Color.blue(c))
    }

    private fun luma(c: Int) = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10

    /**
     * How far the colours Samsung shows under [c]'s ink are from [c]'s own, summed over its colours' halves;
     * a half that couldn't be measured counts as far.
     */
    private fun colourGap(c: Candidate): Int {
        val shown = c.fit.shown ?: return Int.MAX_VALUE
        val keys = c.probe?.keys ?: return Int.MAX_VALUE
        return shown.indices.sumOf { part -> shown[part]?.let { distance(it, keys[part / 2]) } ?: UNMEASURED_GAP }
    }

    /**
     * Whether Samsung shows [c]'s chosen colours (see [CHOSEN_CHROMA]) under its ink ([screen], [w] x [h]):
     * at least [MIN_OWN_SHARE] of one such colour's ink in its hue and at least a third as saturated. True
     * for a clock in white or grey. (The most common colour under the ink isn't enough: for a blue time and
     * a paler date drawn as one colour, it was the date's near-white, and the right clock was ruled out.)
     */
    private fun showsOwnColours(c: Candidate, screen: IntArray, w: Int, h: Int): Boolean {
        val p = c.probe ?: return true
        var chosen = 0
        for (g in p.keys.indices) {
            val own = p.keys[g]
            if (chroma(own) < CHOSEN_CHROMA) continue
            chosen++
            var n = 0
            var hit = 0
            for (i in p.byGroup[g]) {
                val x = p.ink[i] % w + c.fit.dx
                val y = p.ink[i] / w + c.fit.dy
                if (x !in 0 until w || y !in 0 until h) continue
                n++
                if (tintOf(screen[y * w + x], own)) hit++
            }
            if (n > 0 && hit >= n * MIN_OWN_SHARE) return true
        }
        return chosen == 0
    }

    private fun chroma(c: Int): Int {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    /** Degrees between the hues of [a] and [b] (0..180). */
    private fun hueGap(a: Int, b: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(a, hsv); val ha = hsv[0]
        Color.colorToHSV(b, hsv); val hb = hsv[0]
        val d = abs(ha - hb) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * Samsung's preset picker (a long press on the locked cover) shows the presets as small cards on flat
     * grey: a snapshot of it fits the cards' clocks at the wrong size and place. Told by strips down both
     * edges of the cover, all of that one grey, where any wallpaper varies. The strips are narrow: as the
     * picker opens, the lock screen shrinks into its card and the grey comes in from the edges (filmed on a
     * Flip6: 18 cover pixels on the first frame, then 35, 51...), and those frames fit wrong clocks too.
     */
    private fun isPresetPicker(screen: IntArray, w: Int, h: Int): Boolean {
        val strip = maxOf(1, w / PICKER_EDGE)
        var grey = 0
        var n = 0
        for (y in h / 4 until h * 3 / 4) {
            for (x in 0 until strip) {
                n += 2
                if (same(screen[y * w + x], PICKER_GREY, PICKER_TOLERANCE)) grey++
                if (same(screen[y * w + w - 1 - x], PICKER_GREY, PICKER_TOLERANCE)) grey++
            }
        }
        return n > 0 && grey * 100L >= n * PICKER_PERCENT
    }

    private fun distance(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /**
     * The best [score] with the drawn clock moved by up to [reach] comparison pixels each way from
     * ([fromX], [fromY]) in [step]s, then around the best in halving steps down to a pixel; never more than
     * [MAX_SHIFT] in all.
     */
    private fun bestFit(
        p: Probe, screen: IntArray, w: Int, h: Int,
        fromX: Int = 0, fromY: Int = 0, reach: Int = MAX_SHIFT, step: Int = SHIFT_STEP,
    ): Fit {
        // The grid on every [COARSE_STRIDE]th pixel, then all of them around its best.
        val stride = if (step > 1) COARSE_STRIDE else 1
        var best = Fit(score(p, screen, w, h, fromX, fromY, stride = stride), fromX, fromY)
        for (dy in fromY - reach..fromY + reach step step) for (dx in fromX - reach..fromX + reach step step) {
            if (abs(dx) > MAX_SHIFT || abs(dy) > MAX_SHIFT || (dx == fromX && dy == fromY)) continue
            val s = score(p, screen, w, h, dx, dy, stride = stride)
            if (s > best.score) best = Fit(s, dx, dy)
        }
        if (stride != 1) best = Fit(score(p, screen, w, h, best.dx, best.dy), best.dx, best.dy)
        var fine = step / 2
        while (fine >= 1) {
            val (cx, cy) = best.dx to best.dy
            for (dy in cy - fine..cy + fine step fine) for (dx in cx - fine..cx + fine step fine) {
                if ((dx == cx && dy == cy) || abs(dx) > MAX_SHIFT || abs(dy) > MAX_SHIFT) continue
                val s = score(p, screen, w, h, dx, dy)
                if (s > best.score) best = Fit(s, dx, dy)
            }
            fine /= 2
        }
        return Fit(best.score, best.dx, best.dy, shownColors(p, screen, w, h, best.dx, best.dy))
    }

    /**
     * How well FlipFix's drawn clock, moved by ([dx], [dy]), sits on Samsung's lock screen ([screen]): the
     * share of ink pixels whose colour (as Samsung tints it, [shownColors]) Samsung has there (or one pixel
     * away), minus the share of the background ring around the ink that happens to have that colour too.
     * A white clock over clouds scores low on its own; only a clock in the right place and size, drawn in
     * one flat colour against a different background, scores high. "That colour" is judged against how far
     * the background is from it: a black clock on dark grey needs a tighter tolerance than white on night sky.
     * [parts]: those parts' ink and ring alone (see [Probe.part]; e.g. the date's colour, or the digits' upper half).
     * [stride]: every so many of the pixels only (a quicker, rougher score for a coarse search).
     */
    private fun score(
        p: Probe, screen: IntArray, w: Int, h: Int, dx: Int, dy: Int, parts: Set<Int>? = null, stride: Int = 1,
    ): Float {
        val shown = shownColors(p, screen, w, h, dx, dy, stride)
        val colour = IntArray(shown.size) { part -> shown[part] ?: p.keys[part / 2] }
        // Per colour (and half), how far the background differs from it (largest channel), and how many ring
        // pixels it has: a ring pixel "has the colour" when that difference is within the tolerance.
        val contrast = Array(shown.size) { IntArray(256) }
        val ringTotal = IntArray(shown.size)
        for (i in p.ring.indices step stride) {
            val part = p.ringPart[i]
            ringTotal[part]++
            val x = p.ring[i] % w + dx; val y = p.ring[i] / w + dy
            if (x !in 0 until w || y !in 0 until h) continue
            contrast[part][maxDifference(screen[y * w + x], colour[part])]++
        }
        // The tolerance: half the background's median difference.
        val tolerance = IntArray(shown.size) { part ->
            val counts = contrast[part]
            val n = counts.sum()
            if (n == 0) return@IntArray COLOR_TOLERANCE
            var seen = 0; var median = 0
            while (median < 255 && seen + counts[median] <= n / 2) { seen += counts[median]; median++ }
            (median / 2).coerceIn(MIN_TOLERANCE, COLOR_TOLERANCE)
        }
        var inkHit = 0
        var inkCount = 0
        var ringHit = 0
        var ringCount = 0
        for (part in colour.indices) {
            if (parts != null && part !in parts) continue
            val px = p.byPart[part]
            for (k in px.indices step stride) {
                val i = px[k]
                inkCount++
                if (near(screen, w, h, p.ink[i] % w + dx, p.ink[i] / w + dy, colour[part], tolerance[part])) inkHit++
            }
            for (d in 0..tolerance[part]) ringHit += contrast[part][d]
            ringCount += ringTotal[part]
        }
        if (inkCount == 0) return 0f
        val inkRate = inkHit.toFloat() / inkCount
        val ringRate = if (ringCount == 0) 0f else ringHit.toFloat() / ringCount
        return inkRate - ringRate
    }

    private fun same(a: Int, b: Int, tolerance: Int = COLOR_TOLERANCE) =
        abs(Color.red(a) - Color.red(b)) <= tolerance &&
            abs(Color.green(a) - Color.green(b)) <= tolerance &&
            abs(Color.blue(a) - Color.blue(b)) <= tolerance

    private fun maxDifference(a: Int, b: Int) = maxOf(
        abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)),
    )

    /** Samsung's pixel at (x, y) or one pixel away has the colour [c]. */
    private fun near(screen: IntArray, w: Int, h: Int, x: Int, y: Int, c: Int, tolerance: Int): Boolean {
        if (x in 0 until w && y in 0 until h && same(screen[y * w + x], c, tolerance)) return true
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until w && ny in 0 until h && same(screen[ny * w + nx], c, tolerance)) return true
        }
        return false
    }

    private fun inkWithin(clock: IntArray, w: Int, h: Int, x: Int, y: Int): Boolean {
        for (dy in -RING..RING) for (dx in -RING..RING) {
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until w && ny in 0 until h && Color.alpha(clock[ny * w + nx]) >= INK_ALPHA) return true
        }
        return false
    }

    private fun nearestInkColor(clock: IntArray, w: Int, h: Int, x: Int, y: Int): Int {
        for (r in 1..RING) for (dy in -r..r) for (dx in -r..r) {
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until w && ny in 0 until h) {
                val c = clock[ny * w + nx]
                if (Color.alpha(c) >= INK_ALPHA) return c
            }
        }
        return Color.WHITE
    }
}
