package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * How Samsung's one-line ("horizontal") cover clock ("13:49  Sun 15 November") is laid out, so FlipFix
 * can draw an identical one after unlock. Other styles: see CoverClockLayout.
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

        private const val AOD_PACKAGE = "com.samsung.android.app.aodservice"

        /**
         * The one-line clock, from its ink band in the template (see CoverClockLayout): the line's right
         * edge, and the first glyph (a time digit) for digit height, baseline and colour.
         */
        fun fromBand(t: CoverClockLayout.Template, band: CoverClockLayout.Band): CoverClockStyle? {
            val (minX, maxX) = t.xRange(band) ?: return null
            // First glyph (leftmost column run) = a time digit.
            var gx1 = minX
            while (gx1 + 1 < t.w && t.columnHasInk(band, gx1 + 1)) gx1++
            val glyph = t.yRange(band, minX, gx1) ?: return null
            val color = t.opaqueColor(glyph) ?: Color.rgb(250, 250, 250)
            return CoverClockStyle(
                rightFrac = (maxX + 1f) / t.w,
                timeBaselineFrac = (glyph.bottom + 1f) / t.h + ONE_UI_9_Y_SHIFT,
                digitHeightFrac = glyph.height.toFloat() / t.h,
                color = color,
                fromTemplate = true,
            )
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
