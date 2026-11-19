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
 * The tiles are found on the snapshot the preset check takes ([CoverFaceMatcher]); only each widget's marks
 * are kept, blurred, in memory only, never stored: after a restart they come back with the next check. The
 * face shows them on glass ([CoverWidgetGlass]): the moving cover video behind, blurred as Samsung does.
 */
object CoverWidgets {

    /**
     * A widget tile: where it is on the cover (fractions) and its marks, blurred, see-through around them.
     */
    class Tile(val left: Float, val top: Float, val right: Float, val bottom: Float, val slots: Int, val image: Bitmap)

    /**
     * What the check of preset switch [stamp] saw: the matched preset's [folder] ("" if none) and its
     * [tiles]; [looks]: snapshots looked at for them.
     */
    class Row(val stamp: String, val folder: String, val tiles: List<Tile>, val looks: Int = 1) {
        val slots get() = tiles.sumOf { it.slots }
        fun looked() = Row(stamp, folder, tiles, looks + 1)
    }

    @Volatile
    var row: Row? = null
        private set

    // Samsung's row on the 948-pixel-wide cover, in cover pixels (scaled to other widths).
    private const val REFERENCE_WIDTH = 948f
    private const val SLOT = 141f
    private const val SLOT_GAP = 30f
    private const val CORNER = 46f

    /** The row's edges are looked for this far (cover pixels) around where the search put them. */
    private const val EDGE_REACH = 6f

    /** The row's top this far below the clock's ink (Samsung: 45). */
    private const val ROW_GAP_MIN = 36f
    private const val ROW_GAP_MAX = 56f

    /** The row starts this close to the clock's edge (left/right) or middle. */
    private const val ANCHOR_REACH = 8f

    /** Blur of a widget's marks: three box passes of this radius (cover pixels), close to a 13-pixel Gaussian. */
    private const val BLUR = 12f

    /** A widget's panel is fitted on this band in from its edge (cover pixels), where Samsung draws no marks. */
    private const val PANEL_BAND_FROM = 3f
    private const val PANEL_BAND_TO = 12f

    /** Luma this far from the panel counts as a mark (less is the panel's own unevenness). */
    private const val MARK_MIN_STEP = 6f

    /** Luma step across an outline point that counts as an edge there. */
    private const val EDGE_STEP = 3f

    /** A tile's outline shows all round (share of its points), sharply (median step)... */
    private const val MIN_COVERAGE = 0.85f
    private const val MIN_STEP = 8f

    /** ...around a smooth padding band (Samsung blurs the wallpaper behind a widget; median gradient). */
    private const val MAX_PADDING = 1.5f

    /**
     * A wide tile whose slot gaps show edges like this (share of each gap side; real gaps 0.84+, slot
     * boundaries inside a widget 0.47 at most) is two tiles. The gap is looked for this far (cover pixels)
     * either side of where the hypothesis puts it.
     */
    private const val SPLIT = 0.8f
    private const val SPLIT_REACH = 4f

    private const val MAX_SLOTS = 4

    /**
     * Snapshots a preset check may look at for widgets: Samsung fades them in after the clock, so the first
     * snapshot (taken early, to know the clock before a quick unlock) can miss them, and a widget's edge can
     * melt into the video behind it for a moment.
     */
    private const val MAX_LOOKS = 3

    /**
     * Whether another snapshot is worth taking for [stamp]: its preset matched, but its row isn't full (no
     * widgets showed yet, or some may have been missed). Only after a preset switch: a few per switch.
     */
    fun wantsAnotherLook(stamp: String): Boolean {
        val r = row ?: return false
        return r.stamp == stamp && r.folder.isNotEmpty() && r.slots < MAX_SLOTS && r.looks < MAX_LOOKS
    }

    /**
     * A snapshot for [stamp] that couldn't be looked at for widgets (its clock didn't match, though [folder]
     * had matched before): counts as a look, so the checks for widgets stay a few per switch.
     */
    fun missedLook(stamp: String, folder: String) {
        val r = row
        row = if (r != null && r.stamp == stamp && r.folder == folder) r.looked() else Row(stamp, folder, emptyList())
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
     * matched). Of the snapshots looked at for [stamp], the one that saw the most of the row wins, and on a
     * tie the latest: Samsung fades the widgets in, so an earlier one may catch them part-way (paler, and
     * showing the video through their panels), and one may miss a tile whose edge melted into the video.
     * (A snapshot that finishes after the cover unlocks is dropped before it gets here.) Returns a note for
     * the log.
     */
    fun look(
        stamp: String, folder: String?, screen: IntArray, w: Int, h: Int,
        clock: RectF?, align: CoverClockLayout.Align?,
    ): String {
        if (folder == null || clock == null) {
            row = Row(stamp, "", emptyList())
            return ""
        }
        val prev = row?.takeIf { it.stamp == stamp && it.folder == folder }
        val (found, closest) = find(screen, w, h, clock, align ?: alignOf(clock, w))
        val tiles = found.map { (rect, slots) ->
            // find() measures in pixel indices (a pixel's value sits at its centre); the cover is drawn in
            // pixel edges, half a snapshot pixel further on (1 cover pixel at the comparison scale).
            Tile(
                (rect.left + 0.5f) / w, (rect.top + 0.5f) / h, (rect.right + 0.5f) / w, (rect.bottom + 0.5f) / h,
                slots, replica(screen, w, h, rect),
            )
        }
        val fresh = Row(stamp, folder, tiles, (prev?.looks ?: 0) + 1)
        if (prev != null && fresh.slots < prev.slots) {
            row = prev.looked()
            return ""
        }
        row = fresh
        if (tiles.isNotEmpty()) return ", widgets " + tiles.joinToString("+") { "${it.slots}x1" }
        return ", no widgets" + (closest?.let { (slots, l) ->
            " (closest ${slots}x1: outline %d%%, edge %.1f, padding %.1f)".format((l.coverage * 100).roundToInt(), l.step, l.padding)
        } ?: "")
    }

    /**
     * Draws [tiles] on a [w] x [h] cover drawn at ([offX], [offY]) as tinted see-through panes (no live blur:
     * for the edge-peek card, and where [CoverWidgetGlass] can't use Samsung's blur).
     */
    fun draw(canvas: Canvas, tiles: List<Tile>, w: Float, h: Float, offX: Float, offY: Float) {
        for (t in tiles) {
            drawTile(canvas, t, RectF(t.left * w - offX, t.top * h - offY, t.right * w - offX, t.bottom * h - offY), frosted = false)
        }
    }

    /**
     * Draws [tile] at [rect]: its marks, on Samsung's live blur if [frosted] (as is: the same blur preset
     * matches Samsung's panels better than any tint over it, see [seeThroughTint]), else over a tint of the
     * cover behind it.
     */
    fun drawTile(canvas: Canvas, tile: Tile, rect: RectF, frosted: Boolean) {
        val corner = corner(rect.height())
        if (!frosted) canvas.drawRoundRect(rect, corner, corner, seeThroughTint)
        val matrix = Matrix().apply {
            setScale(rect.width() / tile.image.width, rect.height() / tile.image.height)
            postTranslate(rect.left, rect.top)
        }
        paint.shader = BitmapShader(tile.image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.shader = null
    }

    /** A tile's corner radius at [height] (Samsung's 46 px on a 141 px slot). */
    fun corner(height: Float) = height * CORNER / SLOT

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * With nothing blurring the cover behind it: about what Samsung's dark background blur does to the
     * cover's middle tones (it squeezes them into 34-175), minus the blur.
     *
     * Over Samsung's blur no tint is drawn. Samsung's panels swing by up to 40 levels within 2 s as the
     * video moves, while the blur alone tracks them as closely as can be measured (against the video around
     * it, 0.94 of it vs Samsung's 0.93, colour cast within 1-2 levels). Every tint tried came out lighter:
     * each widget's own colour at 50-60%, and a faint fixed blue at 15%.
     */
    private val seeThroughTint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(128, 62, 70, 86) }

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
        val splitReach = max(1, (SPLIT_REACH * unit).roundToInt())
        // The hypothesis nearest to a tile, for the log when none is one.
        var closest: Pair<Int, Look>? = null
        fun tileOk(x: Float, y: Float, n: Int): Look? {
            val key = Triple((x * 10).roundToInt(), (y * 10).roundToInt(), n)
            return cache.getOrPut(key) {
                val l = look(im, x, y, width(n), slot, r) ?: return@getOrPut null
                if (closest.let { it == null || l.coverage > it.second.coverage }) closest = n to l
                if (l.coverage < MIN_COVERAGE || l.step < MIN_STEP || l.padding > MAX_PADDING) return@getOrPut null
                for (k in 1 until n) {
                    // Looked for a little around where the gap would be: this hypothesis may sit a pixel or
                    // two off the real tiles and still pass (a 2x1 and a 1x1 read as one 3x1 otherwise).
                    for (s in -splitReach..splitReach) {
                        val g0 = x + k * (slot + gap) - gap + s; val g1 = g0 + gap
                        val split = min(lineCoverage(im, g0, y + r, y + slot - r), lineCoverage(im, g1, y + r, y + slot - r))
                        if (split >= SPLIT) return@getOrPut null
                    }
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
        // Settle the row on its edges, to a fraction of a pixel (the search above is in whole pixels and
        // favours coverage): where the luma step across the first tile's left side and across the tiles'
        // tops peaks. On every capture this lands within half a pixel of Samsung's edges.
        val edgeReach = EDGE_REACH * unit
        val x0 = row.first().first
        val left = edgePeak(im, vertical = true, line = x0, spans = listOf(bestY + r to bestY + slot - r), reach = edgeReach)
        val top = edgePeak(im, vertical = false, line = bestY, spans = row.map { (x, n) -> x + r to x + width(n) - r }, reach = edgeReach)
        return row.map { (x, n) ->
            val tileLeft = x + left - x0
            RectF(tileLeft, top, tileLeft + width(n), top + slot) to n
        } to closest
    }

    /**
     * Where the luma step across a straight tile side peaks, to a fraction of a pixel: the side lies at
     * [line] (x for a [vertical] side, else y), along [spans]; looked for within [reach] of [line], which is
     * kept if the step peaks at neither.
     */
    private fun edgePeak(im: Images, vertical: Boolean, line: Float, spans: List<Pair<Float, Float>>, reach: Float): Float {
        val step = 0.5f
        val n = (2 * reach / step).roundToInt() + 1
        val profile = FloatArray(n)
        for (i in 0 until n) {
            val o = line - reach + i * step
            var sum = 0f; var count = 0
            for ((from, to) in spans) {
                var t = from
                while (t < to) {
                    val p = if (vertical) at(im.luma, im.w, im.h, o - 1, t) else at(im.luma, im.w, im.h, t, o - 1)
                    val q = if (vertical) at(im.luma, im.w, im.h, o + 1, t) else at(im.luma, im.w, im.h, t, o + 1)
                    if (!p.isNaN() && !q.isNaN()) { sum += abs(p - q); count++ }
                    t += 1f
                }
            }
            profile[i] = if (count == 0) 0f else sum / count
        }
        val i = profile.indices.maxBy { profile[it] }
        if (i == 0 || i == n - 1) return line
        val a = profile[i - 1]; val b = profile[i]; val c = profile[i + 1]
        val d = a - 2 * b + c
        val sub = if (d != 0f) 0.5f * (a - c) / d else 0f
        return line - reach + (i + sub) * step
    }

    /**
     * The widget at [rect] on [screen] without its panel: only its own marks (text, icons, rings), lifted off
     * the panel and blurred past reading, see-through elsewhere, so the live cover shows around them. The
     * panel (Samsung's blur of the wallpaper behind it, which holds no marks near its edges) is taken as a
     * smooth fit of that edge band; a mark is how far a pixel stands out from it, as opacity.
     */
    private fun replica(screen: IntArray, w: Int, h: Int, rect: RectF): Bitmap {
        val x0 = (ceil(rect.left).toInt() + 1).coerceIn(0, w - 1)
        val y0 = (ceil(rect.top).toInt() + 1).coerceIn(0, h - 1)
        val x1 = (floor(rect.right).toInt() - 1).coerceIn(x0 + 1, w)
        val y1 = (floor(rect.bottom).toInt() - 1).coerceIn(y0 + 1, h)
        val cw = x1 - x0; val ch = y1 - y0; val n = cw * ch
        val rgb = Array(3) { FloatArray(n) }
        for (y in 0 until ch) for (x in 0 until cw) {
            val c = screen[(y0 + y) * w + x0 + x]
            rgb[0][y * cw + x] = Color.red(c).toFloat(); rgb[1][y * cw + x] = Color.green(c).toFloat(); rgb[2][y * cw + x] = Color.blue(c).toFloat()
        }
        val unit = w / REFERENCE_WIDTH
        val panel = panelFit(rgb, cw, ch, max(1, (PANEL_BAND_FROM * unit).roundToInt()), max(2, (PANEL_BAND_TO * unit).roundToInt()))
        // Opacity from how far the luma stands out (up to white or down to black); premultiplied colour.
        val alpha = FloatArray(n)
        val marks = Array(3) { FloatArray(n) }
        for (i in 0 until n) {
            val t = (rgb[0][i] * 3 + rgb[1][i] * 6 + rgb[2][i]) / 10f
            val p = (panel[0][i] * 3 + panel[1][i] * 6 + panel[2][i]) / 10f
            val d = t - p
            if (abs(d) < MARK_MIN_STEP) continue
            val a = (if (d > 0) d / max(1f, 255f - p) else -d / max(1f, p)).coerceIn(0f, 1f)
            alpha[i] = a
            for (c in 0..2) marks[c][i] = (rgb[c][i] - (1 - a) * panel[c][i]).coerceIn(0f, a * 255f)
        }
        val radius = max(1, (BLUR * unit).roundToInt())
        for (channel in arrayOf(alpha, marks[0], marks[1], marks[2])) repeat(3) {
            boxBlur(channel, cw, ch, radius, horizontal = true)
            boxBlur(channel, cw, ch, radius, horizontal = false)
        }
        val px = IntArray(n) {
            val a = alpha[it]
            if (a < 1f / 255f) 0 else Color.argb(
                (a * 255).roundToInt(),
                (marks[0][it] / a).roundToInt().coerceIn(0, 255),
                (marks[1][it] / a).roundToInt().coerceIn(0, 255),
                (marks[2][it] / a).roundToInt().coerceIn(0, 255),
            )
        }
        return Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888)
    }

    /**
     * A smooth fit (quadratic in x and y, per channel) of a [cw] x [ch] tile's panel, from its pixels
     * [from] to [to] in from its edge.
     */
    private fun panelFit(rgb: Array<FloatArray>, cw: Int, ch: Int, from: Int, to: Int): Array<FloatArray> {
        fun basis(x: Int, y: Int, out: DoubleArray) {
            val bx = x.toDouble() / cw - 0.5; val by = y.toDouble() / ch - 0.5
            out[0] = 1.0; out[1] = bx; out[2] = by; out[3] = bx * bx; out[4] = by * by; out[5] = bx * by
        }
        val k = 6
        val ata = Array(k) { DoubleArray(k) }
        val atb = Array(3) { DoubleArray(k) }
        val v = DoubleArray(k)
        for (y in 0 until ch) for (x in 0 until cw) {
            val d = minOf(minOf(x, y), minOf(cw - 1 - x, ch - 1 - y))
            if (d < from || d > to) continue
            basis(x, y, v)
            for (i in 0 until k) {
                for (j in 0 until k) ata[i][j] += v[i] * v[j]
                for (c in 0..2) atb[c][i] += v[i] * rgb[c][y * cw + x]
            }
        }
        val coef = Array(3) { c -> solve(Array(k) { ata[it].copyOf() }, atb[c].copyOf()) }
        val out = Array(3) { FloatArray(cw * ch) }
        for (y in 0 until ch) for (x in 0 until cw) {
            basis(x, y, v)
            for (c in 0..2) {
                val cf = coef[c]
                // Without a fit (no band: a tiny tile), the panel is taken as the tile itself: no marks.
                out[c][y * cw + x] = if (cf == null) rgb[c][y * cw + x] else (0 until k).sumOf { v[it] * cf[it] }.toFloat()
            }
        }
        return out
    }

    /** Solves [a] x = [b] (Gaussian elimination, partial pivoting); null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            val p = (col until n).maxBy { abs(a[it][col]) }
            if (abs(a[p][col]) < 1e-9) return null
            a[col] = a[p].also { a[p] = a[col] }; b[col] = b[p].also { b[p] = b[col] }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
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
