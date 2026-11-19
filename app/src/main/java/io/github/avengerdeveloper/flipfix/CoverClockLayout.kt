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
        /**
         * Set (right-aligned font digits): the date's ink ends this far (fraction of the cover width) past
         * the digit lines' ink, as on Samsung's lock screen, where it moves with the digits' width ("38"
         * reaches further right than the template's "45"); [dateAnchorXFrac] is then unused.
         */
        val dateGapFrac: Float? = null,
        /** Set: the digits are drawn without kerning, with this letter spacing (em) (see CoverClockFonts.identifyFitted). */
        val spacing: Float? = null,
        /** Set: Samsung shades the digits from [color] at the top to another colour at the bottom. */
        val shade: Shade? = null,
        /**
         * Set: how the digit lines align with each other, when that differs from [align]. The lines form a
         * block as wide as the widest line, placed by [align] at [anchorXFrac] (as is the date); within it,
         * Samsung may e.g. centre a narrower line ("17" over "05" in Clock-Stripe, whose "1" is narrow).
         */
        val lineAlign: Align? = null,
    ) : CoverClockLayout

    /**
     * Samsung's top-to-bottom shading of the digits (it tints the template's flat colour to suit the
     * wallpaper, sometimes as a gradient): the top colour at [topFrac] of the cover's height, [bottomColor]
     * at [bottomFrac].
     */
    data class Shade(val bottomColor: Int, val topFrac: Float, val bottomFrac: Float) {
        /** The colour halfway down, for clocks drawn in one colour. */
        fun middle(top: Int): Int = Color.rgb(
            (Color.red(top) + Color.red(bottomColor)) / 2,
            (Color.green(top) + Color.green(bottomColor)) / 2,
            (Color.blue(top) + Color.blue(bottomColor)) / 2,
        )
    }

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
        /** Set: the time is drawn without kerning, with this letter spacing (em), as TwoLine.spacing. */
        val timeSpacing: Float? = null,
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

    /** How the clock lines up on the cover, where the style says (null: judge from where its ink sits). */
    fun alignment(): Align? = when (this) {
        is TwoLine -> align
        else -> null
    }

    /** The digits' and the date's colours as the template has them, or null (a style FlipFix doesn't recolour). */
    fun inkColors(): Pair<Int, Int>? = when (this) {
        is Horizontal -> style.color to style.color
        is TwoLine -> color to dateColor
        is Stacked -> color to color
        is Sideways -> color to dateColor
        else -> null
    }

    /**
     * This clock in Samsung's tint of it (see CoverFaceMatcher.activeColors); styles without [inkColors]
     * unchanged. Only font digits take the [shade]; other styles are drawn in its middle colour.
     */
    fun withColors(digits: Int, date: Int, shade: Shade? = null): CoverClockLayout = when (this) {
        is Horizontal -> copy(style = style.copy(color = shade?.middle(digits) ?: digits))
        is TwoLine -> copy(color = digits, dateColor = date, shade = shade)
        is Stacked -> copy(color = shade?.middle(digits) ?: digits)
        is Sideways -> copy(color = shade?.middle(digits) ?: digits, dateColor = date)
        else -> this
    }

    /**
     * The weight the digits are drawn at, for styles drawn in a font with weights (null: the style's digits
     * have one look). The template only shows the weight the preset was made with; Samsung may draw it
     * bolder or lighter now (seen on a Flip6 on One UI 9: thin template digits shown bold).
     */
    fun digitWeight(): Int? = when (this) {
        is TwoLine -> weight.takeIf { paths == null && font.isNotEmpty() }
        is Sideways -> timeWeight
        else -> null
    }

    /** This clock with its colours left out: clocks equal but for their colours draw the same shapes. */
    fun uncoloured(): CoverClockLayout = when (this) {
        is Horizontal -> copy(style = style.copy(color = 0))
        is TwoLine -> copy(color = 0, dateColor = 0, shade = null)
        is Stacked -> copy(color = 0)
        is Sideways -> copy(color = 0, dateColor = 0)
        else -> this
    }

    /** The font the digits are drawn in, for styles drawn in a font (see CoverClockFonts.typeface). */
    fun digitFont(): String? = when (this) {
        is TwoLine -> font.takeIf { paths == null && font.isNotEmpty() }
        is Sideways -> timeFont
        else -> null
    }

    /** This clock with its digits at [weight] (see [digitWeight]); other styles unchanged. */
    fun withDigitWeight(weight: Int): CoverClockLayout = when (this) {
        is TwoLine -> if (paths == null && font.isNotEmpty()) copy(weight = weight) else this
        is Sideways -> copy(timeWeight = weight)
        else -> this
    }

    /**
     * Whether this is hours over minutes in a font, whose lines Samsung may space differently from the
     * template (seen on a Flip6 on One UI 9: thin stacked templates shown bold and bigger, with the hours and
     * the date closer to the minutes, the date at the template's size). See [withLines].
     */
    fun hasLines(): Boolean = this is TwoLine && !oneLine && paths == null && font.isNotEmpty()

    /**
     * This clock (see [hasLines]) with its hours line moved down by [hoursDy] and its date by [dateDy]
     * (fractions of the cover's height, before any resizing), and its date resized by [dateSize]; other
     * styles unchanged.
     */
    fun withLines(hoursDy: Float, dateDy: Float, dateSize: Float): CoverClockLayout =
        if (this is TwoLine && hasLines()) {
            copy(
                hoursBaselineFrac = hoursBaselineFrac + hoursDy,
                dateBaselineFrac = dateBaselineFrac + dateDy,
                dateTextSizeFrac = dateTextSizeFrac * dateSize,
            )
        } else {
            this
        }

    companion object {
        const val TEMPLATE_ROOT = "/data/overlays/coverwallpaper/0"
        private const val TEMPLATE_NAME = "cover_clock.png"
        private const val INK_ALPHA = 60

        /** Camera shortcut corner of the templates (left and top edges, as fractions). */
        private const val CAMERA_LEFT = 0.80f
        private const val CAMERA_TOP = 0.70f

        /** Clock lines start below this share of the template's height (above: the sample status icons). */
        private const val MIN_LINE_TOP = 0.05f

        /** Where the sample status icons start and end at the latest (see Template.statusBottom). */
        private const val STATUS_TOP = 0.06f
        private const val STATUS_BOTTOM = 0.09f

        /**
         * Width Samsung's weather (gap + icon + temperature) takes after the date, per date ascender
         * height (Flip8 lock screen: 197 px after a 42.5 px-tall date).
         */
        const val WEATHER_SLOT_PER_DATE_HEIGHT = 4.64f

        /** Samsung's sample text in the template: the digits are positioned and sized from it. */
        const val SAMPLE_TIME = "12:45"

        /** Letters with ascenders: the template's date ("Wed 1 Feb") is sized by its tallest letters. */
        const val ASCENDERS = "bd"

        /**
         * The clock of the active cover preset (see CoverFaceMatcher): its template's, or the one found on the
         * lock screen itself for a preset without a template it can read (CoverFaceMatcher.FALLBACK); [Unknown]
         * for no clock.
         */
        fun load(context: Context): CoverClockLayout {
            val parsed = CoverFaceMatcher.activeFallback(context) ?: run {
                val file = CoverFaceMatcher.activeTemplate(context) ?: return Unknown
                val variants = variants(context, file)
                variants.getOrNull(CoverFaceMatcher.activeVariant(context)) ?: variants.firstOrNull() ?: return Unknown
            }
            val weighed = CoverFaceMatcher.activeWeight(context)?.let { parsed.withDigitWeight(it) } ?: parsed
            val layout = CoverFaceMatcher.activeLines(context)?.let { weighed.withLines(it.hoursDy, it.dateDy, it.dateSize) } ?: weighed
            val colors = CoverFaceMatcher.activeColors(context) ?: return layout
            return layout.withColors(colors.digits, colors.date, colors.shade)
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

        /**
         * Parsed once per template file and modification time: presets rarely change, parsing is slow. Each
         * template gives its clock as one or more [variants].
         */
        private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<CoverClockLayout>>>()

        /** The clock of [file] (its first variant), or [Unknown]. */
        fun parse(context: Context, file: File): CoverClockLayout = variants(context, file).firstOrNull() ?: Unknown

        /**
         * The ways [file]'s clock may be drawn: one, for a clock in a style or font FlipFix knows; for a font it
         * doesn't, the closest AOD font and the generic font (see [fontChoices]): CoverFaceMatcher keeps the
         * one that looks most like Samsung's lock screen. Empty if the template shows no clock FlipFix can read.
         */
        fun variants(context: Context, file: File): List<CoverClockLayout> {
            val stamp = file.lastModified()
            parsed[file.path]?.let { (at, layouts) -> if (at == stamp) return layouts }
            val layouts = runCatching { fromTemplate(context, file) }
                .onFailure { Log.w(CoverState.TAG, "cover clock template unreadable: ${file.parentFile?.name}", it) }
                .getOrNull().orEmpty()
            parsed[file.path] = stamp to layouts
            return layouts
        }

        private fun fromTemplate(context: Context, file: File): List<CoverClockLayout> {
            val bmp = BitmapFactory.decodeFile(file.path) ?: return emptyList()
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            bmp.recycle()
            val t = Template(px, w, h)

            // Ink bands below the sample status icons (top 10%); tiny gaps (dots, icons) are merged.
            val bands = t.bands(from = (h * 0.10f).toInt(), mergeGap = (h * 0.02f).toInt())
            val key = "${file.path}@${file.lastModified()}"
            val layouts = when {
                // A one-line clock is a thin band; a band filling the template is an illustrated face.
                bands.size == 1 && bands[0].height < h * 0.2f ->
                    listOfNotNull(CoverClockStyle.fromBand(t, bands[0])?.let { Horizontal(it) })
                bands.size == 2 && bands[1].height > bands[0].height * 1.8f ->
                    stacked(context, t, bands[0], bands[1], key)
                else -> twoLine(context, t, key)
                    .ifEmpty { oneLine(context, t, key) }
                    .ifEmpty { sideways(context, t, key) }
                    .ifEmpty { listOf(CoverClockAnalog.parse(t, dateFont(context))).filter { it != Unknown } }
            }
            Log.d(CoverState.TAG, "cover clock from ${file.parentFile?.name}: bands=$bands -> ${layouts.ifEmpty { listOf(Unknown) }.joinToString(" or ")}")
            return layouts
        }

        /**
         * Fonts to draw a clock in whose sample digits are [samples] (keyed [key]; [known]: the AOD font
         * that covers them best, from CoverClockFonts.identify). An AOD font that fits them, as such or drawn
         * the way Samsung draws some fonts ([CoverClockFonts.identifyFitted]), is the one choice: Samsung's
         * own. Otherwise both the closest AOD font, if it comes [CLOSE_OVERLAP] close, and the generic font
         * ([CoverClockFonts.generic]): the template may be older than how Samsung draws the clock now, and
         * the lock screen shows which looks more like it. Empty: the samples aren't digits.
         */
        private fun fontChoices(
            context: Context, key: String, samples: () -> List<CoverClockFonts.Sample>, known: CoverClockFonts.Match?,
        ): List<CoverClockFonts.Match> {
            if (known != null && known.overlap >= CoverClockFonts.MIN_OVERLAP) return listOf(known)
            CoverClockFonts.identifyFittedCached(context, key, samples)?.let { return listOf(it) }
            return listOfNotNull(
                known?.takeIf { it.overlap >= CLOSE_OVERLAP },
                CoverClockFonts.genericCached(context, key, samples),
            )
        }

        /**
         * An AOD font covering a template's digits this well (but less than CoverClockFonts.MIN_OVERLAP) may
         * still be the preset's (seen on a Flip6: Capriola at 0.76 on one vertical clock, 0.80 on its sibling).
         */
        private const val CLOSE_OVERLAP = 0.65f

        /** Hours over minutes with a date line: three bands, two equal digit lines and a small date line. */
        private fun twoLine(context: Context, t: Template, key: String): List<CoverClockLayout> {
            // Here the date sits close to the digits: only merge gaps within a line.
            val bands = t.bands(from = (t.h * 0.10f).toInt(), mergeGap = (t.h * 0.006f).toInt())
            if (bands.size != 3) return emptyList()
            fun similar(a: Band, b: Band) = a.height >= b.height * 0.85f && a.height <= b.height * 1.18f
            val hours: Band; val minutes: Band; val date: Band
            when {
                similar(bands[1], bands[2]) && bands[0].height < bands[1].height * 0.45f -> {
                    date = bands[0]; hours = bands[1]; minutes = bands[2]
                }
                similar(bands[0], bands[1]) && bands[2].height < bands[0].height * 0.45f -> {
                    hours = bands[0]; minutes = bands[1]; date = bands[2]
                }
                else -> return emptyList()
            }
            val hoursRect = t.inkRect(hours) ?: return emptyList()
            val minutesRect = t.inkRect(minutes) ?: return emptyList()
            val dateRect = t.inkRect(date) ?: return emptyList()
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
                val d = dateFields(t, dateRect, align, color, dateFont(context)) ?: return emptyList()
                return listOf(TwoLine(
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
                ))
            }
            val lines = listOf(SAMPLE_HOURS to hoursRect, SAMPLE_MINUTES to minutesRect)
            return fontChoices(context, key, samples, font).mapNotNull { fontClock(context, t, it, lines, dateRect) }
        }

        /**
         * One line of digits with a date line above or below it ("12:45" under or over "Wed 1 February"), the
         * two close together (a few pixels apart in the template, so only gaps that small split them). The
         * date is the line that is far wider for its height.
         */
        private fun oneLine(context: Context, t: Template, key: String): List<CoverClockLayout> {
            val bands = t.bands(from = (t.h * 0.10f).toInt(), mergeGap = maxOf(2, (t.h * 0.003f).toInt()))
            if (bands.size != 2) return emptyList()
            val rects = bands.map { t.inkRect(it) ?: return emptyList() }
            fun aspect(r: android.graphics.Rect) = r.width().toFloat() / r.height().coerceAtLeast(1)
            val dateFirst = aspect(rects[0]) > aspect(rects[1])
            val (dateRect, digitsRect) = if (dateFirst) rects[0] to rects[1] else rects[1] to rects[0]
            if (aspect(dateRect) < aspect(digitsRect) * DATE_ASPECT) return emptyList()
            if (dateFirst) return stacked(context, t, bands[0], bands[1], "$key#tight", strict = true)
            val samples = { listOf(t.sample(SAMPLE_TIME, digitsRect)) }
            val fonts = fontChoices(context, "$key#below", samples, CoverClockFonts.identifyCached(context, "$key#below", samples))
            Log.d(CoverState.TAG, "cover clock one line, date below: fonts $fonts")
            return fonts.mapNotNull { fontClock(context, t, it, listOf(SAMPLE_TIME to digitsRect), dateRect) }
        }

        /** A date line is at least this much wider, for its height, than the digits' line. */
        private const val DATE_ASPECT = 1.5f

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
        ): CoverClockLayout? {
            val tf = CoverClockFonts.typeface(context, font.font, font.weight) ?: return null
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                font.spacing?.let { fontFeatureSettings = CoverClockFonts.NO_KERNING; letterSpacing = it }
            }
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
                // Where the ink really starts when drawn: with letter spacing, the bounds can be pixels off.
                val inkLeft = drawnInkLeft(paint, text) ?: r.left.toFloat()
                if (kotlin.math.abs(inkLeft - r.left) >= 1f) {
                    Log.d(CoverState.TAG, "clock line \"$text\": bounds start at ${r.left}, drawn ink at $inkLeft")
                }
                origins[i] = rect.left - inkLeft
                advances[i] = paint.measureText(text)
                baselines[i] = rect.bottom - r.bottom.toFloat()
            }
            val centers = lines.indices.map { origins[it] + advances[it] / 2 }
            val ends = lines.indices.map { origins[it] + advances[it] }
            // Where the clock sits on the cover (the date is aligned the same way).
            val align = alignOf(centers.average().toFloat(), t.w)
            // Lines of different widths (Clock-Stripe's "12" is narrower than its "45") show how Samsung
            // aligns them with each other: a clock at the left may still have its lines centred on each
            // other. Lines of one width don't show it, so they follow where the clock sits.
            val lineAlign = if (lines.size > 1 && advances.max() - advances.min() > advances.max() * SAME_WIDTH) {
                fun spread(xs: List<Float>) = xs.max() - xs.min()
                listOf(Align.LEFT to spread(origins.toList()), Align.CENTER to spread(centers), Align.RIGHT to spread(ends))
                    .minBy { it.second }.first
            } else {
                align
            }
            // The block of lines (as wide as the widest): its left edge per line, then its anchor.
            val blockW = advances.max()
            val blockLeft = lines.indices.map { origins[it] - (blockW - advances[it]) * share(lineAlign) }.average().toFloat()
            val anchor = blockLeft + blockW * share(align)
            val color = t.opaqueColor(Band(firstRect.top, firstRect.bottom - 1)) ?: Color.WHITE
            val d = dateFields(t, dateRect, align, color, dateFont(context)) ?: return null
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
                dateGapFrac = if (align == Align.RIGHT && lineAlign == Align.RIGHT) {
                    (d.anchor - lines.maxOf { it.second.right }) / t.w
                } else {
                    null
                },
                spacing = font.spacing,
                lineAlign = lineAlign.takeIf { it != align },
            )
        }

        /** Digit lines within this share of each other's width count as one width (see [fontClock]). */
        private const val SAME_WIDTH = 0.02f

        /** First column of [text]'s ink (alpha as the template's ink) when [paint] draws it from x = 0, or null. */
        private fun drawnInkLeft(paint: android.graphics.Paint, text: String): Float? {
            val r = android.graphics.Rect()
            paint.getTextBounds(text, 0, text.length, r)
            val pad = (paint.textSize / 2).toInt() + 2
            val w = (paint.measureText(text) + r.width() + 2 * pad).toInt()
            val h = r.height() + 4
            if (w <= 0 || h <= 4) return null
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp).drawText(text, pad.toFloat(), (2 - r.top).toFloat(), paint)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            for (x in 0 until w) for (y in 0 until h) {
                if (px[y * w + x] ushr 24 > INK_ALPHA) return (x - pad).toFloat()
            }
            return null
        }

        /** How far across its box (0 left, 1 right) something aligned [align] sits. */
        internal fun share(align: Align) = when (align) {
            Align.LEFT -> 0f
            Align.CENTER -> 0.5f
            Align.RIGHT -> 1f
        }

        internal class DateFields(val long: Boolean, val anchor: Float, val size: Float, val baseline: Float, val color: Int)

        /**
         * The date line at [rect]: sample "Wed 1 February" or "Wed 1 Feb" (whichever has its proportions),
         * sized by its ink height in [font], anchored by its ink like the clock ([align]).
         */
        internal fun dateFields(
            t: Template, rect: android.graphics.Rect, align: Align, fallbackColor: Int, font: DateFont,
        ): DateFields? {
            if (rect.height() <= 0) return null
            val r = android.graphics.Rect()
            val datePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font.typeface
                letterSpacing = font.letterSpacing
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
        private fun sideways(context: Context, t: Template, key: String): List<CoverClockLayout> {
            // Upright view: a quarter turn clockwise. Leave out the status icons and the camera shortcut.
            val statusBottom = maxOf((t.h * SIDEWAYS_STATUS_BOTTOM).toInt(), t.statusBottom())
            val up = IntArray(t.w * t.h)
            for (y in statusBottom until t.h) for (x in 0 until t.w) {
                if (x > t.w * CAMERA_LEFT && y > t.h * CAMERA_TOP) continue
                up[x * t.h + (t.h - 1 - y)] = t.pixel(x, y)
            }
            val u = Template(up, t.h, t.w, cameraCorner = false) // camera already left out
            val bands = u.bands(from = 0, mergeGap = (u.h * 0.006f).toInt())
            val line = bands.singleOrNull()?.takeIf { it.height < u.h * 0.2f } ?: return emptyList()

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
            if (words.size < 2) return emptyList()

            fun rect(word: Pair<Int, Int>): android.graphics.Rect? {
                val b = u.yRange(line, word.first, word.second) ?: return null
                return android.graphics.Rect(word.first, b.top, word.second + 1, b.bottom + 1)
            }
            // The time is at the end of the line, or (other order) at its start: where an AOD font fits it,
            // else the end whose fonts (see [fontChoices]) fit it better; the date's end fits none.
            val endRect = rect(words.last()) ?: return emptyList()
            val startRect = rect(words.first()) ?: return emptyList()
            fun samples(end: Boolean) = { listOf(u.sample(SAMPLE_TIME, if (end) endRect else startRect)) }
            fun keyOf(end: Boolean) = "$key#${if (end) "end" else "start"}"
            fun known(end: Boolean) = CoverClockFonts.identifyCached(context, keyOf(end), samples(end))
            val knownEnd = known(end = true)
            val (timeFirst, fonts) = if (knownEnd != null && knownEnd.overlap >= CoverClockFonts.MIN_OVERLAP) {
                false to listOf(knownEnd)
            } else {
                val atEnd = fontChoices(context, keyOf(true), samples(true), knownEnd)
                val atStart = fontChoices(context, keyOf(false), samples(false), known(end = false))
                if ((atStart.maxOfOrNull { it.overlap } ?: 0f) > (atEnd.maxOfOrNull { it.overlap } ?: 0f)) true to atStart else false to atEnd
            }
            Log.d(CoverState.TAG, "cover clock sideways: time ${if (timeFirst) "first" else "last"}, fonts $fonts")
            val timeRect = if (timeFirst) startRect else endRect
            val dateWords = if (timeFirst) words.drop(1) else words.dropLast(1)
            val dateBand = u.yRange(line, dateWords.first().first, dateWords.last().second) ?: return emptyList()
            return fonts.mapNotNull { sidewaysIn(context, u, it, timeFirst, timeRect, dateWords, dateBand) }
        }

        /** [sideways]' clock with the time ([timeRect] in the upright template [u]) drawn in [font]. */
        private fun sidewaysIn(
            context: Context, u: Template, font: CoverClockFonts.Match, timeFirst: Boolean,
            timeRect: android.graphics.Rect, dateWords: List<Pair<Int, Int>>, dateBand: Band,
        ): CoverClockLayout? {
            val dateLeft = dateWords.first().first
            val dateRight = dateWords.last().second + 1
            val tf = CoverClockFonts.typeface(context, font.font, font.weight) ?: return null

            val r = android.graphics.Rect()
            val timePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                textSize = 200f
                font.spacing?.let { fontFeatureSettings = CoverClockFonts.NO_KERNING; letterSpacing = it }
            }
            timePaint.getTextBounds(SAMPLE_TIME, 0, SAMPLE_TIME.length, r)
            timePaint.textSize = 200f * timeRect.height() / r.height().coerceAtLeast(1)
            timePaint.getTextBounds(SAMPLE_TIME, 0, SAMPLE_TIME.length, r)
            val timeBaseline = timeRect.bottom - r.bottom.toFloat()

            // Date: Samsung's date font, stretched to the template's width (a no-op unless the stand-in is used).
            val dateFont = dateFont(context)
            val datePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = dateFont.typeface; letterSpacing = dateFont.letterSpacing; textSize = 100f
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
                timeSpacing = font.spacing,
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
         * [strict] (found by [oneLine]): path digits only if they really fit, then a fitted font, else nothing.
         */
        private fun stacked(
            context: Context, t: Template, line: Band, digits: Band, key: String, strict: Boolean = false,
        ): List<CoverClockLayout> {
            val (dx0, dx1) = t.xRange(digits) ?: return emptyList()
            val (lx0, lx1) = t.xRange(line) ?: return emptyList()
            // The date is the first cluster of the line, up to a gap wider than half the line height
            // (then comes the weather icon). A big date alone on its line (found by [oneLine]) can have
            // word gaps that wide: there, only a gap of a full line height ends it.
            val dateRight = t.firstClusterRight(line, lx0, gap = (line.height * (if (strict) 1f else 0.5f)).toInt())
            val dateBand = t.yRange(line, lx0, dateRight) ?: line

            val digitsRect = android.graphics.Rect(dx0, digits.top, dx1 + 1, digits.bottom + 1)
            val samples = { listOf(t.sample(SAMPLE_TIME, digitsRect)) }
            val font = CoverClockFonts.identifyCached(context, "$key#line", samples)
            val pathMatch = CoverClockFonts.pathOverlap(context, samples())
            val paths = pathMatch.overlap
            Log.d(CoverState.TAG, "cover clock one line: font $font, Samsung's path digits $pathMatch")
            val dateRect = android.graphics.Rect(lx0, dateBand.top, dateRight + 1, dateBand.bottom + 1)
            val lines = listOf(SAMPLE_TIME to digitsRect)
            if (font != null && font.overlap >= CoverClockFonts.MIN_OVERLAP && font.overlap > paths) {
                return listOfNotNull(fontClock(context, t, font, lines, dateRect))
            }
            if (strict && paths < CoverClockFonts.MIN_OVERLAP) {
                return fontChoices(context, "$key#line", samples, font).mapNotNull { fontClock(context, t, it, lines, dateRect) }
            }
            return listOf(Stacked(
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
            ))
        }

        @Volatile private var dateFontCache: DateFont? = null

        /**
         * The date font of the Z Flip8 cover clocks (every style but the Z Flip6 one-line clock):
         * SamsungOne UI, which ships in Samsung's font package and is loaded from there at runtime (nothing
         * of Samsung's is bundled), at its natural spacing. Without that package, the closest system
         * stand-in: Roboto Flex, slightly tightened.
         */
        fun dateFont(context: Context): DateFont {
            dateFontCache?.let { return it }
            val samsung = runCatching {
                val fonts = context.createPackageContext(SAMSUNG_ONE_PACKAGE, 0)
                Typeface.Builder(fonts.assets, SAMSUNG_ONE_DATE_FONT).build()
            }.onFailure { Log.w(CoverState.TAG, "SamsungOne date font unavailable, using Roboto Flex", it) }.getOrNull()
            val font = if (samsung != null) {
                DateFont(samsung, 0f)
            } else {
                val flex = runCatching {
                    Typeface.Builder(File("/system/fonts/RobotoFlex-Regular.ttf")).setFontVariationSettings("'wght' 375").build()
                }.getOrNull()
                DateFont(flex ?: Typeface.DEFAULT, -0.02f)
            }
            Log.d(CoverState.TAG, "date font: ${if (samsung != null) "SamsungOne UI" else "Roboto Flex stand-in"}")
            return font.also { dateFontCache = it }
        }

        private const val SAMSUNG_ONE_PACKAGE = "com.monotype.android.font.samsungone"
        private const val SAMSUNG_ONE_DATE_FONT = "fonts/SamsungOneUI-Regular.ttf"
    }

    /** A date line's font, with the letter spacing that makes it match Samsung's (see [dateFont]). */
    class DateFont(val typeface: Typeface, val letterSpacing: Float)

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

        /**
         * Rows with ink from [from] down, split where more than [mergeGap] rows are empty. A clock line that
         * reaches up past [from] (the status icons' strip) keeps its top, as long as its ink runs on unbroken
         * and not into the top [MIN_LINE_TOP] of the template (where the icons are).
         */
        fun bands(from: Int, mergeGap: Int): List<Band> {
            fun rowHasInk(y: Int) = (0 until w).any { ink(it, y) }
            var top = from
            if (from > 0 && from < h && rowHasInk(from)) {
                var y = from
                while (y > 0 && rowHasInk(y - 1)) y--
                if (y >= h * MIN_LINE_TOP) top = y
            }
            val out = mutableListOf<Band>()
            var start = -1
            var lastInk = -1
            for (y in top until h) {
                val any = rowHasInk(y)
                if (any) {
                    if (start < 0) start = y
                    else if (y - lastInk > mergeGap) { out += Band(start, lastInk); start = y }
                    lastInk = y
                }
            }
            if (start >= 0) out += Band(start, lastInk)
            return out
        }

        /**
         * The row below the template's sample status icons: the first band of ink, if it starts within the
         * top [STATUS_TOP] and ends within the top [STATUS_BOTTOM] of the template; 0 if there's no such band.
         * (On a Flip6 they end 7 to 8% down, on a Flip8 above 6.5%.)
         */
        fun statusBottom(): Int {
            val gap = maxOf(2, (h * 0.005f).toInt())
            val limit = (h * STATUS_BOTTOM).toInt()
            var top = -1; var last = -1
            for (y in 0..minOf(h - 1, limit + gap)) {
                if ((0 until w).any { ink(it, y) }) {
                    if (top < 0) top = y
                    last = y
                } else if (top >= 0 && y - last > gap) {
                    break
                }
            }
            return if (top in 0..(h * STATUS_TOP).toInt() && last < limit) last + 1 else 0
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
