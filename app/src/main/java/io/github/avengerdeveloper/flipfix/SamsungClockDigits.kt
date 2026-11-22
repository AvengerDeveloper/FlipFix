package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log

/**
 * The digits of Samsung's stacked ("big") cover clock. They are not a font: Samsung's AOD app stores each
 * digit as a path, `big_clock_num_<d>_vertices` plus `big_clock_num_<d>_control_points` (string arrays,
 * "x, y" pairs in a unit box, y up), drawn as a thick line with round caps. FlipFix loads them from
 * Samsung's app at runtime (nothing of Samsung's is bundled) and draws them the same way.
 *
 * Path format (One UI 9): n vertices make n-1 segments (the layered set: vertex pairs, one segment
 * each), each with two control points. Whether a segment is a line, a quadratic curve (first control
 * point) or a cubic one comes from Samsung's motion-clock data (`motion_clock/numeric_data/<set>.xml`,
 * `segment_list`: 0, 1, 2), not from the control points: some straight segments carry small ones
 * (the layered 9's tail, the layered 6's top). Without that file: both (0,0) = line, one = quadratic.
 *
 * Geometry, solved from the template's sample "12:45" (Z Flip8), in digit units:
 */
class SamsungClockDigits private constructor(
    private val paths: Array<Path>,
    val set: Set,
    /** Per digit, its strokes cut into short pieces with their own opacity (the layered set), or null. */
    private val shaded: Array<Pieces?>,
) {

    /**
     * A layered digit the way Samsung's lock-screen shader draws it: each stroke as [PIECES] short
     * lines (unit space, y down: x0, y0, x1, y1 each in [xy]) with their own opacity ([alpha]); where
     * pieces overlap, the more opaque one shows (the shader keeps the highest).
     */
    private class Pieces(val xy: FloatArray, val alpha: FloatArray, val bounds: RectF)

    /** A shaded digit drawn once at one size: its opacity, and where it sits from the digit's origin. */
    private class Mask(val bitmap: Bitmap, val left: Int, val top: Int)

    fun path(digit: Int): Path = paths[digit]

    /**
     * Union of the unit-space bounds of [digits] (y down, as drawn), measured on the curves themselves.
     * (Path.computeBounds ignores its "exact" flag since Android 11 and includes control points, which
     * made the digits come out ~9% too small.)
     */
    fun bounds(digits: String): RectF {
        val out = RectF()
        var first = true
        for (c in digits) {
            if (!c.isDigit()) continue
            val pts = paths[c - '0'].approximate(0.002f) // [fraction, x, y, fraction, x, y, ...]
            var i = 0
            while (i + 2 < pts.size) {
                val x = pts[i + 1]
                val y = pts[i + 2]
                if (first) { out.set(x, y, x, y); first = false } else out.union(x, y)
                i += 3
            }
        }
        return out
    }


    private val drawPath = Path()
    private val drawMatrix = Matrix()

    /**
     * Draws [text] (digits and ':') in Samsung's fixed slots, sized and placed like the template's [sample]
     * ("12:45", "12", "45") whose ink box has its left edge at [left], its bottom at [bottom] and is [height]
     * tall. Characters fill the sample's slots from the right (a one-digit hour leaves the first empty).
     * [stroke] gets the line width ([thickness], in digit units: Samsung's clock editor has a thickness
     * setting; [STROKE] is the default); colours and shaders are the caller's.
     * [shaded]: draw a layered digit's strokes with their own opacity, as Samsung's lock screen does
     * (in [stroke]'s colour or shader).
     */
    fun draw(
        canvas: Canvas, sample: String, text: String, left: Float, bottom: Float, height: Float,
        stroke: Paint, dot: Paint, thickness: Float = STROKE, shaded: Boolean = false,
    ) {
        val sb = bounds(sample)
        val s = height / (sb.height() + thickness)
        val w = thickness * s
        val centerY = bottom - sb.bottom * s - w / 2
        val firstDigit = sample.firstOrNull { it.isDigit() } ?: return
        val slots = FloatArray(sample.length)
        var x = left - bounds(firstDigit.toString()).left * s + w / 2
        sample.forEachIndexed { i, c ->
            if (i > 0) x += if (c == ':' || sample[i - 1] == ':') COLON_GAP * s else set.advance * s
            slots[i] = x
        }
        stroke.strokeWidth = w
        val chars = text.filter { it.isDigit() || it == ':' }
        val start = (slots.size - chars.length).coerceAtLeast(0)
        chars.forEachIndexed { i, c ->
            val sx = slots.getOrNull(start + i) ?: return@forEachIndexed
            if (c == ':') {
                val r = DOT_DIAMETER * s / 2
                canvas.drawCircle(sx, centerY - DOT_OFFSET * s, r, dot)
                canvas.drawCircle(sx, centerY + DOT_OFFSET * s, r, dot)
            } else {
                val pieces = if (shaded) this.shaded[c - '0'] else null
                if (pieces == null) {
                    drawMatrix.setScale(s, s)
                    drawMatrix.postTranslate(sx, centerY)
                    drawPath.set(path(c - '0'))
                    drawPath.transform(drawMatrix)
                    canvas.drawPath(drawPath, stroke)
                } else {
                    // Drawn on whole pixels, the quarter pixel left over in the mask itself.
                    val ox = kotlin.math.floor(sx); val oy = kotlin.math.floor(centerY)
                    val m = mask(c - '0', pieces, s, w, sx - ox, centerY - oy)
                    canvas.drawBitmap(m.bitmap, ox + m.left, oy + m.top, stroke)
                }
            }
        }
    }

    private val masks = HashMap<String, Mask>()

    /**
     * Digit [digit]'s [pieces] at scale [s] with line width [w] (pixels), its origin [fx], [fy] into a
     * pixel, as an opacity mask: per pixel the highest of the pieces' opacity times their coverage, as
     * Samsung's shader computes it (coverage fading out over the pixel at the line's edge).
     */
    private fun mask(digit: Int, pieces: Pieces, s: Float, w: Float, fx: Float, fy: Float): Mask {
        val qx = (fx * 4).toInt().coerceIn(0, 3); val qy = (fy * 4).toInt().coerceIn(0, 3)
        val key = "$digit/${(s * 10).toInt()}/${(w * 10).toInt()}/$qx/$qy"
        // (The face and the preset matcher draw from different threads.)
        synchronized(masks) { masks[key] }?.let { return it }
        val r = w / 2
        val dx = qx / 4f; val dy = qy / 4f
        val left = kotlin.math.floor(pieces.bounds.left * s + dx - r - 2).toInt()
        val top = kotlin.math.floor(pieces.bounds.top * s + dy - r - 2).toInt()
        val width = (kotlin.math.ceil(pieces.bounds.right * s + dx + r + 2).toInt() - left).coerceAtLeast(1)
        val height = (kotlin.math.ceil(pieces.bounds.bottom * s + dy + r + 2).toInt() - top).coerceAtLeast(1)
        val ink = FloatArray(width * height)
        val xy = pieces.xy
        for (p in pieces.alpha.indices) {
            val a = pieces.alpha[p]
            if (a <= 0f) continue
            val x0 = xy[4 * p] * s + dx - left; val y0 = xy[4 * p + 1] * s + dy - top
            val x1 = xy[4 * p + 2] * s + dx - left; val y1 = xy[4 * p + 3] * s + dy - top
            val bx = x1 - x0; val by = y1 - y0
            val bb = bx * bx + by * by
            val xa = maxOf(0, (minOf(x0, x1) - r - 1).toInt()); val xb = minOf(width - 1, (maxOf(x0, x1) + r + 1).toInt())
            val ya = maxOf(0, (minOf(y0, y1) - r - 1).toInt()); val yb = minOf(height - 1, (maxOf(y0, y1) + r + 1).toInt())
            for (y in ya..yb) for (x in xa..xb) {
                val px = x + 0.5f - x0; val py = y + 0.5f - y0
                val t = if (bb == 0f) 0f else ((px * bx + py * by) / bb).coerceIn(0f, 1f)
                val ex = px - bx * t; val ey = py - by * t
                // Coverage: whole inside the line, none a pixel outside, the line's edge half way.
                val e = kotlin.math.sqrt(ex * ex + ey * ey) - r + 0.5f
                if (e >= 1f) continue
                val v = a * (if (e <= 0f) 1f else 1f - e * e * (3f - 2f * e))
                val i = y * width + x
                if (v > ink[i]) ink[i] = v
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val row = bitmap.rowBytes
        val bytes = ByteArray(bitmap.byteCount)
        for (y in 0 until height) for (x in 0 until width) {
            bytes[y * row + x] = (ink[y * width + x] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        return Mask(bitmap, left, top).also {
            synchronized(masks) {
                // (Not recycled: a drawn frame may still hold them.)
                if (masks.size >= MAX_MASKS) masks.clear()
                masks[key] = it
            }
        }
    }

    /**
     * Samsung's path-drawn digit sets: [BIG] (the default stacked clock, one stroke per digit) and
     * [LAYERED] (ribbon-like strokes, each segment its own line: vertices come in start/end pairs).
     * [advance] is the distance between digit origins (fixed slots), in digit units.
     */
    enum class Set(val prefix: String, val advance: Float, val pairedVertices: Boolean) {
        BIG("big_clock", 0.50f, false),
        LAYERED("layered_clock", 0.56f, true),
    }

    companion object {
        const val STROKE = 0.1438f      // line thickness
        const val COLON_GAP = 0.365f    // digit origin to colon centre, each side
        const val DOT_OFFSET = 0.1436f  // colon dots above/below the digits' centre
        const val DOT_DIAMETER = 0.161f

        private const val AOD_PACKAGE = "com.samsung.android.app.aodservice"

        private val cached = java.util.concurrent.ConcurrentHashMap<Set, SamsungClockDigits>()

        /** Pieces per stroke of a shaded digit (as Samsung's shader cuts them). */
        private const val PIECES = 60

        /** Shaded digits kept drawn (digit x size x quarter-pixel offset). */
        private const val MAX_MASKS = 48

        fun load(context: Context, set: Set = Set.BIG): SamsungClockDigits? = cached[set] ?: runCatching {
            val aod = context.createPackageContext(AOD_PACKAGE, 0)
            val res = aod.resources
            val glyphs = runCatching { glyphs(aod, set) }
                .onFailure { Log.w(CoverState.TAG, "Samsung clock digit kinds unavailable: $set", it) }
                .getOrNull().orEmpty()
            val shaded = arrayOfNulls<Pieces>(10)
            val paths = Array(10) { d ->
                fun arr(kind: String): Array<String> {
                    val id = res.getIdentifier("${set.prefix}_num_${d}_$kind", "array", AOD_PACKAGE)
                    require(id != 0) { "missing ${set.prefix}_num_${d}_$kind" }
                    return res.getStringArray(id)
                }
                val vertices = arr("vertices").map(::values)
                val controls = arr("control_points").map(::values)
                val segments = segments(vertices, controls, set.pairedVertices, glyphs[d]?.kinds)
                if (set.pairedVertices) shaded[d] = runCatching { pieces(segments, glyphs[d]?.groups) }.getOrNull()
                Path().apply {
                    segments.forEachIndexed { i, seg -> if (set.pairedVertices || i == 0) moveTo(seg.ax, seg.ay); seg.addTo(this) }
                }
            }
            SamsungClockDigits(paths, set, shaded)
        }.onFailure { Log.w(CoverState.TAG, "Samsung clock digits unavailable: $set", it) }
            .getOrNull()?.also { cached[set] = it }

        /** "x, y" (control points may carry extra values: the layered set's opacity, see [pieces]). */
        private fun values(s: String): List<Float> = s.split(',').map { it.trim().toFloat() }

        /** One digit's strokes as Samsung's data file lists them: [kinds] ('0' line, '1', '2'), [groups] (seg_div). */
        private class Glyph(val kinds: String, val groups: List<Int>)

        /** Samsung's motion-clock data for [set], per digit (see the class comment). */
        private fun glyphs(aod: Context, set: Set): Map<Int, Glyph> {
            val out = HashMap<Int, Glyph>()
            aod.assets.open("motion_clock/numeric_data/${set.prefix}.xml").use { input ->
                val xml = android.util.Xml.newPullParser().apply { setInput(input, null) }
                val fields = HashMap<String, String>()
                var tag = ""
                while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (xml.eventType) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> { tag = xml.name; if (tag == "numeral") fields.clear() }
                        org.xmlpull.v1.XmlPullParser.TEXT -> if (xml.text.isNotBlank()) fields[tag] = xml.text.trim()
                        org.xmlpull.v1.XmlPullParser.END_TAG -> if (xml.name == "numeral") {
                            val d = fields["number"]?.toIntOrNull()
                            val kinds = fields["segment_list"]
                            if (d != null && kinds != null) {
                                out[d] = Glyph(kinds, fields["seg_div"].orEmpty().map { it - '0' })
                            }
                        }
                    }
                }
            }
            return out
        }

        /**
         * One stroke, from (ax, ay) to (bx, by) (unit space, y down), a line, quadratic curve (through c1)
         * or cubic one ([kind] '0', '1', '2'). [alphas]: its opacity stops (two or three, see [pieces]).
         */
        private class Segment(
            val ax: Float, val ay: Float, val bx: Float, val by: Float,
            val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float,
            val kind: Char, val alphas: FloatArray,
        ) {
            fun addTo(path: Path) = when (kind) {
                '0' -> path.lineTo(bx, by)
                '1' -> path.quadTo(c1x, c1y, bx, by)
                else -> path.cubicTo(c1x, c1y, c2x, c2y, bx, by)
            }

            fun at(t: Float, out: FloatArray) {
                val u = 1f - t
                when (kind) {
                    '0' -> { out[0] = ax + (bx - ax) * t; out[1] = ay + (by - ay) * t }
                    '1' -> { out[0] = u * u * ax + 2 * u * t * c1x + t * t * bx; out[1] = u * u * ay + 2 * u * t * c1y + t * t * by }
                    else -> {
                        out[0] = u * u * u * ax + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * bx
                        out[1] = u * u * u * ay + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * by
                    }
                }
            }

            /** Length the way Samsung reckons it: the chord, or the mean of chord and control polygon. */
            fun length(): Float {
                fun d(x0: Float, y0: Float, x1: Float, y1: Float) = kotlin.math.hypot(x1 - x0, y1 - y0)
                val chord = d(ax, ay, bx, by)
                return when (kind) {
                    '0' -> chord
                    '1' -> (chord + d(ax, ay, c1x, c1y) + d(c1x, c1y, bx, by)) / 2
                    else -> (chord + d(ax, ay, c1x, c1y) + d(c1x, c1y, c2x, c2y) + d(c2x, c2y, bx, by)) / 2
                }
            }
        }

        /**
         * Chained vertices (n vertices, n-1 segments) or, if [paired], separate segments (vertex 2i to
         * 2i+1). Segment i has control points 2i and 2i+1, its kind from [kinds] (else guessed from which
         * control points are set), its opacity stops after the first control point's x and y.
         */
        private fun segments(
            vertices: List<List<Float>>, controls: List<List<Float>>, paired: Boolean, kinds: String?,
        ): List<Segment> {
            fun zero(p: List<Float>?) = p == null || (p[0] == 0f && p[1] == 0f)
            val count = if (paired) vertices.size / 2 else vertices.size - 1
            return (0 until count).map { i ->
                val a = if (paired) vertices[2 * i] else vertices[i]
                val b = if (paired) vertices[2 * i + 1] else vertices[i + 1]
                var c1 = controls.getOrNull(2 * i)
                val c2 = controls.getOrNull(2 * i + 1)
                val kind = kinds?.getOrNull(i) ?: when {
                    zero(c1) && zero(c2) -> '0'
                    zero(c1) -> { c1 = c2; '1' }
                    zero(c2) -> '1'
                    else -> '2'
                }
                // y is up in Samsung's data, down on screen.
                Segment(
                    a[0], -a[1], b[0], -b[1],
                    c1?.get(0) ?: 0f, -(c1?.get(1) ?: 0f), c2?.get(0) ?: 0f, -(c2?.get(1) ?: 0f),
                    kind, controls.getOrNull(2 * i)?.drop(2)?.toFloatArray() ?: FloatArray(0),
                )
            }
        }

        /**
         * The layered set's strokes cut into pieces with their opacity, as Samsung's lock-screen shader
         * draws them. The strokes come in [groups] (seg_div: "22" = strokes 1-2 and 3-4); along a group,
         * by length, runs 0 to 1, and each stroke's opacity follows its stops over its part of that run:
         * two stops from first to second, three through the middle one at 0.5 ("0.10, 1.00, 0.10" fades
         * in and out again). The shader then lifts every opacity to the power 0.7. Null if a stroke has
         * no stops.
         */
        private fun pieces(segments: List<Segment>, groups: List<Int>?): Pieces? {
            if (segments.any { it.alphas.size < 2 }) return null
            val sizes = groups?.takeIf { it.sum() == segments.size && it.all { n -> n > 0 } } ?: List(segments.size) { 1 }
            val from = FloatArray(segments.size)
            val to = FloatArray(segments.size)
            var first = 0
            for (n in sizes) {
                val lengths = (first until first + n).map { segments[it].length() }
                val total = lengths.sum().takeIf { it > 0f } ?: 1f
                var run = 0f
                for (k in 0 until n) {
                    from[first + k] = run / total
                    run += lengths[k]
                    to[first + k] = run / total
                }
                first += n
            }
            val xy = FloatArray(segments.size * PIECES * 4)
            val alpha = FloatArray(segments.size * PIECES)
            val p = FloatArray(2)
            segments.forEachIndexed { i, seg ->
                seg.at(0f, p)
                for (k in 1..PIECES) {
                    val j = i * PIECES + k - 1
                    xy[4 * j] = p[0]; xy[4 * j + 1] = p[1]
                    val t = k / PIECES.toFloat()
                    seg.at(t, p)
                    xy[4 * j + 2] = p[0]; xy[4 * j + 3] = p[1]
                    val run = from[i] + t * (to[i] - from[i])
                    val st = seg.alphas
                    val a = when {
                        st.size >= 3 && run < 0.5f -> st[0] + (st[1] - st[0]) * run * 2
                        st.size >= 3 -> st[1] + (st[2] - st[1]) * (run * 2 - 1)
                        else -> st[0] + (st[1] - st[0]) * run
                    }
                    alpha[j] = Math.pow(kotlin.math.abs(a).toDouble(), 0.7).toFloat().coerceIn(0f, 1f)
                }
            }
            val xs = xy.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = xy.filterIndexed { i, _ -> i % 2 == 1 }
            return Pieces(xy, alpha, RectF(xs.min(), ys.min(), xs.max(), ys.max()))
        }
    }
}
