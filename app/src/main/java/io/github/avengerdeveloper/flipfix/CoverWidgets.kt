package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Blurred stand-ins for the widgets on Samsung's locked cover. One UI 9 puts them in one row under the
 * clock: up to four slots, each widget one or more slots wide. Showing them live would take the
 * permissions their data needs (health, alarms, usage...), which FlipFix doesn't ask for: FlipFix is made
 * for widgetless cover screens. So the face shows each widget where Samsung does, in its shape, blurred
 * past reading, and doing nothing; the cover looks as it did, and the real widgets are a swipe away.
 *
 * The tiles are found on the snapshot the preset check takes ([CoverFaceMatcher]), copied blurred, and kept
 * in memory only, never stored: after a restart they come back with the next check.
 */
object CoverWidgets {

    /** A widget tile: where it is on the cover (fractions) and its blurred look. */
    class Tile(val left: Float, val top: Float, val right: Float, val bottom: Float, val slots: Int, val image: Bitmap)

    /**
     * What the check of preset switch [stamp] saw: the matched preset's [folder] ("" if none) and its
     * [tiles], from a snapshot this bright; [looks]: snapshots looked at for them.
     */
    class Row(val stamp: String, val folder: String, val brightness: Int, val tiles: List<Tile>, val looks: Int = 1) {
        fun looked() = Row(stamp, folder, brightness, tiles, looks + 1)
    }

    @Volatile
    var row: Row? = null
        private set

    // Samsung's row on the 948-pixel-wide cover, in cover pixels (scaled to other widths).
    private const val REFERENCE_WIDTH = 948f
    private const val SLOT = 140f
    private const val SLOT_GAP = 31f
    private const val CORNER = 32f

    /** The row's top this far below the clock's ink (Samsung: 45). */
    private const val ROW_GAP_MIN = 36f
    private const val ROW_GAP_MAX = 56f

    /** The row starts this close to the clock's edge (left/right) or middle. */
    private const val ANCHOR_REACH = 8f

    /** Blur: three box passes of this radius (cover pixels), close to a 13-pixel Gaussian. */
    private const val BLUR = 12f

    /** Luma step across an outline point that counts as an edge there. */
    private const val EDGE_STEP = 3f

    /** A tile's outline shows all round (share of its points), sharply (median step)... */
    private const val MIN_COVERAGE = 0.85f
    private const val MIN_STEP = 8f

    /** ...around a smooth padding band (Samsung blurs the wallpaper behind a widget; median gradient). */
    private const val MAX_PADDING = 1.5f

    /** A wide tile whose slot gaps show edges like this is two tiles. */
    private const val SPLIT = 0.8f

    private const val MAX_SLOTS = 4

    /**
     * Snapshots a preset check may look at for widgets: Samsung fades them in after the clock, so the first
     * snapshot (taken early, to know the clock before a quick unlock) can miss them.
     */
    private const val MAX_LOOKS = 3

    /** Whether another snapshot is worth taking for [stamp]: its preset matched, but no widgets showed yet. */
    fun wantsAnotherLook(stamp: String): Boolean {
        val r = row ?: return false
        return r.stamp == stamp && r.folder.isNotEmpty() && r.tiles.isEmpty() && r.looks < MAX_LOOKS
    }

    /** The tiles of the active preset (none if it has no widgets or isn't known). */
    fun active(context: Context): List<Tile> {
        val r = row ?: return emptyList()
        if (r.folder.isEmpty() || r.folder != CoverFaceMatcher.activeFolder(context)?.name) return emptyList()
        return r.tiles
    }

    /**
     * Looks for widget tiles on [screen] ([w] x [h], Samsung's locked cover at the comparison scale, taken
     * while [stamp] was current) under the clock whose ink spans [clock] there, aligned by [align] (null:
     * judged from where the ink sits). Keeps them, blurred, for [folder] (the matched preset; null: none
     * matched). Tiles once found stay (a later snapshot may catch them mid-fade) unless a snapshot at
     * least as bright finds others. Returns a note for the log.
     */
    fun look(
        stamp: String, folder: String?, brightness: Int, screen: IntArray, w: Int, h: Int,
        clock: RectF?, align: CoverClockLayout.Align?,
    ): String {
        if (folder == null || clock == null) {
            row = Row(stamp, "", brightness, emptyList())
            return ""
        }
        val prev = row?.takeIf { it.stamp == stamp && it.folder == folder }
        if (prev != null && prev.tiles.isNotEmpty() && brightness < prev.brightness) {
            row = prev.looked()
            return ""
        }
        val (found, closest) = find(screen, w, h, clock, align ?: alignOf(clock, w))
        if (found.isEmpty() && prev != null && prev.tiles.isNotEmpty()) {
            row = prev.looked()
            return ""
        }
        val tiles = found.map { (rect, slots) ->
            Tile(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h, slots, replica(screen, w, h, rect))
        }
        row = Row(stamp, folder, brightness, tiles, (prev?.looks ?: 0) + 1)
        if (tiles.isNotEmpty()) return ", widgets " + tiles.joinToString("+") { "${it.slots}x1" }
        return ", no widgets" + (closest?.let { (slots, l) ->
            " (closest ${slots}x1: outline %d%%, edge %.1f, padding %.1f)".format((l.coverage * 100).roundToInt(), l.step, l.padding)
        } ?: "")
    }

    /** Draws [tiles] on a [w] x [h] cover drawn at ([offX], [offY]). */
    fun draw(canvas: Canvas, tiles: List<Tile>, w: Float, h: Float, offX: Float, offY: Float) {
        for (t in tiles) {
            val rect = RectF(t.left * w - offX, t.top * h - offY, t.right * w - offX, t.bottom * h - offY)
            val matrix = Matrix().apply {
                setScale(rect.width() / t.image.width, rect.height() / t.image.height)
                postTranslate(rect.left, rect.top)
            }
            paint.shader = BitmapShader(t.image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            val corner = rect.height() * CORNER / SLOT
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
        paint.shader = null
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun alignOf(clock: RectF, w: Int): CoverClockLayout.Align = when {
        abs(clock.centerX() - w / 2f) < w * 0.05f -> CoverClockLayout.Align.CENTER
        clock.centerX() < w / 2f -> CoverClockLayout.Align.LEFT
        else -> CoverClockLayout.Align.RIGHT
    }

    /** A tile hypothesis' outline: its points and outward normals (x, y, nx, ny). */
    private fun outline(x0: Float, y0: Float, width: Float, height: Float, r: Float): FloatArray {
        val pts = ArrayList<Float>()
        fun line(ax: Float, ay: Float, bx: Float, by: Float, nx: Float, ny: Float) {
            val n = max(1, (hypot(bx - ax, by - ay) / 2f).toInt())
            for (i in 0..n) {
                val t = i.toFloat() / n
                pts += ax + (bx - ax) * t; pts += ay + (by - ay) * t; pts += nx; pts += ny
            }
        }
        val x1 = x0 + width; val y1 = y0 + height
        line(x0 + r, y0, x1 - r, y0, 0f, -1f); line(x0 + r, y1, x1 - r, y1, 0f, 1f)
        line(x0, y0 + r, x0, y1 - r, -1f, 0f); line(x1, y0 + r, x1, y1 - r, 1f, 0f)
        for ((cx, cy, a0) in listOf(Triple(x0 + r, y0 + r, 180), Triple(x1 - r, y0 + r, 270), Triple(x1 - r, y1 - r, 0), Triple(x0 + r, y1 - r, 90))) {
            for (k in 1..5) {
                val a = Math.toRadians((a0 + k * 15).toDouble())
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                pts += cx + r * c; pts += cy + r * s; pts += c; pts += s
            }
        }
        return pts.toFloatArray()
    }

    /** Value of [m] ([w] wide) between pixel centres, or NaN off the image. */
    private fun at(m: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        if (x < 0 || y < 0 || x >= w - 1 || y >= h - 1) return Float.NaN
        val x0 = x.toInt(); val y0 = y.toInt(); val fx = x - x0; val fy = y - y0
        val i = y0 * w + x0
        return m[i] * (1 - fx) * (1 - fy) + m[i + 1] * fx * (1 - fy) + m[i + w] * (1 - fx) * fy + m[i + w + 1] * fx * fy
    }

    private class Look(val coverage: Float, val step: Float, val padding: Float)

    private class Images(val luma: FloatArray, val grad: FloatArray, val w: Int, val h: Int)

    private fun images(screen: IntArray, w: Int, h: Int): Images {
        val l = FloatArray(w * h) { val c = screen[it]; (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10f }
        val g = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            g[i] = max(abs(l[i + 1] - l[i - 1]), abs(l[i + w] - l[i - w])) / 2f
        }
        return Images(l, g, w, h)
    }

    /** How a tile [width] x [height] at ([x0], [y0]) shows: its outline's edges and its padding's smoothness. */
    private fun look(im: Images, x0: Float, y0: Float, width: Float, height: Float, r: Float): Look? {
        val pts = outline(x0, y0, width, height, r)
        val n = pts.size / 4
        val steps = FloatArray(n)
        val padding = ArrayList<Float>(n * 2)
        for (i in 0 until n) {
            val x = pts[4 * i]; val y = pts[4 * i + 1]; val nx = pts[4 * i + 2]; val ny = pts[4 * i + 3]
            val a = at(im.luma, im.w, im.h, x - nx * 1.5f, y - ny * 1.5f)
            val b = at(im.luma, im.w, im.h, x + nx * 1.5f, y + ny * 1.5f)
            if (a.isNaN() || b.isNaN()) return null
            steps[i] = abs(a - b)
            for (d in intArrayOf(3, 5)) {
                val g = at(im.grad, im.w, im.h, x - nx * d, y - ny * d)
                if (!g.isNaN()) padding += g
            }
        }
        return Look(steps.count { it >= EDGE_STEP }.toFloat() / n, median(steps.toList()), median(padding))
    }

    private fun median(v: List<Float>): Float = if (v.isEmpty()) Float.MAX_VALUE else v.sorted()[v.size / 2]

    /** Share of a vertical line at [x] (rows [y0] to [y1]) that shows an edge. */
    private fun lineCoverage(im: Images, x: Float, y0: Float, y1: Float): Float {
        var hits = 0; var n = 0
        var y = y0
        while (y < y1) {
            val a = at(im.luma, im.w, im.h, x - 1.5f, y); val b = at(im.luma, im.w, im.h, x + 1.5f, y)
            if (a.isNaN() || b.isNaN()) return 0f
            if (abs(a - b) >= EDGE_STEP) hits++
            n++; y += 2f
        }
        return if (n == 0) 0f else hits.toFloat() / n
    }

    /** Slot patterns of a row, left to right: (1), (2), (1, 1), (2, 1), ... up to [MAX_SLOTS] slots. */
    private val patterns: List<IntArray> = buildList {
        fun grow(prefix: List<Int>) {
            if (prefix.isNotEmpty()) add(prefix.toIntArray())
            for (n in 1..MAX_SLOTS) if (prefix.sum() + n <= MAX_SLOTS) grow(prefix + n)
        }
        grow(emptyList())
    }

    /**
     * The row of tiles under [clock] (comparison pixels) on [screen], as (rect, slots) left to right; empty
     * if there's none. Samsung packs the row from the clock's alignment edge (or around its middle), a fixed
     * gap below it; each tile must show its rounded outline all round and a smooth padding band inside, and
     * a tile spanning several slots must not show the gaps of separate tiles.
     */
    private fun find(
        screen: IntArray, w: Int, h: Int, clock: RectF, align: CoverClockLayout.Align,
    ): Pair<List<Pair<RectF, Int>>, Pair<Int, Look>?> {
        val im = images(screen, w, h)
        val unit = w / REFERENCE_WIDTH  // comparison pixels per cover pixel
        val slot = SLOT * unit; val gap = SLOT_GAP * unit; val r = CORNER * unit
        fun width(n: Int) = n * slot + (n - 1) * gap
        val cache = HashMap<Triple<Int, Int, Int>, Look?>()
        // The hypothesis nearest to a tile, for the log when none is one.
        var closest: Pair<Int, Look>? = null
        fun tileOk(x: Float, y: Float, n: Int): Look? {
            val key = Triple((x * 10).roundToInt(), (y * 10).roundToInt(), n)
            return cache.getOrPut(key) {
                val l = look(im, x, y, width(n), slot, r) ?: return@getOrPut null
                if (closest.let { it == null || l.coverage > it.second.coverage }) closest = n to l
                if (l.coverage < MIN_COVERAGE || l.step < MIN_STEP || l.padding > MAX_PADDING) return@getOrPut null
                for (k in 1 until n) {
                    val g0 = x + k * (slot + gap) - gap; val g1 = x + k * (slot + gap)
                    val split = min(lineCoverage(im, g0, y + r, y + slot - r), lineCoverage(im, g1, y + r, y + slot - r))
                    if (split >= SPLIT) return@getOrPut null
                }
                l
            }
        }
        val anchor0 = when (align) {
            CoverClockLayout.Align.LEFT -> clock.left
            CoverClockLayout.Align.RIGHT -> clock.right
            CoverClockLayout.Align.CENTER -> clock.centerX()
        }
        var best: List<Pair<Float, Int>>? = null
        var bestY = 0f; var bestKey = -1f
        val reach = (ANCHOR_REACH * unit).roundToInt()
        for (da in -reach..reach) for (dy in (ROW_GAP_MIN * unit).roundToInt()..(ROW_GAP_MAX * unit).roundToInt()) {
            val y = clock.bottom + dy
            for (pattern in patterns) {
                val total = pattern.sumOf { width(it).toDouble() }.toFloat() + gap * (pattern.size - 1)
                val anchor = anchor0 + da
                var x = when (align) {
                    CoverClockLayout.Align.LEFT -> anchor
                    CoverClockLayout.Align.RIGHT -> anchor - total
                    CoverClockLayout.Align.CENTER -> anchor - total / 2
                }
                val tiles = ArrayList<Pair<Float, Int>>()
                var worst = 1f
                for (n in pattern) {
                    val l = tileOk(x, y, n) ?: break
                    worst = min(worst, l.coverage)
                    tiles += x to n
                    x += width(n) + gap
                }
                if (tiles.size != pattern.size) continue
                // More slots first, then the clearest outlines.
                val key = pattern.sum() + worst
                if (key > bestKey) { bestKey = key; best = tiles; bestY = y }
            }
        }
        val row = best ?: return emptyList<Pair<RectF, Int>>() to closest
        // Settle the row on its sharpest outline (the search above is in whole pixels and favours coverage).
        var bestShift = 0f to 0f; var bestStep = -1f
        for (i in -6..6) for (j in -6..6) {
            val sx = i / 2f; val sy = j / 2f
            var s = 0f
            for ((x, n) in row) s += look(im, x + sx, bestY + sy, width(n), slot, r)?.step ?: -1000f
            if (s > bestStep) { bestStep = s; bestShift = sx to sy }
        }
        return row.map { (x, n) ->
            val left = x + bestShift.first; val top = bestY + bestShift.second
            RectF(left, top, left + width(n), top + slot) to n
        } to closest
    }

    /** The tile at [rect] on [screen], blurred past reading (its outline left out). */
    private fun replica(screen: IntArray, w: Int, h: Int, rect: RectF): Bitmap {
        val x0 = (ceil(rect.left).toInt() + 1).coerceIn(0, w - 1)
        val y0 = (ceil(rect.top).toInt() + 1).coerceIn(0, h - 1)
        val x1 = (floor(rect.right).toInt() - 1).coerceIn(x0 + 1, w)
        val y1 = (floor(rect.bottom).toInt() - 1).coerceIn(y0 + 1, h)
        val cw = x1 - x0; val ch = y1 - y0
        val r = FloatArray(cw * ch); val g = FloatArray(cw * ch); val b = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val c = screen[(y0 + y) * w + x0 + x]
            r[y * cw + x] = Color.red(c).toFloat(); g[y * cw + x] = Color.green(c).toFloat(); b[y * cw + x] = Color.blue(c).toFloat()
        }
        val radius = max(1, (BLUR * w / REFERENCE_WIDTH).roundToInt())
        for (channel in arrayOf(r, g, b)) repeat(3) {
            boxBlur(channel, cw, ch, radius, horizontal = true)
            boxBlur(channel, cw, ch, radius, horizontal = false)
        }
        val px = IntArray(cw * ch) { Color.rgb(r[it].roundToInt(), g[it].roundToInt(), b[it].roundToInt()) }
        return Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888)
    }

    /** One box-blur pass of [m] ([w] x [h]) along rows or columns, edges clamped. */
    private fun boxBlur(m: FloatArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val line = FloatArray(len)
        val span = 2 * radius + 1
        for (l in 0 until lines) {
            fun idx(i: Int) = if (horizontal) l * w + i else i * w + l
            for (i in 0 until len) line[i] = m[idx(i)]
            var sum = 0f
            for (k in -radius..radius) sum += line[k.coerceIn(0, len - 1)]
            for (i in 0 until len) {
                m[idx(i)] = sum / span
                sum += line[(i + radius + 1).coerceAtMost(len - 1)] - line[(i - radius).coerceAtLeast(0)]
            }
        }
    }
}
