package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log

/**
 * The digits of Samsung's stacked ("big") cover clock. They are not a font: Samsung's AOD app stores each
 * digit as a path, `big_clock_num_<d>_vertices` plus `big_clock_num_<d>_control_points` (string arrays,
 * "x, y" pairs in a unit box, y up), drawn as a thick line with round caps. FlipFix loads them from
 * Samsung's app at runtime (nothing of Samsung's is bundled) and draws them the same way.
 *
 * Path format (decoded on One UI 9): n vertices make n-1 segments, each with two control points.
 * Both (0,0) = straight line; only one set = quadratic curve; both set = cubic curve.
 *
 * Geometry, solved from the template's sample "12:45" (Z Flip8), in digit units:
 */
class SamsungClockDigits private constructor(
    private val paths: Array<Path>,
    val set: Set,
    /** Per digit, its strokes with their opacity along the stroke (the layered set), or null. */
    private val shaded: Array<List<Segment>?>,
) {

    /**
     * One stroke of a layered digit, from ([x0], [y0]) to ([x1], [y1]) (unit space, y down). Its opacity
     * runs through [alphas], spread evenly from [from] to [to] along the stroke (flat outside): Samsung's
     * lock screen draws e.g. a 1's stem fading in from 10% under its flag to solid at its foot.
     */
    private class Segment(
        val path: Path, val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val alphas: FloatArray, val from: Float, val to: Float,
    )

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
     * [shade]: draw a layered digit's strokes with their own opacity, in [shade]'s colour, fading from
     * [shadeTopAlpha] at the top of the ink to opaque at its bottom (stroke's shader is left as it was).
     */
    fun draw(
        canvas: Canvas, sample: String, text: String, left: Float, bottom: Float, height: Float,
        stroke: Paint, dot: Paint, thickness: Float = STROKE, shade: Int? = null, shadeTopAlpha: Float = 1f,
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
                drawMatrix.setScale(s, s)
                drawMatrix.postTranslate(sx, centerY)
                val segments = if (shade != null) shaded[c - '0'] else null
                if (segments == null) {
                    drawPath.set(path(c - '0'))
                    drawPath.transform(drawMatrix)
                    canvas.drawPath(drawPath, stroke)
                } else {
                    drawShaded(canvas, segments, sx, centerY, s, bottom, height, stroke, shade!!, shadeTopAlpha)
                }
            }
        }
    }

    private val ends = FloatArray(4)

    private fun drawShaded(
        canvas: Canvas, segments: List<Segment>, sx: Float, cy: Float, s: Float, bottom: Float, height: Float,
        stroke: Paint, color: Int, topAlpha: Float,
    ) {
        val saved = stroke.shader
        for (seg in segments) {
            ends[0] = sx + seg.x0 * s; ends[1] = cy + seg.y0 * s
            ends[2] = sx + seg.x1 * s; ends[3] = cy + seg.y1 * s
            // The ink's own fade (top to bottom), taken at the stroke's middle.
            val mid = ((ends[1] + ends[3]) / 2 - (bottom - height)) / height
            val fade = topAlpha + (1f - topAlpha) * mid.coerceIn(0f, 1f)
            val n = seg.alphas.size
            val colors = IntArray(n + 2)
            val stops = FloatArray(n + 2)
            for (k in 0 until n) {
                stops[k + 1] = if (n == 1) seg.from else seg.from + (seg.to - seg.from) * k / (n - 1)
                colors[k + 1] = withAlpha(color, seg.alphas[k] * fade)
            }
            stops[0] = 0f; colors[0] = colors[1]
            stops[n + 1] = 1f; colors[n + 1] = colors[n]
            for (k in 1..n + 1) stops[k] = stops[k].coerceIn(stops[k - 1], 1f)
            // (A dot-sized stroke has no direction: one flat colour.)
            if (kotlin.math.hypot(ends[2] - ends[0], ends[3] - ends[1]) < 1f) {
                ends[2] = ends[0] + 1f; ends[3] = ends[1]; colors.fill(colors[1])
            }
            stroke.shader = LinearGradient(ends[0], ends[1], ends[2], ends[3], colors, stops, Shader.TileMode.CLAMP)
            drawPath.set(seg.path)
            drawPath.transform(drawMatrix)
            canvas.drawPath(drawPath, stroke)
        }
        stroke.shader = saved
    }

    private fun withAlpha(color: Int, alpha: Float) =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * (color ushr 24) + 0.5f).toInt() shl 24)

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

        fun load(context: Context, set: Set = Set.BIG): SamsungClockDigits? = cached[set] ?: runCatching {
            val res = context.createPackageContext(AOD_PACKAGE, 0).resources
            val shaded = arrayOfNulls<List<Segment>>(10)
            val paths = Array(10) { d ->
                fun arr(kind: String): Array<String> {
                    val id = res.getIdentifier("${set.prefix}_num_${d}_$kind", "array", AOD_PACKAGE)
                    require(id != 0) { "missing ${set.prefix}_num_${d}_$kind" }
                    return res.getStringArray(id)
                }
                val vertices = arr("vertices").map(::values)
                val controls = arr("control_points").map(::values)
                if (set.pairedVertices) shaded[d] = runCatching { shadedSegments(vertices, controls) }.getOrNull()
                buildPath(vertices.map(::point), controls.map(::point), set.pairedVertices)
            }
            SamsungClockDigits(paths, set, shaded)
        }.onFailure { Log.w(CoverState.TAG, "Samsung clock digits unavailable: $set", it) }
            .getOrNull()?.also { cached[set] = it }

        /** "x, y" (control points may carry extra values: the layered set's shading, see [shadedSegments]). */
        private fun values(s: String): List<Float> = s.split(',').map { it.trim().toFloat() }

        private fun point(v: List<Float>): Pair<Float, Float> = v[0] to v[1]

        /**
         * The layered set's strokes one by one, with their shading. Stroke i's first control point carries
         * its opacity stops after x and y ("1.00, 1.00" solid, "0.10, 1.00" fading in, "1.00, 0.40, 1.00"
         * dipping in the middle), its second control point where along the stroke they start and end
         * ("0.00, 1.00"). Null if a stroke has no shading.
         */
        private fun shadedSegments(vertices: List<List<Float>>, controls: List<List<Float>>): List<Segment>? =
            (0 until vertices.size / 2).map { i ->
                val c1 = controls[2 * i]
                val c2 = controls[2 * i + 1]
                if (c1.size < 4 || c2.size < 4) return null
                val start = vertices[2 * i]
                val end = vertices[2 * i + 1]
                val path = buildPath(listOf(start, end).map(::point), listOf(c1, c2).map(::point), paired = true)
                Segment(path, start[0], -start[1], end[0], -end[1], c1.drop(2).toFloatArray(), c2[2], c2[3])
            }

        /**
         * Chained vertices (n vertices, n-1 segments) or, if [paired], separate segments (vertex 2i to
         * 2i+1). Segment i has control points 2i and 2i+1.
         */
        private fun buildPath(vertices: List<Pair<Float, Float>>, controls: List<Pair<Float, Float>>, paired: Boolean): Path {
            fun zero(p: Pair<Float, Float>) = p.first == 0f && p.second == 0f
            val path = Path()
            val segments = if (paired) vertices.size / 2 else vertices.size - 1
            for (i in 0 until segments) {
                val start = if (paired) vertices[2 * i] else vertices[i]
                val end = if (paired) vertices[2 * i + 1] else vertices[i + 1]
                // y is up in Samsung's data, down on screen.
                if (paired || i == 0) path.moveTo(start.first, -start.second)
                val c1 = controls.getOrNull(2 * i) ?: (0f to 0f)
                val c2 = controls.getOrNull(2 * i + 1) ?: (0f to 0f)
                when {
                    zero(c1) && zero(c2) -> path.lineTo(end.first, -end.second)
                    zero(c2) -> path.quadTo(c1.first, -c1.second, end.first, -end.second)
                    zero(c1) -> path.quadTo(c2.first, -c2.second, end.first, -end.second)
                    else -> path.cubicTo(c1.first, -c1.second, c2.first, -c2.second, end.first, -end.second)
                }
            }
            return path
        }
    }
}
