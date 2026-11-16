package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * The fonts Samsung's cover clocks are drawn in. They ship inside Samsung's AOD app (assets/fonts); FlipFix
 * loads them from there at runtime (nothing of Samsung's is bundled) and works out which font and weight a
 * preset uses by comparing them with the preset's template.
 */
object CoverClockFonts {

    private const val AOD_PACKAGE = "com.samsung.android.app.aodservice"
    private const val FONT_DIR = "fonts"

    /** Template digits are compared at this height (px): enough for the shapes, cheap to render. */
    private const val SAMPLE_HEIGHT = 64

    /** Minimum mean overlap (intersection over union) for a font to count as the preset's font. */
    private const val MIN_OVERLAP = 0.85f

    data class Match(val font: String, val weight: Int, val overlap: Float)

    /** A sample of template ink: [ink] is [w] x [h], row by row. */
    class Sample(val text: String, val w: Int, val h: Int, val ink: BooleanArray)

    private val typefaces = ConcurrentHashMap<String, Typeface>()

    /** [font] (a path inside the AOD app's assets) at [weight], or null if it can't be loaded. */
    fun typeface(context: Context, font: String, weight: Int): Typeface? {
        val key = "$font@$weight"
        typefaces[key]?.let { return it }
        return runCatching {
            val aod = context.createPackageContext(AOD_PACKAGE, 0)
            Typeface.Builder(aod.assets, font).setFontVariationSettings("'wght' $weight").build()
        }.onFailure { Log.w(CoverState.TAG, "cover clock font unavailable: $font", it) }
            .getOrNull()?.also { typefaces[key] = it }
    }

    private fun fonts(context: Context): List<String> = runCatching {
        context.createPackageContext(AOD_PACKAGE, 0).assets.list(FONT_DIR).orEmpty()
            .filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
            .map { "$FONT_DIR/$it" }
    }.getOrDefault(emptyList())

    /** Scales template ink ([px], ARGB, [stride] wide) inside [rect] to a [Sample] [SAMPLE_HEIGHT] tall. */
    fun sample(text: String, px: IntArray, stride: Int, rect: Rect, inkAlpha: Int): Sample {
        val crop = Bitmap.createBitmap(px, rect.top * stride + rect.left, stride, rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val w = (rect.width() * SAMPLE_HEIGHT / rect.height().toFloat()).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(crop, w, SAMPLE_HEIGHT, true)
        crop.recycle()
        val out = IntArray(w * SAMPLE_HEIGHT).also { small.getPixels(it, 0, w, 0, 0, w, SAMPLE_HEIGHT) }
        small.recycle()
        return Sample(text, w, SAMPLE_HEIGHT, BooleanArray(out.size) { Color.alpha(out[it]) > inkAlpha })
    }

    /**
     * [identify], remembered across restarts per template ([key]: its path and modification time): trying
     * every font takes a moment, and the cover face may need the answer on the main thread.
     */
    fun identifyCached(context: Context, key: String, samples: () -> List<Sample>): Match? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { saved ->
            if (saved == NONE) return null
            val parts = saved.split('|')
            parts.getOrNull(1)?.toIntOrNull()?.let { return Match(parts[0], it, parts.getOrNull(2)?.toFloatOrNull() ?: 1f) }
        }
        val match = identify(context, samples())
        prefs.edit().putString(key, match?.let { "${it.font}|${it.weight}|${it.overlap}" } ?: NONE).apply()
        return match
    }

    private const val PREFS = "cover_clock_fonts"
    private const val NONE = "-"

    /**
     * The AOD font and weight whose digits best cover [samples] (the template's sample hours and minutes),
     * or null if none is close enough (then FlipFix draws no clock rather than a wrong one).
     */
    fun identify(context: Context, samples: List<Sample>): Match? {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = Rect()
        fun overlap(tf: Typeface): Float = samples.map { s ->
            paint.typeface = tf
            paint.textSize = 200f
            paint.getTextBounds(s.text, 0, s.text.length, bounds)
            if (bounds.height() <= 0) return@map 0f
            paint.textSize = 200f * s.h / bounds.height()
            paint.getTextBounds(s.text, 0, s.text.length, bounds)
            val w = maxOf(s.w, bounds.width()) + 2
            val bmp = Bitmap.createBitmap(w, s.h + 2, Bitmap.Config.ALPHA_8)
            Canvas(bmp).drawText(s.text, -bounds.left.toFloat(), -bounds.top.toFloat(), paint)
            val drawn = ByteArray(w * (s.h + 2)).also { bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) }
            bmp.recycle()
            var both = 0; var either = 0
            for (y in 0 until s.h + 2) for (x in 0 until w) {
                val a = (drawn[y * w + x].toInt() and 0xFF) > 127
                val b = y < s.h && x < s.w && s.ink[y * s.w + x]
                if (a && b) both++
                if (a || b) either++
            }
            if (either == 0) 0f else both.toFloat() / either
        }.average().toFloat()

        var best: Match? = null
        for (font in fonts(context)) {
            fun score(weight: Int): Float = typeface(context, font, weight)?.let { overlap(it) } ?: 0f
            // Static fonts look the same at every weight: score once. Variable ones: coarse, then finer.
            val light = score(100)
            val heavy = score(900)
            var bestWeight = if (heavy > light) 900 else 100
            var bestScore = maxOf(light, heavy)
            if (light != heavy) {
                for (w in 200..800 step 100) score(w).let { if (it > bestScore) { bestScore = it; bestWeight = w } }
                for (step in intArrayOf(50, 25)) {
                    val around = bestWeight
                    for (w in intArrayOf(around - step, around + step)) {
                        if (w !in 100..900) continue
                        score(w).let { if (it > bestScore) { bestScore = it; bestWeight = w } }
                    }
                }
            }
            if (bestScore > (best?.overlap ?: 0f)) best = Match(font, bestWeight, bestScore)
        }
        // Weights tried but not chosen aren't needed any more.
        typefaces.keys.removeAll { key -> best == null || key != "${best.font}@${best.weight}" }
        Log.d(CoverState.TAG, "cover clock font: $best")
        return best?.takeIf { it.overlap >= MIN_OVERLAP }
    }
}
