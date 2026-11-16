package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * Which Samsung cover clock style a cover preset has, read from the template Wallpaper & style renders next
 * to each preset's wallpaper (/data/overlays/coverwallpaper/0/<id>/cover_clock.png). Which preset is active
 * is worked out by CoverFaceMatcher.
 *
 * The template is split into horizontal ink bands below the sample status icons:
 *  - one band            -> [Horizontal]: "13:49  Sun 15 November" on one line (Z Flip6 default)
 *  - small band over big -> [Stacked]: "Mon 16 Nov  (weather)" over huge rounded digits (Z Flip8 default)
 *  - anything else       -> [Unknown]: FlipFix draws no clock rather than a wrong one (safety net)
 */
sealed interface CoverClockLayout {

    data class Horizontal(val style: CoverClockStyle) : CoverClockLayout

    /**
     * Fractions are of the cover display (the template is the cover at a larger scale, same aspect).
     * Calibrated on a Z Flip8 (948x1048 cover): digits are Samsung's own path-drawn "big clock" digits
     * (SamsungClockDigits) in fixed slots, with an opacity fade towards the top; the date in Roboto Flex.
     * Samsung centres "date + weather"; FlipFix keeps the date where it sits on the lock screen, even
     * without weather.
     */
    data class Stacked(
        /** Left edge of the sample digits' ink ("12:45") in the template. */
        val digitsLeftFrac: Float,
        /** Bottom of the digits' ink. */
        val digitsBottomFrac: Float,
        /** Ink height of the sample digits. */
        val digitHeightFrac: Float,
        val lineCenterXFrac: Float,
        val dateBaselineFrac: Float,
        val dateHeightFrac: Float,
        val color: Int,
        /** Digit opacity at the top of the digits (Samsung fades them in towards the bottom). */
        val digitsTopAlpha: Float,
    ) : CoverClockLayout

    data object Unknown : CoverClockLayout

    companion object {
        const val TEMPLATE_ROOT = "/data/overlays/coverwallpaper/0"
        private const val TEMPLATE_NAME = "cover_clock.png"
        private const val INK_ALPHA = 60

        /** Camera shortcut corner of the templates (left and top edges, as fractions). */
        private const val CAMERA_LEFT = 0.80f
        private const val CAMERA_TOP = 0.70f

        /**
         * Width Samsung's weather (gap + icon + temperature) takes after the date, per date ascender
         * height (Flip8 lock screen: 197 px after a 42.5 px-tall date).
         */
        const val WEATHER_SLOT_PER_DATE_HEIGHT = 4.64f

        /** Samsung's sample text in the template: the digits are positioned and sized from it. */
        const val SAMPLE_TIME = "12:45"

        /** Letters with ascenders: the template's date ("Wed 1 Feb") is sized by its tallest letters. */
        const val ASCENDERS = "bd"

        const val DATE_LETTER_SPACING = -0.02f

        /** The clock of the active cover preset (see CoverFaceMatcher), or [Unknown] for no clock. */
        fun load(context: Context): CoverClockLayout {
            val file = CoverFaceMatcher.activeTemplate(context) ?: return Unknown
            return parse(file)
        }

        /** Every cover preset's template (one folder per preset in Wallpaper & style). */
        fun templates(): List<File> = File(TEMPLATE_ROOT).listFiles()
            ?.map { File(it, TEMPLATE_NAME) }
            ?.filter { it.canRead() }
            .orEmpty()

        /** The template of the preset created or edited most recently (in this cover's shape). */
        fun newestTemplate(context: Context): File? {
            val size = coverSize(context)
            return templates().filter { size == null || sameShape(it, size.first, size.second) }
                .maxByOrNull { it.lastModified() }
        }

        /**
         * Whether [file] was rendered for a cover of this shape. Presets carried over from another phone
         * (e.g. a Flip6 backup restored on a Flip8) keep that phone's template, but Samsung lays them out
         * differently on this cover, so their template doesn't say where the clock really is.
         */
        fun sameShape(file: File, coverW: Int, coverH: Int): Boolean {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0 || coverW <= 0 || coverH <= 0) return false
            val template = o.outWidth.toFloat() / o.outHeight
            val cover = coverW.toFloat() / coverH
            return kotlin.math.abs(template / cover - 1f) < SHAPE_TOLERANCE
        }

        private fun coverSize(context: Context): Pair<Int, Int>? {
            val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return null
            val mode = CoverState.coverDisplay(dm)?.mode ?: return null
            return mode.physicalWidth to mode.physicalHeight
        }

        private const val SHAPE_TOLERANCE = 0.03f

        /** Parsed once per template file and modification time: presets rarely change, parsing is slow. */
        private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CoverClockLayout>>()

        fun parse(file: File): CoverClockLayout {
            val stamp = file.lastModified()
            parsed[file.path]?.let { (at, layout) -> if (at == stamp) return layout }
            val layout = runCatching { fromTemplate(file) }
                .onFailure { Log.w(CoverState.TAG, "cover clock template unreadable: ${file.parentFile?.name}", it) }
                .getOrNull() ?: Unknown
            parsed[file.path] = stamp to layout
            return layout
        }

        private fun fromTemplate(file: File): CoverClockLayout {
            val bmp = BitmapFactory.decodeFile(file.path) ?: return Unknown
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            bmp.recycle()
            val t = Template(px, w, h)

            // Ink bands below the sample status icons (top 10%); tiny gaps (dots, icons) are merged.
            val bands = t.bands(from = (h * 0.10f).toInt(), mergeGap = (h * 0.02f).toInt())
            val layout = when {
                // A one-line clock is a thin band; a band filling the template is an illustrated face.
                bands.size == 1 && bands[0].height < h * 0.2f ->
                    Horizontal(CoverClockStyle.fromBand(t, bands[0]) ?: return Unknown)
                bands.size == 2 && bands[1].height > bands[0].height * 1.8f -> stacked(t, bands[0], bands[1])
                else -> Unknown
            }
            Log.d(CoverState.TAG, "cover clock from ${file.parentFile?.name}: bands=$bands -> $layout")
            return layout
        }

        private fun stacked(t: Template, line: Band, digits: Band): CoverClockLayout {
            val (dx0, dx1) = t.xRange(digits) ?: return Unknown
            val (lx0, lx1) = t.xRange(line) ?: return Unknown
            // The date is the first cluster of the line, up to a gap wider than half the line height
            // (then comes the weather icon).
            val dateRight = t.firstClusterRight(line, lx0, gap = (line.height * 0.5f).toInt())
            val dateBand = t.yRange(line, lx0, dateRight) ?: line
            return Stacked(
                digitsLeftFrac = dx0.toFloat() / t.w,
                digitsBottomFrac = (digits.bottom + 1f) / t.h,
                digitHeightFrac = digits.height.toFloat() / t.h,
                lineCenterXFrac = (lx0 + lx1 + 1) / 2f / t.w,
                dateBaselineFrac = (dateBand.bottom + 1f) / t.h,
                dateHeightFrac = dateBand.height.toFloat() / t.h,
                color = t.opaqueColor(digits) ?: Color.WHITE,
                digitsTopAlpha = t.topAlpha(digits),
            )
        }

        /** Same as the one-line style's date: system Roboto Flex (closest to Samsung's date width). */
        fun stackedDateTypeface(): Typeface = runCatching {
            Typeface.Builder(File("/system/fonts/RobotoFlex-Regular.ttf")).setFontVariationSettings("'wght' 375").build()
        }.getOrNull() ?: Typeface.DEFAULT
    }

    /** A horizontal strip of the template that contains ink, rows [top, bottom]. */
    data class Band(val top: Int, val bottom: Int) {
        val height get() = bottom - top + 1
    }

    /** Pixel helpers over the template. */
    class Template(private val px: IntArray, val w: Int, val h: Int) {
        fun alpha(x: Int, y: Int) = Color.alpha(px[y * w + x])

        /** Clock ink; the camera shortcut Samsung draws in the bottom-right corner doesn't count. */
        fun ink(x: Int, y: Int) = alpha(x, y) > INK_ALPHA && !(x > w * CAMERA_LEFT && y > h * CAMERA_TOP)

        fun bands(from: Int, mergeGap: Int): List<Band> {
            val out = mutableListOf<Band>()
            var start = -1
            var lastInk = -1
            for (y in from until h) {
                val any = (0 until w).any { ink(it, y) }
                if (any) {
                    if (start < 0) start = y
                    else if (y - lastInk > mergeGap) { out += Band(start, lastInk); start = y }
                    lastInk = y
                }
            }
            if (start >= 0) out += Band(start, lastInk)
            return out
        }

        fun xRange(b: Band): Pair<Int, Int>? {
            var x0 = w; var x1 = -1
            for (y in b.top..b.bottom) for (x in 0 until w) if (ink(x, y)) { if (x < x0) x0 = x; if (x > x1) x1 = x }
            return if (x1 < 0) null else x0 to x1
        }

        fun yRange(b: Band, x0: Int, x1: Int): Band? {
            var y0 = -1; var y1 = -1
            for (y in b.top..b.bottom) if ((x0..x1).any { ink(it, y) }) { if (y0 < 0) y0 = y; y1 = y }
            return if (y0 < 0) null else Band(y0, y1)
        }

        fun columnHasInk(b: Band, x: Int) = (b.top..b.bottom).any { ink(x, it) }

        /** Right edge of the first cluster of columns starting at [x0], ending at a gap wider than [gap]. */
        fun firstClusterRight(b: Band, x0: Int, gap: Int): Int {
            var last = x0
            var x = x0
            while (x < w && x - last <= gap) {
                if (columnHasInk(b, x)) last = x
                x++
            }
            return last
        }

        fun opaqueColor(b: Band): Int? {
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            for (y in b.top..b.bottom) for (x in 0 until w) {
                val c = px[y * w + x]
                if (Color.alpha(c) > 240) { r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++ }
            }
            return if (n == 0L) null else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }

        /** Mean opacity of the ink in the top tenth of the band, relative to fully opaque. */
        fun topAlpha(b: Band): Float {
            val rows = (b.height * 0.1f).toInt().coerceAtLeast(1)
            var sum = 0L; var n = 0L
            for (y in b.top until b.top + rows) for (x in 0 until w) {
                val a = alpha(x, y)
                if (a > 150) { sum += a; n++ }
            }
            return if (n == 0L) 1f else (sum.toFloat() / n / 255f).coerceIn(0.3f, 1f)
        }
    }
}
