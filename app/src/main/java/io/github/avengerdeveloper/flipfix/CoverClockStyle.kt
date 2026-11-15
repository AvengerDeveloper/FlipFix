package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * How Samsung's horizontal cover clock ("13:49  Sun 15 November") is laid out, so FlipFix can draw an
 * identical one after unlock.
 *
 * Samsung leaves a rendered template of the user's cover face next to the cover wallpaper:
 * /data/overlays/coverwallpaper/0/<id>/cover_clock.png (readable by apps). From it we take the clock
 * line's right edge (the line is right-aligned and grows to the left), the digit height (which sets the
 * font size) and the text colour.
 *
 * Calibrated against real One UI 9 lock-screen screenshots on a Z Flip6 (748x720 cover):
 *  - time: Samsung AOD font ClockModexVF, wght 775, size ~1.39 x digit height
 *  - date: system Roboto Flex, wght 340, size ~0.92 x time size
 *  - gap between time and date ~0.6 x digit height; date baseline ~0.05 x digit height above the time's
 *  - One UI 9 draws the line 5.4% of the screen height higher than the (older) template shows
 * (exact values in the constants below)
 */
data class CoverClockStyle(
    /** Right edge of the clock line, as a fraction of the cover width. */
    val rightFrac: Float,
    /** Bottom of the time digits (their baseline), as a fraction of the cover height. */
    val timeBaselineFrac: Float,
    /** Height of a time digit, as a fraction of the cover height. */
    val digitHeightFrac: Float,
    val color: Int,
    val fromTemplate: Boolean,
) {
    companion object {
        // Fitted with FreeType on a PC, then corrected over two rounds of same-minute screenshots
        // (Samsung locked vs FlipFix unlocked): Android draws these fonts a little smaller and lighter.
        // Time weight was chosen by matching digit stroke width (10 px on a 38 px digit).
        const val TIME_SIZE_PER_DIGIT_HEIGHT = 1.3697f * 1.018f
        const val DATE_SIZE_PER_TIME_SIZE = 0.9188f
        const val GAP_PER_DIGIT_HEIGHT = 0.605f
        const val DATE_RAISE_PER_DIGIT_HEIGHT = 0.053f
        const val TIME_LETTER_SPACING = 0f
        const val TIME_WEIGHT = 775
        const val DATE_WEIGHT = 340

        /** On-device nudges from the side-by-side comparison, as fractions of the cover size. */
        const val RIGHT_NUDGE_FRAC = 3f / 748f
        const val DOWN_NUDGE_FRAC = 0f

        /** One UI 9 places the clock higher than the template rendered by Wallpaper & style. */
        private const val ONE_UI_9_Y_SHIFT = -0.0541f

        /** Measured on the Flip6 lock screen; used when no template can be read. */
        private val FALLBACK = CoverClockStyle(
            rightFrac = 713.5f / 748f,
            timeBaselineFrac = 470f / 720f,
            digitHeightFrac = 38f / 720f,
            color = Color.rgb(250, 250, 250),
            fromTemplate = false,
        )

        private const val TEMPLATE_ROOT = "/data/overlays/coverwallpaper/0"
        private const val AOD_PACKAGE = "com.samsung.android.app.aodservice"

        fun load(): CoverClockStyle = runCatching { fromTemplate() }
            .onFailure { Log.w(CoverState.TAG, "cover clock template unreadable", it) }
            .getOrNull() ?: FALLBACK

        /** The template of the cover wallpaper set most recently. */
        private fun templateFile(): File? = File(TEMPLATE_ROOT).listFiles()
            ?.map { File(it, "cover_clock.png") }
            ?.filter { it.canRead() }
            ?.maxByOrNull { it.lastModified() }

        private fun fromTemplate(): CoverClockStyle? {
            val file = templateFile() ?: return null
            val bmp = BitmapFactory.decodeFile(file.path) ?: return null
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            bmp.recycle()
            fun ink(x: Int, y: Int) = Color.alpha(px[y * w + x]) > 110

            // The top ~15% holds sample status icons (notifications, battery); the clock is below.
            val top = (h * 0.15f).toInt()
            var minX = w; var maxX = -1
            for (y in top until h) for (x in 0 until w) if (ink(x, y)) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
            }
            if (maxX < 0) return null

            // First glyph (leftmost column run) = a time digit: gives digit height and baseline.
            var gx1 = minX
            while (gx1 + 1 < w && (top until h).any { ink(gx1 + 1, it) }) gx1++
            var gy0 = h; var gy1 = -1
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in top until h) for (x in minX..gx1) if (ink(x, y)) {
                if (y < gy0) gy0 = y
                if (y > gy1) gy1 = y
                val c = px[y * w + x]
                if (Color.alpha(c) > 200) { r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++ }
            }
            if (gy1 < 0) return null
            val color = if (n > 0) Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()) else FALLBACK.color

            return CoverClockStyle(
                rightFrac = (maxX + 1f) / w,
                timeBaselineFrac = (gy1 + 1f) / h + ONE_UI_9_Y_SHIFT,
                digitHeightFrac = (gy1 - gy0 + 1f) / h,
                color = color,
                fromTemplate = true,
            ).also { Log.d(CoverState.TAG, "cover clock from ${file.parentFile?.name}: $it") }
        }

        /** Samsung's clock digits, loaded from Samsung's AOD app (never bundled with FlipFix). */
        fun timeTypeface(context: Context): Typeface = runCatching {
            val aod = context.createPackageContext(AOD_PACKAGE, 0)
            Typeface.Builder(aod.assets, "fonts/ClockModexVF.ttf")
                .setFontVariationSettings("'wght' $TIME_WEIGHT")
                .build()
        }.onFailure { Log.w(CoverState.TAG, "Samsung clock font unavailable", it) }
            .getOrNull() ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)

        fun dateTypeface(): Typeface = runCatching {
            Typeface.Builder(File("/system/fonts/RobotoFlex-Regular.ttf"))
                .setFontVariationSettings("'wght' $DATE_WEIGHT")
                .build()
        }.getOrNull() ?: Typeface.create("sans-serif", Typeface.NORMAL)
    }
}
