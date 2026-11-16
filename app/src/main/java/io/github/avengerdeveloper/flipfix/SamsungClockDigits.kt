package io.github.avengerdeveloper.flipfix

import android.content.Context
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
 * Path format (decoded on One UI 9): n vertices make n-1 segments, each with two control points.
 * Both (0,0) = straight line; only one set = quadratic curve; both set = cubic curve.
 *
 * Geometry, solved from the template's sample "12:45" (Z Flip8), in digit units:
 */
class SamsungClockDigits private constructor(private val paths: Array<Path>, val set: Set) {

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
     */
    fun draw(
        canvas: Canvas, sample: String, text: String, left: Float, bottom: Float, height: Float,
        stroke: Paint, dot: Paint, thickness: Float = STROKE,
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
                drawPath.set(path(c - '0'))
                drawPath.transform(drawMatrix)
                canvas.drawPath(drawPath, stroke)
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

        fun load(context: Context, set: Set = Set.BIG): SamsungClockDigits? = cached[set] ?: runCatching {
            val res = context.createPackageContext(AOD_PACKAGE, 0).resources
            val paths = Array(10) { d ->
                fun arr(kind: String): Array<String> {
                    val id = res.getIdentifier("${set.prefix}_num_${d}_$kind", "array", AOD_PACKAGE)
                    require(id != 0) { "missing ${set.prefix}_num_${d}_$kind" }
                    return res.getStringArray(id)
                }
                buildPath(arr("vertices").map(::point), arr("control_points").map(::point), set.pairedVertices)
            }
            SamsungClockDigits(paths, set)
        }.onFailure { Log.w(CoverState.TAG, "Samsung clock digits unavailable: $set", it) }
            .getOrNull()?.also { cached[set] = it }

        /** "x, y" (control points may carry extra values, e.g. the layered set's shading; only x and y are used). */
        private fun point(s: String): Pair<Float, Float> {
            val v = s.split(',').map { it.trim().toFloat() }
            return v[0] to v[1]
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
