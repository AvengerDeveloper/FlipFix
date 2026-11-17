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
    const val MIN_OVERLAP = 0.80f

    /**
     * [spacing]: null for a font drawn as it lays text out itself; set when found by [identifyFitted]: the
     * text is drawn without kerning and with this letter spacing (em), as Samsung's lock screen draws it.
     */
    data class Match(val font: String, val weight: Int, val overlap: Float, val spacing: Float? = null)

    /** A sample of template ink, [w] x [h] row by row: [ink] (opaque enough) and its [alpha] (0..1). */
    class Sample(val text: String, val w: Int, val h: Int, val ink: BooleanArray, val alpha: FloatArray)

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
        return Sample(
            text, w, SAMPLE_HEIGHT,
            BooleanArray(out.size) { Color.alpha(out[it]) > inkAlpha },
            FloatArray(out.size) { Color.alpha(out[it]) / 255f },
        )
    }

    /**
     * [identify], remembered across restarts per template ([key]: its path and modification time): trying
     * every font takes a moment, and the cover face may need the answer on the main thread.
     */
    fun identifyCached(context: Context, key: String, samples: () -> List<Sample>): Match? =
        cached(context, key) { identify(context, samples()) }

    /** [identifyFitted], remembered the same way. */
    fun identifyFittedCached(context: Context, key: String, samples: () -> List<Sample>): Match? =
        cached(context, "$key#fitted") { identifyFitted(context, samples()) }

    private fun cached(context: Context, key: String, find: () -> Match?): Match? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { saved ->
            if (saved == NONE) return null
            val parts = saved.split('|')
            parts.getOrNull(1)?.toIntOrNull()?.let {
                return Match(parts[0], it, parts.getOrNull(2)?.toFloatOrNull() ?: 0f, parts.getOrNull(3)?.toFloatOrNull())
            }
        }
        val match = find()
        prefs.edit().putString(key, match?.let { "${it.font}|${it.weight}|${it.overlap}|${it.spacing ?: ""}" } ?: NONE).apply()
        return match
    }

    /** v2: every best match is kept (the caller compares it with Samsung's path digits). */
    private const val PREFS = "cover_clock_fonts_v2"
    private const val NONE = "-"

    /**
     * Mean overlap of [samples] with ink drawn by [draw] (text, sample) into an ALPHA_8 canvas: of opaque
     * pixels (intersection over union), or if [soft], of coverage (the lower opacity summed over the higher),
     * which gives partial credit to thin lines and hatching that sit a fraction of a pixel off.
     */
    private fun overlap(samples: List<Sample>, soft: Boolean = false, draw: (Canvas, Sample) -> Int): Float = samples.map { s ->
        var w = s.w + 2
        var bmp = Bitmap.createBitmap(w, s.h + 2, Bitmap.Config.ALPHA_8)
        val needed = draw(Canvas(bmp), s) + 2
        if (needed > w) {
            bmp.recycle()
            w = needed
            bmp = Bitmap.createBitmap(w, s.h + 2, Bitmap.Config.ALPHA_8)
            draw(Canvas(bmp), s)
        }
        val drawn = ByteArray(w * (s.h + 2)).also { bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) }
        bmp.recycle()
        if (soft) {
            var low = 0f; var high = 0f
            for (y in 0 until s.h + 2) for (x in 0 until w) {
                val a = (drawn[y * w + x].toInt() and 0xFF) / 255f
                val b = if (y < s.h && x < s.w) s.alpha[y * s.w + x] else 0f
                low += minOf(a, b); high += maxOf(a, b)
            }
            return@map if (high == 0f) 0f else low / high
        }
        var both = 0; var either = 0
        for (y in 0 until s.h + 2) for (x in 0 until w) {
            val a = (drawn[y * w + x].toInt() and 0xFF) > 127
            val b = y < s.h && x < s.w && s.ink[y * s.w + x]
            if (a && b) both++
            if (a || b) either++
        }
        if (either == 0) 0f else both.toFloat() / either
    }.average().toFloat()

    /**
     * How well Samsung's path-drawn digits (SamsungClockDigits: the default rounded set and the layered
     * one) cover [samples], drawn the way FlipFix draws them, with the set and stroke thickness that fit
     * best (Samsung's clock editor lets the thickness be changed).
     */
    fun pathOverlap(context: Context, samples: List<Sample>): PathMatch {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        var best = PathMatch(0f, SamsungClockDigits.STROKE, SamsungClockDigits.Set.BIG)
        for (set in SamsungClockDigits.Set.entries) {
            val digits = SamsungClockDigits.load(context, set) ?: continue
            fun at(thickness: Float) = overlap(samples) { canvas, s ->
                digits.draw(canvas, s.text, s.text, 0f, s.h.toFloat(), s.h.toFloat(), stroke, dot, thickness)
                s.w
            }
            fun consider(k: Float) = at(k).let { if (it > best.overlap) best = PathMatch(it, k, set) }
            if (set == SamsungClockDigits.Set.BIG) consider(SamsungClockDigits.STROKE)
            for (i in 0..THICKNESS_STEPS) consider(THICKNESS_MIN + (THICKNESS_MAX - THICKNESS_MIN) * i / THICKNESS_STEPS)
            // Refine around the best step (if this set is the best so far).
            if (best.set == set) {
                val step = (THICKNESS_MAX - THICKNESS_MIN) / THICKNESS_STEPS
                val around = best.thickness
                consider(around - step / 2)
                consider(around + step / 2)
            }
        }
        return best
    }

    /** How well Samsung's path digits fit: which set, at which stroke thickness (digit units). */
    data class PathMatch(val overlap: Float, val thickness: Float, val set: SamsungClockDigits.Set)

    private const val THICKNESS_MIN = 0.06f
    private const val THICKNESS_MAX = 0.24f
    private const val THICKNESS_STEPS = 9

    /**
     * The AOD font and weight whose digits best cover [samples] (the template's sample time), with how well
     * ([Match.overlap]; at least [MIN_OVERLAP] to be usable), or null if no font could be loaded.
     */
    fun identify(context: Context, samples: List<Sample>): Match? {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = Rect()
        fun overlap(tf: Typeface): Float = overlap(samples) { canvas, s ->
            paint.typeface = tf
            paint.textSize = 200f
            paint.getTextBounds(s.text, 0, s.text.length, bounds)
            if (bounds.height() <= 0) return@overlap 0
            paint.textSize = 200f * s.h / bounds.height()
            paint.getTextBounds(s.text, 0, s.text.length, bounds)
            canvas.drawText(s.text, -bounds.left.toFloat(), -bounds.top.toFloat(), paint)
            bounds.width()
        }

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
        return best
    }

    /**
     * A second look for a template no font fitted in [identify]: Samsung draws some clock fonts without
     * their kerning and a little closer together or further apart than the font sets them, so thin strokes
     * (Clock-Stripe's parallel lines, Fredericka the Great's hatching) miss by a pixel almost everywhere.
     * Here each font is drawn that way, its letter spacing fitted to the sample's width, and compared by
     * coverage. The best font counts only if it scores at least [FITTED_MIN] and [FITTED_LEAD] more than any
     * font of another family (Samsung's fonts come in families, e.g. ClockStampVF and ClockStampMonoVF).
     */
    fun identifyFitted(context: Context, samples: List<Sample>): Match? {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = NO_KERNING }
        val bounds = Rect()
        val spacingOf = HashMap<Sample, Float>()
        fun overlap(tf: Typeface): Float = overlap(samples, soft = true) { canvas, s ->
            paint.typeface = tf
            paint.letterSpacing = 0f
            paint.textSize = 200f
            paint.getTextBounds(s.text, 0, s.text.length, bounds)
            if (bounds.height() <= 0) return@overlap 0
            paint.textSize = 200f * s.h / bounds.height()
            paint.getTextBounds(s.text, 0, s.text.length, bounds)
            if (s.text.length > 1) {
                val extra = (s.w - bounds.width()).toFloat() / (s.text.length - 1)
                paint.letterSpacing = (extra / paint.textSize).coerceIn(-MAX_SPACING, MAX_SPACING)
                paint.getTextBounds(s.text, 0, s.text.length, bounds)
            }
            spacingOf[s] = paint.letterSpacing
            canvas.drawText(s.text, -bounds.left.toFloat(), -bounds.top.toFloat(), paint)
            bounds.width()
        }

        val scores = mutableListOf<Match>()
        for (font in fonts(context)) {
            fun score(weight: Int): Match? = typeface(context, font, weight)?.let { tf ->
                val o = overlap(tf)
                Match(font, weight, o, samples.map { spacingOf[it] ?: 0f }.average().toFloat())
            }
            val light = score(100) ?: continue
            val heavy = score(900) ?: continue
            var best = if (heavy.overlap > light.overlap) heavy else light
            if (light.overlap != heavy.overlap) {
                for (w in 200..800 step 100) score(w)?.let { if (it.overlap > best.overlap) best = it }
                for (step in intArrayOf(50, 25)) {
                    val around = best.weight
                    for (w in intArrayOf(around - step, around + step)) {
                        if (w !in 100..900) continue
                        score(w)?.let { if (it.overlap > best.overlap) best = it }
                    }
                }
            }
            scores += best
        }
        val best = scores.maxByOrNull { it.overlap }
        val runnerUp = scores.filter { best != null && family(it.font) != family(best.font) }.maxOfOrNull { it.overlap } ?: 0f
        val accepted = best?.takeIf { it.overlap >= FITTED_MIN && it.overlap - runnerUp >= FITTED_LEAD }
        typefaces.keys.removeAll { key -> accepted == null || key != "${accepted.font}@${accepted.weight}" }
        Log.d(CoverState.TAG, "cover clock font, fitted: $best, next family %.2f: %s".format(runnerUp, if (accepted != null) "used" else "not used"))
        return accepted
    }

    /** Samsung's variants of one design (monospaced, fixed, variable) share a family. */
    private fun family(font: String) =
        font.substringAfterLast('/').substringBeforeLast('.').replace(Regex("Mono|_H|_Fixed|Fixed|VF|-Regular"), "")

    /** Paint.fontFeatureSettings that turns kerning off (fonts matched by [identifyFitted]). */
    const val NO_KERNING = "'kern' 0"

    private const val MAX_SPACING = 0.2f
    private const val FITTED_MIN = 0.60f
    private const val FITTED_LEAD = 0.15f
}
