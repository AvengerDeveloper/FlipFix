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
 *  - otherwise, in turn  -> [TwoLine] (hours over minutes), [Sideways], [Analog] (a dial with hands)
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
        /** Stroke thickness of the digits, in digit units (Samsung's clock editor has a thickness setting). */
        val thickness: Float = SamsungClockDigits.STROKE,
        val digitSet: SamsungClockDigits.Set = SamsungClockDigits.Set.BIG,
    ) : CoverClockLayout

    /**
     * Hours over minutes ("12" over "45"), with a date line above or below, aligned left, centred or
     * right. Drawn in one of the AOD app's clock fonts (see CoverClockFonts), identified from the template.
     * Fractions are of the cover (x of its width, the rest of its height); digit lines are aligned by
     * their advance width (as Samsung lays them out), the date by its ink.
     */
    data class TwoLine(
        val font: String,
        val weight: Int,
        val align: Align,
        val anchorXFrac: Float,
        val textSizeFrac: Float,
        val hoursBaselineFrac: Float,
        val minutesBaselineFrac: Float,
        val color: Int,
        val dateLongMonth: Boolean,
        val dateAnchorXFrac: Float,
        val dateTextSizeFrac: Float,
        val dateBaselineFrac: Float,
        val dateColor: Int,
        /** The time on one line ("12:45", baseline [hoursBaselineFrac]) instead of hours over minutes. */
        val oneLine: Boolean = false,
        /** Set: the digits are Samsung's rounded path digits (SamsungClockDigits), not a font. */
        val paths: PathLines? = null,
    ) : CoverClockLayout

    /** Hours over minutes in Samsung's path digits: each line's ink left and bottom, ink height, fade. */
    data class PathLines(
        val hoursLeftFrac: Float,
        val hoursBottomFrac: Float,
        val minutesLeftFrac: Float,
        val minutesBottomFrac: Float,
        val heightFrac: Float,
        val topAlpha: Float,
        val thickness: Float = SamsungClockDigits.STROKE,
        val digitSet: SamsungClockDigits.Set = SamsungClockDigits.Set.BIG,
    )

    enum class Align { LEFT, CENTER, RIGHT }

    /**
     * One line ("Wed 1 February 12:45") turned on its side, reading upwards. Coordinates are in the line's
     * own upright frame: x runs up the cover (fraction of its height), y runs across it from the left
     * (fraction of its width); sizes are fractions of the cover's height. The end of the line ([anchorFrac],
     * at the time's ink) stays put, as on Samsung's lock screen; the date sits [gapFrac] before the time.
     */
    data class Sideways(
        val timeFont: String,
        val timeWeight: Int,
        val timeTextSizeFrac: Float,
        val timeBaselineFrac: Float,
        val timeFirst: Boolean,
        val anchorFrac: Float,
        val gapFrac: Float,
        val dateLongMonth: Boolean,
        val dateTextSizeFrac: Float,
        val dateScaleX: Float,
        val dateBaselineFrac: Float,
        val color: Int,
        val dateColor: Int,
    ) : CoverClockLayout

    /**
     * An analog dial (read by CoverClockAnalog). [dial] is the template's own dial (numerals or ticks) with
     * the sample hands taken out, drawn over the cover rect given by the dial fractions; the hands are drawn
     * live around the centre. Hand sizes and the pin are fractions of the cover height.
     */
    data class Analog(
        val dial: android.graphics.Bitmap,
        val dialLeftFrac: Float,
        val dialTopFrac: Float,
        val dialRightFrac: Float,
        val dialBottomFrac: Float,
        val centerXFrac: Float,
        val centerYFrac: Float,
        val hour: Hand,
        val minute: Hand,
        /** Null when the style has no second hand. */
        val second: Hand?,
        val pinRadiusFrac: Float,
        val color: Int,
        val secondColor: Int,
        val pinColor: Int,
        val date: DateLine?,
    ) : CoverClockLayout

    /** A clock hand from the centre to its round tip ([lengthFrac]); a tail behind the centre if any. */
    data class Hand(val lengthFrac: Float, val widthFrac: Float, val tailFrac: Float = 0f, val tailWidthFrac: Float = 0f)

    /** A date line ("Wed 1 February" or "Wed 1 Feb"), its ink anchored at [anchorXFrac] by [align]. */
    data class DateLine(
        val longMonth: Boolean,
        val align: Align,
        val anchorXFrac: Float,
        val textSizeFrac: Float,
        val baselineFrac: Float,
        val color: Int,
    )

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
            return parse(context, file)
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

        fun parse(context: Context, file: File): CoverClockLayout {
            val stamp = file.lastModified()
            parsed[file.path]?.let { (at, layout) -> if (at == stamp) return layout }
            val layout = runCatching { fromTemplate(context, file) }
                .onFailure { Log.w(CoverState.TAG, "cover clock template unreadable: ${file.parentFile?.name}", it) }
                .getOrNull() ?: Unknown
            parsed[file.path] = stamp to layout
            return layout
        }

        private fun fromTemplate(context: Context, file: File): CoverClockLayout {
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
                bands.size == 2 && bands[1].height > bands[0].height * 1.8f ->
                    stacked(context, t, bands[0], bands[1], "${file.path}@${file.lastModified()}")
                else -> twoLine(context, t, "${file.path}@${file.lastModified()}").takeIf { it != Unknown }
                    ?: sideways(context, t, "${file.path}@${file.lastModified()}").takeIf { it != Unknown }
                    ?: CoverClockAnalog.parse(t)
            }
            Log.d(CoverState.TAG, "cover clock from ${file.parentFile?.name}: bands=$bands -> $layout")
            return layout
        }

        /** Hours over minutes with a date line: three bands, two equal digit lines and a small date line. */
        private fun twoLine(context: Context, t: Template, key: String): CoverClockLayout {
            // Here the date sits close to the digits: only merge gaps within a line.
            val bands = t.bands(from = (t.h * 0.10f).toInt(), mergeGap = (t.h * 0.006f).toInt())
            if (bands.size != 3) return Unknown
            fun similar(a: Band, b: Band) = a.height >= b.height * 0.85f && a.height <= b.height * 1.18f
            val hours: Band; val minutes: Band; val date: Band
            when {
                similar(bands[1], bands[2]) && bands[0].height < bands[1].height * 0.45f -> {
                    date = bands[0]; hours = bands[1]; minutes = bands[2]
                }
                similar(bands[0], bands[1]) && bands[2].height < bands[0].height * 0.45f -> {
                    hours = bands[0]; minutes = bands[1]; date = bands[2]
                }
                else -> return Unknown
            }
            val hoursRect = t.inkRect(hours) ?: return Unknown
            val minutesRect = t.inkRect(minutes) ?: return Unknown
            val dateRect = t.inkRect(date) ?: return Unknown
            val samples = {
                listOf(t.sample(SAMPLE_HOURS, hoursRect), t.sample(SAMPLE_MINUTES, minutesRect))
            }
            val font = CoverClockFonts.identifyCached(context, key, samples)
            val pathMatch = CoverClockFonts.pathOverlap(context, samples())
            val paths = pathMatch.overlap
            Log.d(CoverState.TAG, "cover clock two lines: font $font, Samsung's path digits $pathMatch")
            if (paths >= CoverClockFonts.MIN_OVERLAP && paths > (font?.overlap ?: 0f)) {
                val inkCenter = (hoursRect.exactCenterX() + minutesRect.exactCenterX()) / 2
                val align = alignOf(inkCenter, t.w)
                val color = t.opaqueColor(hours) ?: Color.WHITE
                val d = dateFields(t, dateRect, align, color) ?: return Unknown
                return TwoLine(
                    font = "", weight = 0, align = align, anchorXFrac = 0f, textSizeFrac = 0f,
                    hoursBaselineFrac = 0f, minutesBaselineFrac = 0f, color = color,
                    dateLongMonth = d.long, dateAnchorXFrac = d.anchor / t.w, dateTextSizeFrac = d.size / t.h,
                    dateBaselineFrac = d.baseline / t.h, dateColor = d.color,
                    paths = PathLines(
                        hoursLeftFrac = hoursRect.left.toFloat() / t.w,
                        hoursBottomFrac = hoursRect.bottom.toFloat() / t.h,
                        minutesLeftFrac = minutesRect.left.toFloat() / t.w,
                        minutesBottomFrac = minutesRect.bottom.toFloat() / t.h,
                        heightFrac = hoursRect.height().toFloat() / t.h,
                        topAlpha = t.topAlpha(hours),
                        thickness = pathMatch.thickness,
                        digitSet = pathMatch.set,
                    ),
                )
            }
            if (font == null || font.overlap < CoverClockFonts.MIN_OVERLAP) return Unknown
            return fontClock(context, t, font, listOf(SAMPLE_HOURS to hoursRect, SAMPLE_MINUTES to minutesRect), dateRect)
        }

        internal fun alignOf(centerX: Float, width: Int) = when {
            kotlin.math.abs(centerX - width / 2f) < width * 0.02f -> Align.CENTER
            centerX < width / 2f -> Align.LEFT
            else -> Align.RIGHT
        }

        /**
         * Time line(s) in an AOD font: one line ("12:45") or hours over minutes ("12", "45"), with a date.
         * Size from the first line's ink height, baselines from each line's ink bottom, alignment from where
         * the lines' advance boxes sit (as Samsung lays them out).
         */
        private fun fontClock(
            context: Context, t: Template, font: CoverClockFonts.Match,
            lines: List<Pair<String, android.graphics.Rect>>, dateRect: android.graphics.Rect,
        ): CoverClockLayout {
            val tf = CoverClockFonts.typeface(context, font.font, font.weight) ?: return Unknown
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = tf }
            val r = android.graphics.Rect()
            val (firstText, firstRect) = lines.first()
            paint.textSize = 200f
            paint.getTextBounds(firstText, 0, firstText.length, r)
            paint.textSize = 200f * firstRect.height() / r.height().coerceAtLeast(1)
            val origins = FloatArray(lines.size)
            val advances = FloatArray(lines.size)
            val baselines = FloatArray(lines.size)
            lines.forEachIndexed { i, (text, rect) ->
                paint.getTextBounds(text, 0, text.length, r)
                origins[i] = rect.left - r.left.toFloat()
                advances[i] = paint.measureText(text)
                baselines[i] = rect.bottom - r.bottom.toFloat()
            }
            val center = lines.indices.map { origins[it] + advances[it] / 2 }.average().toFloat()
            val align = alignOf(center, t.w)
            val anchor = when (align) {
                Align.LEFT -> origins.average().toFloat()
                Align.CENTER -> center
                Align.RIGHT -> lines.indices.map { origins[it] + advances[it] }.average().toFloat()
            }
            val color = t.opaqueColor(Band(firstRect.top, firstRect.bottom - 1)) ?: Color.WHITE
            val d = dateFields(t, dateRect, align, color) ?: return Unknown
            return TwoLine(
                font = font.font,
                weight = font.weight,
                align = align,
                anchorXFrac = anchor / t.w,
                textSizeFrac = paint.textSize / t.h,
                hoursBaselineFrac = baselines.first() / t.h,
                minutesBaselineFrac = baselines.last() / t.h,
                color = color,
                dateLongMonth = d.long,
                dateAnchorXFrac = d.anchor / t.w,
                dateTextSizeFrac = d.size / t.h,
                dateBaselineFrac = d.baseline / t.h,
                dateColor = d.color,
                oneLine = lines.size == 1,
            )
        }

        internal class DateFields(val long: Boolean, val anchor: Float, val size: Float, val baseline: Float, val color: Int)

        /**
         * The date line at [rect]: sample "Wed 1 February" or "Wed 1 Feb" (whichever has its proportions),
         * sized by its ink height (system stand-in font), anchored by its ink like the clock ([align]).
         */
        internal fun dateFields(t: Template, rect: android.graphics.Rect, align: Align, fallbackColor: Int): DateFields? {
            if (rect.height() <= 0) return null
            val r = android.graphics.Rect()
            val datePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = stackedDateTypeface()
                letterSpacing = DATE_LETTER_SPACING
                textSize = 100f
            }
            val inkAspect = rect.width().toFloat() / rect.height()
            fun aspect(s: String) = r.also { datePaint.getTextBounds(s, 0, s.length, it) }
                .let { it.width().toFloat() / it.height().coerceAtLeast(1) }
            val long = kotlin.math.abs(aspect(SAMPLE_DATE_LONG) - inkAspect) < kotlin.math.abs(aspect(SAMPLE_DATE_SHORT) - inkAspect)
            val sample = if (long) SAMPLE_DATE_LONG else SAMPLE_DATE_SHORT
            datePaint.getTextBounds(sample, 0, sample.length, r)
            val size = 100f * rect.height() / r.height().coerceAtLeast(1)
            datePaint.textSize = size
            datePaint.getTextBounds(sample, 0, sample.length, r)
            val anchor = when (align) {
                Align.LEFT -> rect.left.toFloat()
                Align.CENTER -> rect.exactCenterX()
                Align.RIGHT -> rect.right.toFloat()
            }
            return DateFields(
                long, anchor, size, rect.bottom - r.bottom.toFloat(),
                t.opaqueColor(Band(rect.top, rect.bottom - 1)) ?: fallbackColor,
            )
        }

        /**
         * A one-line clock turned on its side (reading upwards): the template turned upright is a single
         * thin line of words, the time at one end.
         */
        private fun sideways(context: Context, t: Template, key: String): CoverClockLayout {
            // Upright view: a quarter turn clockwise. Leave out the status icons and the camera shortcut.
            val statusBottom = (t.h * SIDEWAYS_STATUS_BOTTOM).toInt()
            val up = IntArray(t.w * t.h)
            for (y in statusBottom until t.h) for (x in 0 until t.w) {
                if (x > t.w * CAMERA_LEFT && y > t.h * CAMERA_TOP) continue
                up[x * t.h + (t.h - 1 - y)] = t.pixel(x, y)
            }
            val u = Template(up, t.h, t.w, cameraCorner = false) // camera already left out
            val bands = u.bands(from = 0, mergeGap = (u.h * 0.006f).toInt())
            val line = bands.singleOrNull()?.takeIf { it.height < u.h * 0.2f } ?: return Unknown

            // Words: column runs split by gaps wider than a quarter of the line height.
            val words = mutableListOf<Pair<Int, Int>>()
            var start = -1; var last = -1
            for (x in 0 until u.w) {
                if (!u.columnHasInk(line, x)) continue
                if (start < 0) start = x
                else if (x - last > line.height / 4) { words += start to last; start = x }
                last = x
            }
            if (start >= 0) words += start to last
            if (words.size < 2) return Unknown

            fun rect(word: Pair<Int, Int>): android.graphics.Rect? {
                val b = u.yRange(line, word.first, word.second) ?: return null
                return android.graphics.Rect(word.first, b.top, word.second + 1, b.bottom + 1)
            }
            // The time is at the end of the line, or (other order) at its start.
            var timeFirst = false
            var timeRect = rect(words.last()) ?: return Unknown
            var font = CoverClockFonts.identifyCached(context, "$key#end") { listOf(u.sample(SAMPLE_TIME, timeRect)) }
            if (font == null) {
                timeFirst = true
                timeRect = rect(words.first()) ?: return Unknown
                font = CoverClockFonts.identifyCached(context, "$key#start") { listOf(u.sample(SAMPLE_TIME, timeRect)) }
                    ?: return Unknown
            }
            val dateWords = if (timeFirst) words.drop(1) else words.dropLast(1)
            val dateBand = u.yRange(line, dateWords.first().first, dateWords.last().second) ?: return Unknown
            val dateLeft = dateWords.first().first
            val dateRight = dateWords.last().second + 1
            val tf = CoverClockFonts.typeface(context, font.font, font.weight) ?: return Unknown

            val r = android.graphics.Rect()
            val timePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = 200f }
            timePaint.getTextBounds(SAMPLE_TIME, 0, SAMPLE_TIME.length, r)
            timePaint.textSize = 200f * timeRect.height() / r.height().coerceAtLeast(1)
            timePaint.getTextBounds(SAMPLE_TIME, 0, SAMPLE_TIME.length, r)
            val timeBaseline = timeRect.bottom - r.bottom.toFloat()

            // Date: Samsung's date font isn't readable; a close system font, stretched to the template's width.
            val datePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = CoverClockStyle.dateTypeface(); textSize = 100f
            }
            val inkAspect = (dateRight - dateLeft).toFloat() / dateBand.height
            fun aspect(s: String) = r.also { datePaint.getTextBounds(s, 0, s.length, it) }
                .let { it.width().toFloat() / it.height().coerceAtLeast(1) }
            val long = kotlin.math.abs(aspect(SAMPLE_DATE_LONG) - inkAspect) < kotlin.math.abs(aspect(SAMPLE_DATE_SHORT) - inkAspect)
            val sampleDate = if (long) SAMPLE_DATE_LONG else SAMPLE_DATE_SHORT
            datePaint.getTextBounds(sampleDate, 0, sampleDate.length, r)
            datePaint.textSize = 100f * dateBand.height / r.height().coerceAtLeast(1)
            datePaint.getTextBounds(sampleDate, 0, sampleDate.length, r)
            val scaleX = (dateRight - dateLeft).toFloat() / r.width().coerceAtLeast(1)
            val dateBaseline = dateBand.bottom + 1f - r.bottom

            val gap = if (timeFirst) dateLeft - timeRect.right else timeRect.left - dateRight
            val anchor = if (timeFirst) timeRect.left else timeRect.right
            val color = u.opaqueColor(Band(timeRect.top, timeRect.bottom - 1)) ?: Color.WHITE
            return Sideways(
                timeFont = font.font,
                timeWeight = font.weight,
                timeTextSizeFrac = timePaint.textSize / u.w,
                timeBaselineFrac = timeBaseline / u.h,
                timeFirst = timeFirst,
                anchorFrac = anchor.toFloat() / u.w,
                gapFrac = gap.toFloat() / u.w,
                dateLongMonth = long,
                dateTextSizeFrac = datePaint.textSize / u.w,
                dateScaleX = scaleX,
                dateBaselineFrac = dateBaseline / u.h,
                color = color,
                dateColor = u.opaqueColor(dateBand) ?: color,
            )
        }

        /** Bottom of the template's sample status icons (the sideways clock starts just below them). */
        private const val SIDEWAYS_STATUS_BOTTOM = 0.065f
        private const val SAMPLE_HOURS = "12"
        private const val SAMPLE_MINUTES = "45"
        private const val SAMPLE_DATE_LONG = "Wed 1 February"
        private const val SAMPLE_DATE_SHORT = "Wed 1 Feb"

        /**
         * Date line over one big time line. Samsung's default draws the digits with its rounded path digits
         * ([Stacked]); other presets use an AOD font for the same layout ([TwoLine] with one line).
         */
        private fun stacked(context: Context, t: Template, line: Band, digits: Band, key: String): CoverClockLayout {
            val (dx0, dx1) = t.xRange(digits) ?: return Unknown
            val (lx0, lx1) = t.xRange(line) ?: return Unknown
            // The date is the first cluster of the line, up to a gap wider than half the line height
            // (then comes the weather icon).
            val dateRight = t.firstClusterRight(line, lx0, gap = (line.height * 0.5f).toInt())
            val dateBand = t.yRange(line, lx0, dateRight) ?: line

            val digitsRect = android.graphics.Rect(dx0, digits.top, dx1 + 1, digits.bottom + 1)
            val samples = { listOf(t.sample(SAMPLE_TIME, digitsRect)) }
            val font = CoverClockFonts.identifyCached(context, "$key#line", samples)
            val pathMatch = CoverClockFonts.pathOverlap(context, samples())
            val paths = pathMatch.overlap
            Log.d(CoverState.TAG, "cover clock one line: font $font, Samsung's path digits $pathMatch")
            if (font != null && font.overlap >= CoverClockFonts.MIN_OVERLAP && font.overlap > paths) {
                val dateRect = android.graphics.Rect(lx0, dateBand.top, dateRight + 1, dateBand.bottom + 1)
                return fontClock(context, t, font, listOf(SAMPLE_TIME to digitsRect), dateRect)
            }
            return Stacked(
                digitsLeftFrac = dx0.toFloat() / t.w,
                digitsBottomFrac = (digits.bottom + 1f) / t.h,
                digitHeightFrac = digits.height.toFloat() / t.h,
                lineCenterXFrac = (lx0 + lx1 + 1) / 2f / t.w,
                dateBaselineFrac = (dateBand.bottom + 1f) / t.h,
                dateHeightFrac = dateBand.height.toFloat() / t.h,
                color = t.opaqueColor(digits) ?: Color.WHITE,
                digitsTopAlpha = t.topAlpha(digits),
                thickness = pathMatch.thickness,
                digitSet = pathMatch.set,
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
    class Template(private val px: IntArray, val w: Int, val h: Int, private val cameraCorner: Boolean = true) {
        fun alpha(x: Int, y: Int) = Color.alpha(px[y * w + x])

        fun pixel(x: Int, y: Int) = px[y * w + x]

        /** Ink box of [b] (right and bottom exclusive). */
        fun inkRect(b: Band): android.graphics.Rect? {
            val (x0, x1) = xRange(b) ?: return null
            return android.graphics.Rect(x0, b.top, x1 + 1, b.bottom + 1)
        }

        fun sample(text: String, rect: android.graphics.Rect) = CoverClockFonts.sample(text, px, w, rect, INK_ALPHA)

        /** Clock ink; the camera shortcut Samsung draws in the bottom-right corner doesn't count. */
        fun ink(x: Int, y: Int) = alpha(x, y) > INK_ALPHA && !inCameraCorner(x, y)

        fun inCameraCorner(x: Int, y: Int) = cameraCorner && x > w * CAMERA_LEFT && y > h * CAMERA_TOP

        /** Mostly opaque clock ink (inside shapes rather than their soft edges). */
        fun solid(x: Int, y: Int) = alpha(x, y) > 127 && !inCameraCorner(x, y)

        /** Clock-ink opacity (0..1) at a point between pixel centres, interpolated; 0 off the template. */
        fun alphaAt(x: Float, y: Float): Float {
            val x0 = kotlin.math.floor(x).toInt(); val y0 = kotlin.math.floor(y).toInt()
            val fx = x - x0; val fy = y - y0
            fun a(px: Int, py: Int) =
                if (px !in 0 until w || py !in 0 until h || inCameraCorner(px, py)) 0f else alpha(px, py) / 255f
            return a(x0, y0) * (1 - fx) * (1 - fy) + a(x0 + 1, y0) * fx * (1 - fy) +
                a(x0, y0 + 1) * (1 - fx) * fy + a(x0 + 1, y0 + 1) * fx * fy
        }

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
