package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
 * (offscreen), and keeps the preset whose clock lands on Samsung's pixels. If none does (a clock style
 * FlipFix can't draw yet, or something covering the clock), the face shows no clock rather than a wrong one,
 * and the next lock screen is checked again. The snapshot stays in memory and is dropped straight after.
 */
object CoverFaceMatcher {

    /** Samsung setting that changes whenever the cover preset is switched (One UI 9). */
    const val PRESET_SETTING = "favorite_list_changed"

    private const val PREFS = "cover_face_match"
    private const val KEY_STAMP = "stamp"
    private const val KEY_FOLDER = "folder"
    private const val KEY_TRIES = "tries"
    private const val NO_STAMP = "?"

    /** Wakes checked per preset switch when nothing matches, before waiting for the next switch. */
    private const val MAX_TRIES = 5

    /** Without a readable [PRESET_SETTING], re-check at most this often. */
    private const val UNSTAMPED_RECHECK_MS = 10 * 60_000L

    /** Comparison at half the cover's resolution: plenty for clock strokes, a quarter of the work. */
    private const val SCALE = 2

    /** Pixels at least this opaque in FlipFix's drawing count as clock ink. */
    private const val INK_ALPHA = 200

    /** Per-channel colour difference still counted as "the same colour" (lock screen effects, scaling). */
    private const val COLOR_TOLERANCE = 56

    /** Background ring around the ink, in comparison pixels. */
    private const val RING = 3

    /** Minimum (ink match - background match) for a preset to count as the active one. */
    private const val MIN_SCORE = 0.30f

    /** Largest offset tried between the template's clock and Samsung's, in comparison pixels (x2 on the cover). */
    private const val MAX_SHIFT = 20
    private const val SHIFT_STEP = 4
    private const val KEY_SHIFT_X = "shift_x"
    private const val KEY_SHIFT_Y = "shift_y"

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

    /** Folder of the active preset (its video and thumbnail live there too), if known. */
    fun activeFolder(context: Context): File? = activeTemplate(context)?.parentFile

    /** Whether the lock screen should be checked now. */
    fun needsCheck(context: Context): Boolean {
        val p = prefs(context)
        val now = presetStamp(context)
        if (now == NO_STAMP) return SystemClock.uptimeMillis() - lastUnstampedCheck > UNSTAMPED_RECHECK_MS ||
            lastUnstampedCheck == 0L
        val stamp = p.getString(KEY_STAMP, null) ?: return true
        if (stamp != now) return true
        return p.getString(KEY_FOLDER, "").isNullOrEmpty() && p.getInt(KEY_TRIES, 0) < MAX_TRIES
    }

    /**
     * Compares [shot] (Samsung's locked cover, taken at [at] while [stamp] was current) with every
     * preset's clock and records the active preset. Runs off the main thread; recycles [shot].
     * [newTry]: the first snapshot of a wake (later snapshots of the same wake count as the same try).
     * Returns whether another snapshot is worth taking (nothing matched, but some preset could have).
     */
    fun match(context: Context, shot: Bitmap, at: Date, stamp: String, newTry: Boolean): Boolean {
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

        // Presets with the same clock (same style, place, size and colour) draw the same: compare once.
        val byLayout = CoverClockLayout.templates()
            .filter { CoverClockLayout.sameShape(it, coverW, coverH) }
            .map { it to CoverClockLayout.parse(context, it) }
            .filter { it.second != CoverClockLayout.Unknown }
            .groupBy({ it.second }, { it.first })

        val renderer = CoverClockRenderer(context)
        val drawn = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(drawn).apply { scale(1f / SCALE, 1f / SCALE) }
        val clock = IntArray(w * h)
        val fits = byLayout.map { (layout, files) ->
            drawn.eraseColor(Color.TRANSPARENT)
            renderer.draw(canvas, layout, at, coverW.toFloat(), coverH.toFloat())
            drawn.getPixels(clock, 0, w, 0, 0, w, h)
            files to (probe(clock, w, h)?.let { bestFit(it, screen, w, h) } ?: Fit(0f, 0, 0))
        }
        drawn.recycle()

        val best = fits.maxByOrNull { it.second.score }
        val matchedFit = best?.takeIf { it.second.score >= MIN_SCORE }
        val matched = matchedFit?.first?.maxByOrNull { it.lastModified() }?.parentFile?.name
        val p = prefs(context)
        val sameSwitch = p.getString(KEY_STAMP, null) == stamp
        val tries = if (sameSwitch) p.getInt(KEY_TRIES, 0) + (if (newTry) 1 else 0) else 1
        p.edit()
            .putString(KEY_STAMP, stamp)
            .putString(KEY_FOLDER, matched.orEmpty())
            .putFloat(KEY_SHIFT_X, (matchedFit?.second?.dx ?: 0).toFloat() / w)
            .putFloat(KEY_SHIFT_Y, (matchedFit?.second?.dy ?: 0).toFloat() / h)
            .putInt(KEY_TRIES, tries)
            .apply()
        val scores = fits.map { it.first to it.second.score }
        if (stamp == NO_STAMP) lastUnstampedCheck = SystemClock.uptimeMillis()

        Log.d(
            CoverState.TAG,
            "cover preset check (${SystemClock.uptimeMillis() - started} ms, try $tries, $shotInfo, " +
                "${byLayout.size} drawable of ${CoverClockLayout.templates().size}): " +
                (matched ?: "no match") +
                (matchedFit?.second?.let { " shifted ${it.dx * SCALE},${it.dy * SCALE} px" } ?: "") + "; scores " +
                scores.sortedByDescending { it.second }.joinToString { (files, s) ->
                    "${files.joinToString("+") { it.parentFile?.name.orEmpty() }}=${"%.2f".format(s)}"
                },
        )
        observers.forEach { it() }
        return matched == null && byLayout.isNotEmpty()
    }

    /**
     * Where the shifted clock is (fractions of the cover), as Samsung's lock screen showed it for the active
     * preset. Samsung's lock screen doesn't always put the clock exactly where the template has it (e.g. a
     * right-aligned clock sits a little further right), so the check also finds that small offset.
     */
    fun activeShift(context: Context): Pair<Float, Float> {
        if (activeTemplate(context) == null) return 0f to 0f
        val p = prefs(context)
        return p.getFloat(KEY_SHIFT_X, 0f) to p.getFloat(KEY_SHIFT_Y, 0f)
    }

    /** A drawn clock's ink pixels and the background ring around them, with the colours each should have. */
    private class Probe(val ink: IntArray, val inkColor: IntArray, val ring: IntArray, val ringColor: IntArray)

    private class Fit(val score: Float, val dx: Int, val dy: Int)

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
        return Probe(ink.toIntArray(), inkColor.toIntArray(), ring.toIntArray(), ringColor.toIntArray())
    }

    /** The best [score] with the drawn clock moved by up to [MAX_SHIFT] comparison pixels each way. */
    private fun bestFit(p: Probe, screen: IntArray, w: Int, h: Int): Fit {
        var best = Fit(score(p, screen, w, h, 0, 0), 0, 0)
        for (dy in -MAX_SHIFT..MAX_SHIFT step SHIFT_STEP) for (dx in -MAX_SHIFT..MAX_SHIFT step SHIFT_STEP) {
            val s = score(p, screen, w, h, dx, dy)
            if (s > best.score) best = Fit(s, dx, dy)
        }
        val (cx, cy) = best.dx to best.dy
        for (dy in cy - SHIFT_STEP + 1 until cy + SHIFT_STEP) for (dx in cx - SHIFT_STEP + 1 until cx + SHIFT_STEP) {
            if (abs(dx) > MAX_SHIFT || abs(dy) > MAX_SHIFT) continue
            val s = score(p, screen, w, h, dx, dy)
            if (s > best.score) best = Fit(s, dx, dy)
        }
        return best
    }

    /**
     * How well FlipFix's drawn clock, moved by ([dx], [dy]), sits on Samsung's lock screen ([screen]): the
     * share of ink pixels whose colour Samsung also has there (or one pixel away), minus the share of the
     * background ring around the ink that happens to have that colour too. A white clock over clouds scores
     * low on its own; only a clock in the right place, size and colour scores high.
     */
    private fun score(p: Probe, screen: IntArray, w: Int, h: Int, dx: Int, dy: Int): Float {
        var inkHit = 0
        for (i in p.ink.indices) {
            val x = p.ink[i] % w + dx; val y = p.ink[i] / w + dy
            if (near(screen, w, h, x, y, p.inkColor[i])) inkHit++
        }
        var ringHit = 0
        for (i in p.ring.indices) {
            val x = p.ring[i] % w + dx; val y = p.ring[i] / w + dy
            if (x in 0 until w && y in 0 until h && same(screen[y * w + x], p.ringColor[i])) ringHit++
        }
        val inkRate = inkHit.toFloat() / p.ink.size
        val ringRate = if (p.ring.isEmpty()) 0f else ringHit.toFloat() / p.ring.size
        return inkRate - ringRate
    }

    private fun same(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) <= COLOR_TOLERANCE &&
            abs(Color.green(a) - Color.green(b)) <= COLOR_TOLERANCE &&
            abs(Color.blue(a) - Color.blue(b)) <= COLOR_TOLERANCE

    /** Samsung's pixel at (x, y) or one pixel away has the colour [c]. */
    private fun near(screen: IntArray, w: Int, h: Int, x: Int, y: Int, c: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until w && ny in 0 until h && same(screen[ny * w + nx], c)) return true
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
