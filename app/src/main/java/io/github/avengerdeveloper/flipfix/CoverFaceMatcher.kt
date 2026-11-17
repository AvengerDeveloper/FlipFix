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

    /** Samsung's tint of a clock colour keeps its lightness to within this (luma, 0..255). */
    private const val MAX_TINT_LUMA = 90

    /** Samsung's colour under a clock colour's ink counts as measured if this share of it is one flat colour. */
    private const val MIN_FLAT_SHARE = 0.2f

    /** Digits whose upper and lower halves Samsung shows this far apart (summed RGB) are shaded, not flat. */
    private const val MIN_SHADE = 24

    /** Background ring around the ink, in comparison pixels. */
    private const val RING = 3

    /** Minimum (ink match - background match) for a preset to count as the active one. */
    private const val MIN_SCORE = 0.30f

    /** Largest offset tried between the template's clock and Samsung's, in comparison pixels (x2 on the cover). */
    private const val MAX_SHIFT = 20
    private const val SHIFT_STEP = 4
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

    /** While trying sizes: offsets tried around the last best one (comparison pixels). */
    private const val NEAR_SHIFT = 4
    private const val NEAR_STEP = 2
    private const val KEY_SIZE = "size"
    private const val KEY_PIVOT_X = "pivot_x"
    private const val KEY_PIVOT_Y = "pivot_y"

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
        // After an update: look again (it may draw this clock now, or read its place and colours better).
        if (p.getLong(KEY_VERSION, -1) != appVersion(context)) return true
        // Nothing matched: try a few more wakes.
        return p.getString(KEY_FOLDER, "").isNullOrEmpty() && p.getInt(KEY_TRIES, 0) < MAX_TRIES
    }

    private fun appVersion(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)

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
            val pivotX = p.centerX * SCALE
            val pivotY = p.centerY * SCALE
            fun at(size: Float, near: Fit): Candidate? {
                val sized = probeOf(c.layout, size, pivotX, pivotY) ?: return null
                val fit = bestFit(sized, screen, w, h, near.dx, near.dy, NEAR_SHIFT, NEAR_STEP)
                return c.copy(probe = sized, fit = fit, size = size, pivotX = pivotX, pivotY = pivotY)
            }
            var best = c
            for (direction in intArrayOf(1, -1)) {
                var step = 1
                while (true) {
                    val size = 1f + direction * step * SIZE_STEP
                    if (size < MIN_SIZE || size > MAX_SIZE) break
                    val next = at(size, best.fit) ?: break
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

        val fits = byLayout.map { (parsed, files) ->
            // The date drawn a shade apart from the digits, so the two are measured separately: Samsung may
            // tint the digits and leave the date white, though the template has both white.
            val layout = parsed.inkColors()?.let { (digits, date) -> parsed.withColors(digits, apart(date)) } ?: parsed
            val probe = probeOf(layout)
            Candidate(files, parsed, layout, probe, probe?.let { bestFit(it, screen, w, h) } ?: Fit(0f, 0, 0))
        }.sortedByDescending { it.fit.score }.toMutableList().also { ranked ->
            for (i in 0 until minOf(SIZED_CANDIDATES, ranked.size)) {
                if (i > 0 && ranked.take(i).maxOf { it.fit.score } >= SURE_SCORE) break
                ranked[i] = resized(ranked[i])
            }
        }
        drawn.recycle()

        val best = fits.maxByOrNull { it.fit.score }
        val matchedFit = best?.takeIf { it.fit.score >= MIN_SCORE }
        val matched = matchedFit?.files?.maxByOrNull { it.lastModified() }?.parentFile?.name
        val p = prefs(context)
        val sameSwitch = p.getString(KEY_STAMP, null) == stamp
        val version = appVersion(context)
        val sameVersion = p.getLong(KEY_VERSION, -1) == version
        val tries = if (sameSwitch && sameVersion) p.getInt(KEY_TRIES, 0) + (if (newTry) 1 else 0) else 1
        val edit = p.edit()
            .putString(KEY_STAMP, stamp)
            .putString(KEY_FOLDER, matched.orEmpty())
            .putFloat(KEY_SHIFT_X, (matchedFit?.fit?.dx ?: 0).toFloat() / w)
            .putFloat(KEY_SHIFT_Y, (matchedFit?.fit?.dy ?: 0).toFloat() / h)
            .putFloat(KEY_SIZE, matchedFit?.size ?: 1f)
            .putFloat(KEY_PIVOT_X, (matchedFit?.pivotX ?: 0f) / coverW)
            .putFloat(KEY_PIVOT_Y, (matchedFit?.pivotY ?: 0f) / coverH)
            .putInt(KEY_TRIES, tries)
            .putLong(KEY_VERSION, version)
        var colorInfo = ""
        val shownColors = matchedFit?.let { clockColors(it, w, h) }
        val brightness = (lumaSum / screen.size).toInt()
        val samePreset = sameSwitch && p.getString(KEY_FOLDER, null) == matched
        if (shownColors == null) {
            edit.remove(KEY_COLORS)
        } else if (!samePreset || !sameVersion || brightness >= p.getInt(KEY_COLORS_BRIGHTNESS, -1)) {
            // A lock screen still fading in shows the clock darker: keep the colours of the brightest snapshot
            // (of this preset, measured by this version: an update may measure them better).
            edit.putString(KEY_COLORS, shownColors.encode()).putInt(KEY_COLORS_BRIGHTNESS, brightness)
            colorInfo = ", shown in " + shownColors.describe()
        }
        edit.apply()
        val scores = fits.map { it.files to it.fit.score }
        if (stamp == NO_STAMP) lastUnstampedCheck = SystemClock.uptimeMillis()

        Log.d(
            CoverState.TAG,
            "cover preset check (${SystemClock.uptimeMillis() - started} ms, try $tries, $shotInfo, " +
                "${byLayout.size} drawable of ${CoverClockLayout.templates().size}): " +
                (matched ?: "no match") +
                (matchedFit?.let { " shifted ${it.fit.dx * SCALE},${it.fit.dy * SCALE} px" } ?: "") +
                (matchedFit?.takeIf { it.size != 1f }?.let { " at ${"%.0f".format(it.size * 100)}% size" } ?: "") +
                colorInfo + "; scores " +
                scores.sortedByDescending { it.second }.joinToString { (files, s) ->
                    "${files.joinToString("+") { it.parentFile?.name.orEmpty() }}=${"%.2f".format(s)}"
                },
        )
        observers.forEach { it() }
        return matched == null && byLayout.isNotEmpty()
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

    fun activePlacement(context: Context): Placement {
        if (activeTemplate(context) == null) return Placement()
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
        fun groupOf(drawnColor: Int) = probe.keys.indices.minByOrNull { distance(probe.keys[it], drawnColor or 0xFF000000.toInt()) }
        fun whole(g: Int?, own: Int): Int {
            g ?: return own
            val upper = shown[2 * g]; val lower = shown[2 * g + 1]
            return when {
                upper != null && lower != null -> mix(upper, lower, 0.5f)
                else -> upper ?: lower ?: own
            }
        }
        val dateShown = whole(groupOf(dateDrawn), date)
        val g = groupOf(digitsDrawn) ?: return ClockColors(digits, dateShown)
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
        if (activeTemplate(context) == null) return null
        return prefs(context).getString(KEY_COLORS, null)?.let { ClockColors.decode(it) }
    }

    /**
     * A drawn clock's ink pixels and the background ring around them. The clock's colours ([keys]: e.g. the
     * digits' and the date's) number the groups: [byGroup] lists each group's ink pixels (indices into
     * [ink]), [ringGroup] the colour each ring pixel should not have. [splitY]: per group, the last row of
     * its upper half (Samsung may shade a colour from top to bottom, so each half is measured on its own).
     * ([centerX], [centerY]): the middle of the ink's box.
     */
    private class Probe(
        val ink: IntArray, val byGroup: Array<IntArray>, val ring: IntArray, val ringGroup: IntArray, val keys: IntArray,
        val splitY: IntArray, val centerX: Float, val centerY: Float,
    ) {
        /** Index into [shownColors]'s result for [group] at [row]. */
        fun part(group: Int, row: Int) = 2 * group + (if (row > splitY[group]) 1 else 0)
    }

    /** [shown]: per colour group's upper and lower half, the colour Samsung's lock screen shows it in (see [shownColors]). */
    private class Fit(val score: Float, val dx: Int, val dy: Int, val shown: Array<Int?>? = null)

    /**
     * A preset ([files] share one clock, [parsed]) as compared: drawn as [layout], resized by [size] about
     * ([pivotX], [pivotY]) (cover pixels), giving [probe], whose best placement on the lock screen is [fit].
     */
    private data class Candidate(
        val files: List<File>, val parsed: CoverClockLayout, val layout: CoverClockLayout, val probe: Probe?,
        val fit: Fit, val size: Float = 1f, val pivotX: Float = 0f, val pivotY: Float = 0f,
    )

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
            (x0 + x1 + 1) / 2f, (y0 + y1 + 1) / 2f,
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
    private fun shownColors(p: Probe, screen: IntArray, w: Int, h: Int, dx: Int, dy: Int): Array<Int?> {
        val bins = IntArray(16 * 16 * 16)
        return Array(p.keys.size * 2) { part ->
            val g = part / 2
            // The most common colour under this ink (the clock is one flat colour, or close to it within
            // half of it; what shows through around thin strokes varies), then the mean of the pixels close to it.
            bins.fill(0)
            var n = 0
            for (i in p.byGroup[g]) {
                if (p.part(g, p.ink[i] / w) != part) continue
                val x = p.ink[i] % w + dx; val y = p.ink[i] / w + dy
                if (x !in 0 until w || y !in 0 until h) continue
                val c = screen[y * w + x]
                bins[(Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)]++
                n++
            }
            if (n == 0) return@Array null
            var top = 0
            for (b in bins.indices) if (bins[b] > bins[top]) top = b
            if (bins[top] < n * MIN_FLAT_SHARE) return@Array null
            val centre = Color.rgb((top shr 8 shl 4) + 8, ((top shr 4) and 15 shl 4) + 8, (top and 15 shl 4) + 8)
            var r = 0L; var gr = 0L; var b = 0L; var m = 0
            for (i in p.byGroup[g]) {
                if (p.part(g, p.ink[i] / w) != part) continue
                val x = p.ink[i] % w + dx; val y = p.ink[i] / w + dy
                if (x !in 0 until w || y !in 0 until h) continue
                val c = screen[y * w + x]
                if (!same(c, centre, 16)) continue
                r += Color.red(c); gr += Color.green(c); b += Color.blue(c); m++
            }
            val shown = if (m == 0) centre else Color.rgb((r / m).toInt(), (gr / m).toInt(), (b / m).toInt())
            shown.takeIf { abs(luma(it) - luma(p.keys[g])) <= MAX_TINT_LUMA }
        }
    }

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

    private fun distance(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /**
     * The best [score] with the drawn clock moved by up to [reach] comparison pixels each way from
     * ([fromX], [fromY]) in [step]s, then pixel by pixel around the best; never more than [MAX_SHIFT] in all.
     */
    private fun bestFit(
        p: Probe, screen: IntArray, w: Int, h: Int,
        fromX: Int = 0, fromY: Int = 0, reach: Int = MAX_SHIFT, step: Int = SHIFT_STEP,
    ): Fit {
        var best = Fit(score(p, screen, w, h, fromX, fromY), fromX, fromY)
        for (dy in fromY - reach..fromY + reach step step) for (dx in fromX - reach..fromX + reach step step) {
            if (abs(dx) > MAX_SHIFT || abs(dy) > MAX_SHIFT) continue
            val s = score(p, screen, w, h, dx, dy)
            if (s > best.score) best = Fit(s, dx, dy)
        }
        val (cx, cy) = best.dx to best.dy
        for (dy in cy - step + 1 until cy + step) for (dx in cx - step + 1 until cx + step) {
            if (abs(dx) > MAX_SHIFT || abs(dy) > MAX_SHIFT) continue
            val s = score(p, screen, w, h, dx, dy)
            if (s > best.score) best = Fit(s, dx, dy)
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
     */
    private fun score(p: Probe, screen: IntArray, w: Int, h: Int, dx: Int, dy: Int): Float {
        val shown = shownColors(p, screen, w, h, dx, dy)
        fun colorOf(part: Int, g: Int) = shown[part] ?: p.keys[g]
        // Per colour (and half), the background's median difference from it (largest channel).
        val contrast = Array(shown.size) { IntArray(256) }
        for (i in p.ring.indices) {
            val x = p.ring[i] % w + dx; val y = p.ring[i] / w + dy
            if (x !in 0 until w || y !in 0 until h) continue
            val g = p.ringGroup[i]
            val part = p.part(g, p.ring[i] / w)
            contrast[part][maxDifference(screen[y * w + x], colorOf(part, g))]++
        }
        val tolerance = IntArray(shown.size) { part ->
            val counts = contrast[part]
            val n = counts.sum()
            if (n == 0) return@IntArray COLOR_TOLERANCE
            var seen = 0; var median = 0
            while (median < 255 && seen + counts[median] <= n / 2) { seen += counts[median]; median++ }
            (median / 2).coerceIn(MIN_TOLERANCE, COLOR_TOLERANCE)
        }
        var inkHit = 0
        for (g in p.byGroup.indices) for (i in p.byGroup[g]) {
            val x = p.ink[i] % w + dx; val y = p.ink[i] / w + dy
            val part = p.part(g, p.ink[i] / w)
            if (near(screen, w, h, x, y, colorOf(part, g), tolerance[part])) inkHit++
        }
        var ringHit = 0
        for (i in p.ring.indices) {
            val x = p.ring[i] % w + dx; val y = p.ring[i] / w + dy
            val g = p.ringGroup[i]
            val part = p.part(g, p.ring[i] / w)
            if (x in 0 until w && y in 0 until h && same(screen[y * w + x], colorOf(part, g), tolerance[part])) ringHit++
        }
        val inkRate = inkHit.toFloat() / p.ink.size
        val ringRate = if (p.ring.isEmpty()) 0f else ringHit.toFloat() / p.ring.size
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
        for (dy in -1..1) for (dx in -1..1) {
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
