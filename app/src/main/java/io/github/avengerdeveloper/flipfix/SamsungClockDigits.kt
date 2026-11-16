package io.github.avengerdeveloper.flipfix

import android.content.Context
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
class SamsungClockDigits private constructor(private val paths: Array<Path>) {

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

    companion object {
        const val STROKE = 0.1438f      // line thickness
        const val ADVANCE = 0.50f       // distance between digit origins (fixed slots)
        const val COLON_GAP = 0.365f    // digit origin to colon centre, each side
        const val DOT_OFFSET = 0.1436f  // colon dots above/below the digits' centre
        const val DOT_DIAMETER = 0.161f

        private const val AOD_PACKAGE = "com.samsung.android.app.aodservice"

        @Volatile private var cached: SamsungClockDigits? = null

        fun load(context: Context): SamsungClockDigits? = cached ?: runCatching {
            val res = context.createPackageContext(AOD_PACKAGE, 0).resources
            val paths = Array(10) { d ->
                fun arr(kind: String): Array<String> {
                    val id = res.getIdentifier("big_clock_num_${d}_$kind", "array", AOD_PACKAGE)
                    require(id != 0) { "missing big_clock_num_${d}_$kind" }
                    return res.getStringArray(id)
                }
                buildPath(arr("vertices").map(::point), arr("control_points").map(::point))
            }
            SamsungClockDigits(paths)
        }.onFailure { Log.w(CoverState.TAG, "Samsung clock digits unavailable", it) }
            .getOrNull()?.also { cached = it }

        /** "x, y" (control points may carry extra values; only x and y are used). */
        private fun point(s: String): Pair<Float, Float> {
            val v = s.split(',').map { it.trim().toFloat() }
            return v[0] to v[1]
        }

        private fun buildPath(vertices: List<Pair<Float, Float>>, controls: List<Pair<Float, Float>>): Path {
            fun zero(p: Pair<Float, Float>) = p.first == 0f && p.second == 0f
            val path = Path()
            // y is up in Samsung's data, down on screen.
            path.moveTo(vertices[0].first, -vertices[0].second)
            for (i in 0 until vertices.size - 1) {
                val end = vertices[i + 1]
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
