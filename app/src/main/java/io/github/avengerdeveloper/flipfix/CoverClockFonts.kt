package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import java.io.File
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

    /**
     * [font] at [weight], or null if it can't be loaded. [font] is a path inside the AOD app's assets, a
     * system font file (an absolute path, see [GENERIC_FONTS]) or [SYSTEM_SANS].
     */
    fun typeface(context: Context, font: String, weight: Int): Typeface? {
        val key = "$font@$weight"
        typefaces[key]?.let { return it }
        return runCatching {
            when {
                font == SYSTEM_SANS -> Typeface.create(Typeface.SANS_SERIF, weight.coerceIn(1, 1000), false)
                // A missing file would quietly give the default font: check first.
                font.startsWith("/") -> if (File(font).canRead()) {
                    Typeface.Builder(File(font)).setFontVariationSettings("'wght' $weight").build()
                } else {
                    null
                }
                else -> {
                    val aod = context.createPackageContext(AOD_PACKAGE, 0)
                    Typeface.Builder(aod.assets, font).setFontVariationSettings("'wght' $weight").build()
                }
            }
        }.onFailure { Log.w(CoverState.TAG, "cover clock font unavailable: $font", it) }
            .getOrNull()?.also { typefaces[key] = it }
    }

    /**
     * Fonts for a clock whose own font FlipFix can't find (one that isn't in the AOD app, or a template
     * made before an update changed how Samsung draws it): One UI's sans (One UI 8 and later), else Roboto
     * Flex (Android 12 and later), else the system sans. All have a weight axis or weights, so the face
     * can match how bold Samsung draws the clock (see CoverFaceMatcher).
     */
    private val GENERIC_FONTS = listOf("/system/fonts/OneUISans-VF.ttf", "/system/fonts/RobotoFlex-Regular.ttf", SYSTEM_SANS)

    /** [typeface]'s name for the system sans-serif family. */
    const val SYSTEM_SANS = "sans-serif"

    /** The first of [GENERIC_FONTS] that loads, or null (also for a clock without a template: CoverFaceMatcher.FALLBACK). */
    fun genericFont(context: Context): String? = GENERIC_FONTS.firstOrNull { typeface(context, it, 400) != null }

    /** Whether [font] is one of the stand-ins for an unknown font ([GENERIC_FONTS]). */
    fun isGeneric(font: String?): Boolean = font in GENERIC_FONTS

    private val weighted = ConcurrentHashMap<String, Boolean>()

    /**
     * Whether [font] looks different at different weights (a variable font, or the system sans), i.e.
     * whether drawing it bolder or lighter can bring it closer to Samsung's: its "8" at 100 and at 900.
     */
    fun hasWeights(context: Context, font: String): Boolean = weighted.getOrPut(font) {
        fun ink(weight: Int): Int {
            val tf = typeface(context, font, weight) ?: return -1
            val bmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ALPHA_8)
            Canvas(bmp).drawText("8", 10f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = 90f })
            val px = ByteArray(120 * 120).also { bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) }
            bmp.recycle()
            return px.count { (it.toInt() and 0xFF) > 127 }
        }
        val light = ink(100)
        val heavy = ink(900)
        light > 0 && heavy > light * 1.1f
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

    /** [generic], remembered the same way. */
    fun genericCached(context: Context, key: String, samples: () -> List<Sample>): Match? =
        cached(context, "$key#generic") { generic(context, samples()) }

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
        val scores = fonts(context).mapNotNull { font -> fittedWeight(context, font, samples) }
        val best = scores.maxByOrNull { it.overlap }
        val runnerUp = scores.filter { best != null && family(it.font) != family(best.font) }.maxOfOrNull { it.overlap } ?: 0f
        val accepted = best?.takeIf { it.overlap >= FITTED_MIN && it.overlap - runnerUp >= FITTED_LEAD }
        typefaces.keys.removeAll { key -> accepted == null || key != "${accepted.font}@${accepted.weight}" }
        Log.d(CoverState.TAG, "cover clock font, fitted: $best, next family %.2f: %s".format(runnerUp, if (accepted != null) "used" else "not used"))
        return accepted
    }

    /**
     * [font] at the weight whose digits, drawn as in [identifyFitted], best cover [samples]; null if the
     * font can't be loaded. A static font looks the same at every weight: scored once.
     */
    private fun fittedWeight(context: Context, font: String, samples: List<Sample>): Match? {
        fun score(weight: Int): Match? = typeface(context, font, weight)?.let { tf ->
            val (o, spacing) = fittedOverlap(tf, samples)
            Match(font, weight, o, spacing)
        }
        val light = score(100) ?: return null
        val heavy = score(900) ?: return null
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
        return best
    }

    /**
     * Coverage overlap of [samples] with [tf]'s text drawn without kerning, each sample's letter spacing
     * fitted to its width; with the spacing used (em, mean over the samples).
     */
    private fun fittedOverlap(tf: Typeface, samples: List<Sample>): Pair<Float, Float> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = NO_KERNING; typeface = tf }
        val bounds = Rect()
        val spacings = FloatArray(samples.size)
        val o = overlap(samples, soft = true) { canvas, s ->
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
            spacings[samples.indexOf(s)] = paint.letterSpacing
            canvas.drawText(s.text, -bounds.left.toFloat(), -bounds.top.toFloat(), paint)
            bounds.width()
        }
        return o to spacings.average().toFloat()
    }

    /**
     * The first of [GENERIC_FONTS] that loads, at the weight and letter spacing that best cover [samples]:
     * for a clock no AOD font fits. Null if no such font loads, or if it covers the samples so poorly
     * ([GENERIC_MIN]) that they aren't digits at all (e.g. the tiles of Samsung's widget-grid layout).
     * The face draws it only where the template has the clock and as big; how bold Samsung shows the
     * digits is fitted on the lock screen itself (see CoverFaceMatcher).
     */
    fun generic(context: Context, samples: List<Sample>): Match? {
        // Text strokes leave most of their box empty; a widget tile or picture fills it.
        val fill = samples.maxOfOrNull { s -> s.ink.count { it }.toFloat() / s.ink.size.coerceAtLeast(1) } ?: return null
        if (fill > MAX_TEXT_FILL) {
            Log.d(CoverState.TAG, "cover clock font, generic: not digits (ink fills %.0f%% of the box)".format(fill * 100))
            return null
        }
        val font = genericFont(context) ?: return null
        val best = fittedWeight(context, font, samples)
        val accepted = best?.takeIf { it.overlap >= GENERIC_MIN }
        Log.d(CoverState.TAG, "cover clock font, generic: $best: ${if (accepted != null) "used" else "not digits"}")
        return accepted
    }

    /**
     * The generic font covers real template digits at 0.35 and up (Flip6: thin and heavy clock fonts it
     * doesn't share a design with), a date read as digits at under 0.2.
     */
    private const val GENERIC_MIN = 0.3f
    private const val MAX_TEXT_FILL = 0.65f

    /** Samsung's variants of one design (monospaced, fixed, variable) share a family. */
    private fun family(font: String) =
        font.substringAfterLast('/').substringBeforeLast('.').replace(Regex("Mono|_H|_Fixed|Fixed|VF|-Regular"), "")

    /** Paint.fontFeatureSettings that turns kerning off (fonts matched by [identifyFitted]). */
    const val NO_KERNING = "'kern' 0"

    private const val MAX_SPACING = 0.2f
    private const val FITTED_MIN = 0.60f
    private const val FITTED_LEAD = 0.15f
}
