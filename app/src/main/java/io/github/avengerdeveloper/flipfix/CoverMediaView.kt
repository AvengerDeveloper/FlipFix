package io.github.avengerdeveloper.flipfix

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Samsung's cover "Now bar" music pill, redrawn for the unlocked cover face: round artwork, a
 * "Title • Artist" line (scrolling when too long), previous / play-pause / next, a background tinted from
 * the artwork, and a pink "hills" visualiser along the bottom while playing.
 *
 * Shows the session that is playing, else the most recently paused one; hides when nothing is.
 * Geometry is in Z Flip6 cover pixels (748x720) measured from Samsung's locked cover, scaled to the screen.
 */
class CoverMediaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, FlipNotificationListener::class.java)
    private var controller: MediaController? = null

    private var title = ""
    private var art: Bitmap? = null
    private var playing = false
    private var bgTop = DEFAULT_BG_TOP
    private var bgBottom = DEFAULT_BG_BOTTOM
    private var waveEdge = DEFAULT_WAVE
    private var waveLight = lighten(DEFAULT_WAVE, 0.62f)

    private val pill = RectF()
    private val path = Path()
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.FILL_AND_STROKE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = titleTypeface()
    }
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    // Shaders kept between frames: the pill redraws many times a second while it moves. The colours follow
    // the artwork (cleared in tintFromArt); the title's fades never move.
    private var bgShader: Shader? = null
    private var waveShader: Shader? = null
    private var artShader: Shader? = null
    private var artShaderFor: Bitmap? = null
    private val fadeLeft by lazy {
        LinearGradient(TITLE_LEFT, 0f, TITLE_LEFT + FADE, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val fadeRight by lazy {
        LinearGradient(TITLE_RIGHT - FADE, 0f, TITLE_RIGHT, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downInside = false
    private val startedAt = SystemClock.uptimeMillis()
    private var titleSince = startedAt

    // Hit areas in screen coordinates, refreshed on every draw.
    private val prevHit = RectF()
    private val playHit = RectF()
    private val nextHit = RectF()
    private val openHit = RectF()

    /** Reference-to-screen transform used for drawing (density scale, bottom-left anchor). */
    private var scale = 1f
    private var originY = 0f

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pickController(it) }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = readMetadata()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // A session that stopped may hand over to another one.
            if (state == null || !isShowable(state)) pickController(activeSessions()) else readState()
        }
        override fun onSessionDestroyed() = pickController(activeSessions())
    }

    private val mediaNotificationsObserver: () -> Unit = { post { pickController(activeSessions()) } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching { sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent) }
            .onFailure { Log.w(CoverState.TAG, "media sessions unavailable (notification access?)", it) }
        CoverState.observeMediaNotifications(mediaNotificationsObserver)
        pickController(activeSessions())
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameQueued = false
        CoverState.stopObservingMediaNotifications(mediaNotificationsObserver)
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        super.onDetachedFromWindow()
    }

    private fun activeSessions(): List<MediaController> =
        runCatching { sessions.getActiveSessions(listenerComponent) }.getOrDefault(emptyList())

    /**
     * Playing first, else paused (most recent first, as the system orders sessions). Like Samsung's Now bar,
     * a paused player only counts while its app still shows its media notification: apps such as Spotify
     * keep a paused session alive long after they remove the notification.
     */
    private fun pickController(list: List<MediaController>?) {
        val all = list.orEmpty()
        val media = CoverState.mediaNotifications
        val chosen = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull {
                it.playbackState?.let(::isShowable) == true &&
                    (it.sessionToken in media.sessions || it.packageName in media.packages)
            }
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = chosen
            chosen?.registerCallback(controllerCallback)
            Log.d(CoverState.TAG, "media pill: ${chosen?.packageName ?: "hidden"}")
        }
        readMetadata()
        readState()
    }

    private fun isShowable(state: PlaybackState) = state.state in setOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    )

    private fun readMetadata() {
        val m = controller?.metadata
        val t = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.description?.title?.toString().orEmpty()
        val a = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m?.description?.subtitle?.toString()
        val newTitle = listOfNotNull(t.takeIf { it.isNotBlank() }, a?.takeIf { it.isNotBlank() }).joinToString(" • ")
        if (newTitle != title) { title = newTitle; titleSince = SystemClock.uptimeMillis() }
        val newArt = m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (newArt !== art) {
            art = newArt
            tintFromArt(newArt)
        }
        invalidate()
    }

    private fun readState() {
        playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        invalidate()
    }

    /**
     * Samsung tints the pill from the artwork's dominant hue: a dark gradient (HSV value 0.255 -> 0.15,
     * saturation ~0.31) and the visualiser in a vivid version of the same hue (red art -> red wave,
     * pink/purple art -> pink wave, as seen on One UI 9).
     *
     * The dominant hue is a circular mean (hue is an angle: averaging 320 and 30 degrees must give ~355,
     * not 175), weighted by how colourful each pixel is, so small bright details don't take over.
     */
    private fun tintFromArt(bmp: Bitmap?) {
        if (bmp == null) {
            bgTop = DEFAULT_BG_TOP; bgBottom = DEFAULT_BG_BOTTOM; waveEdge = DEFAULT_WAVE
        } else {
            val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
            var vx = 0.0; var vy = 0.0; var weight = 0.0; var satSum = 0.0; var n = 0
            val hsv = FloatArray(3)
            for (y in 0 until small.height) for (x in 0 until small.width) {
                Color.colorToHSV(small.getPixel(x, y), hsv)
                if (hsv[2] < 0.12f) continue // near-black says nothing about the hue
                val w = (hsv[1] * hsv[2]).toDouble()
                val rad = Math.toRadians(hsv[0].toDouble())
                vx += kotlin.math.cos(rad) * w
                vy += kotlin.math.sin(rad) * w
                weight += w
                satSum += hsv[1]; n++
            }
            if (small !== bmp) small.recycle()
            val colourful = weight > 0 && kotlin.math.hypot(vx, vy) / weight > 0.15
            val hue = ((Math.toDegrees(kotlin.math.atan2(vy, vx)) + 360) % 360).toFloat()
            val sat = if (colourful && n > 0) min(0.32f, (satSum / n).toFloat()) else 0f
            bgTop = Color.HSVToColor(floatArrayOf(hue, sat, 0.255f))
            bgBottom = Color.HSVToColor(floatArrayOf(hue, sat, 0.15f))
            waveEdge = if (colourful) Color.HSVToColor(floatArrayOf(hue, 0.75f, 1f)) else DEFAULT_WAVE
        }
        waveLight = lighten(waveEdge, 0.45f)
        bgShader = null
        waveShader = null
    }

    /**
     * While something moves, the pill redraws at a capped rate rather than at every refresh of the cover
     * (up to 120 a second): 30 a second for the visualiser alone, 60 while the title scrolls. Each redraw
     * draws the whole pill again.
     */
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }
    private var frameQueued = false
    private var lastFrameNanos = 0L

    private fun scheduleFrame() {
        if (frameQueued) return
        frameQueued = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        frameQueued = false
        if (!isAttachedToWindow) return
        val interval = if (titleScrolls) SCROLL_FRAME_NANOS else WAVE_FRAME_NANOS
        // Within a few ms counts as due: frames land on the display's refresh grid.
        if (frameTimeNanos - lastFrameNanos >= interval - FRAME_SLACK_NANOS) {
            lastFrameNanos = frameTimeNanos
            invalidate()
        } else {
            scheduleFrame()
        }
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (controller == null || title.isEmpty()) return
        val bounds = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        val screenH = (bounds?.height() ?: height).toFloat()
        val loc = IntArray(2).also(::getLocationOnScreen)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        // Samsung keeps the pill the same physical size and margins on every cover: draw in the reference
        // (Z Flip6) coordinates, scaled by density and anchored to the bottom-left corner.
        scale = resources.displayMetrics.density / REF_DENSITY
        // On taller covers Samsung raises the pill a little more than a fixed margin would
        // (Flip6 41 dp, Flip8 45 dp from the bottom): ~4.6% of the extra height.
        val density = resources.displayMetrics.density
        val extraDp = ((screenH / density) - REF_H / REF_DENSITY).coerceAtLeast(0f) * TALL_COVER_LIFT
        originY = screenH - REF_H * scale - extraDp * density
        canvas.translate(0f, originY)
        canvas.scale(scale, scale)
        val sx = 1f
        val sy = 1f

        pill.set(PILL_L * sx, PILL_T * sy, PILL_R * sx, PILL_B * sy)
        val radius = pill.height() / 2
        clip.reset(); clip.addRoundRect(pill, radius, radius, Path.Direction.CW)

        // Background gradient.
        fill.shader = bgShader
            ?: LinearGradient(0f, pill.top, 0f, pill.bottom, bgTop, bgBottom, Shader.TileMode.CLAMP).also { bgShader = it }
        canvas.drawPath(clip, fill)
        fill.shader = null

        if (playing) drawWave(canvas, sx, sy)
        drawArt(canvas, sx, sy)
        drawTitle(canvas, sx, sy)
        drawControls(canvas, sx, sy)
        canvas.restore()

        openHit.set(pill.left, pill.top, ART_CX * sx + ART_R * sx, pill.bottom)
        if (playing || titleScrolls) scheduleFrame()
    }

    private fun drawArt(canvas: Canvas, sx: Float, sy: Float) {
        val cx = ART_CX * sx
        val cy = ART_CY * sy
        val r = ART_R * sy
        val bmp = art
        if (bmp == null) {
            fill.color = lighten(bgTop, 0.15f)
            canvas.drawCircle(cx, cy, r, fill)
            return
        }
        if (artShaderFor !== bmp) {
            val scale = 2 * r / min(bmp.width, bmp.height)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - bmp.width * scale / 2, cy - bmp.height * scale / 2)
            }
            artShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            artShaderFor = bmp
        }
        artPaint.shader = artShader
        canvas.drawCircle(cx, cy, r, artPaint)
    }

    private var titleScrolls = false

    private fun drawTitle(canvas: Canvas, sx: Float, sy: Float) {
        titlePaint.textSize = TITLE_SIZE * sy
        val left = TITLE_LEFT * sx
        val right = TITLE_RIGHT * sx
        val baseline = TITLE_BASELINE * sy
        val w = titlePaint.measureText(title)
        val area = right - left
        titleScrolls = w > area
        if (!titleScrolls) {
            canvas.drawText(title, CONTROLS_CX * sx - w / 2, baseline, titlePaint)
            return
        }
        // Marquee: pause, then scroll left continuously with a gap, fading at both edges.
        val gap = TITLE_GAP * sx
        val elapsed = (SystemClock.uptimeMillis() - titleSince - MARQUEE_DELAY_MS).coerceAtLeast(0)
        val offset = (elapsed / 1000f * MARQUEE_SPEED * sx) % (w + gap)
        val top = baseline - titlePaint.textSize
        val bottom = baseline + titlePaint.textSize * 0.4f
        val layer = canvas.saveLayer(left, top, right, bottom, null)
        canvas.clipRect(left, top, right, bottom)
        canvas.drawText(title, left - offset, baseline, titlePaint)
        canvas.drawText(title, left - offset + w + gap, baseline, titlePaint)
        val fade = FADE * sx
        fadePaint.shader = fadeLeft
        canvas.drawRect(left, top, left + fade, bottom, fadePaint)
        fadePaint.shader = fadeRight
        canvas.drawRect(right - fade, top, right, bottom, fadePaint)
        canvas.restoreToCount(layer)
    }

    private fun drawControls(canvas: Canvas, sx: Float, sy: Float) {
        iconPaint.strokeWidth = 1.6f * sy
        val top = ICON_T * sy
        val bottom = ICON_B * sy
        val cx = CONTROLS_CX * sx
        val step = CONTROLS_STEP * sx

        // Previous: bar on the left, triangle pointing left.
        drawSkip(canvas, cx - step, top, bottom, sx, forward = false)
        // Next: mirrored.
        drawSkip(canvas, cx + step, top, bottom, sx, forward = true)

        if (playing) {
            val barW = PAUSE_BAR_W * sx
            val half = PAUSE_GAP * sx / 2
            val r = 2f * sx
            canvas.drawRoundRect(cx - half - barW, PAUSE_T * sy, cx - half, PAUSE_B * sy, r, r, iconPaint)
            canvas.drawRoundRect(cx + half, PAUSE_T * sy, cx + half + barW, PAUSE_B * sy, r, r, iconPaint)
        } else {
            val l = cx - PLAY_W * sx / 2 + 1f * sx
            path.reset()
            path.moveTo(l, PLAY_T * sy)
            path.lineTo(l + PLAY_W * sx, (PLAY_T + PLAY_B) / 2 * sy)
            path.lineTo(l, PLAY_B * sy)
            path.close()
            canvas.drawPath(path, iconPaint)
        }

        val hitH = (pill.bottom - pill.top) * 0.7f
        val hitMid = (top + bottom) / 2
        prevHit.set(cx - step * 1.5f, hitMid - hitH / 2, cx - step * 0.5f, hitMid + hitH / 2)
        playHit.set(cx - step * 0.5f, hitMid - hitH / 2, cx + step * 0.5f, hitMid + hitH / 2)
        nextHit.set(cx + step * 0.5f, hitMid - hitH / 2, min(pill.right, cx + step * 1.5f), hitMid + hitH / 2)
    }

    private fun drawSkip(canvas: Canvas, cx: Float, top: Float, bottom: Float, sx: Float, forward: Boolean) {
        val half = SKIP_W * sx / 2
        val barW = SKIP_BAR_W * sx
        val dir = if (forward) 1f else -1f
        val barX0 = cx + dir * half
        canvas.drawRect(min(barX0, barX0 - dir * barW), top, max(barX0, barX0 - dir * barW), bottom, iconPaint)
        val tip = barX0 - dir * (barW + SKIP_TRI_GAP * sx)
        val base = cx - dir * half
        path.reset()
        path.moveTo(base, top)
        path.lineTo(tip, (top + bottom) / 2)
        path.lineTo(base, bottom)
        path.close()
        canvas.drawPath(path, iconPaint)
    }

    /** Pink "hills" along the bottom while playing, clipped to the pill. */
    private fun drawWave(canvas: Canvas, sx: Float, sy: Float) {
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val bottom = pill.bottom
        val maxH = WAVE_H * sy
        canvas.save()
        canvas.clipPath(clip)
        fill.shader = waveShader
            ?: LinearGradient(0f, bottom - maxH, 0f, bottom, waveEdge, waveLight, Shader.TileMode.CLAMP).also { waveShader = it }
        path.reset()
        path.moveTo(pill.left, bottom)
        val steps = 48
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val x = pill.left + f * pill.width()
            // A few moving bumps, strongest left of centre (as on Samsung's), fading out at both ends.
            val envelope = sin(PI * f).toFloat() * (1.1f - f * 0.5f)
            val wave = 0.55f + 0.25f * sin(2 * PI * (f * 2.3 - t * 0.9)).toFloat() +
                0.2f * sin(2 * PI * (f * 5.1 + t * 1.7)).toFloat()
            val h = maxH * (envelope * wave).coerceIn(0f, 1f)
            path.lineTo(x, bottom - h)
        }
        path.lineTo(pill.right, bottom)
        path.close()
        canvas.drawPath(path, fill)
        fill.shader = null
        canvas.restore()
    }

    // ---- Touch: buttons act on a tap; swipes are left to the cover face's gestures ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (controller == null) return false
        // Hit areas are in the reference coordinates the pill is drawn in (see onDraw).
        val x = event.rawX / scale
        val y = (event.rawY - originY) / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                downInside = pill.contains(x, y)
                return downInside
            }
            MotionEvent.ACTION_UP -> {
                if (!downInside || abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) return downInside
                val c = controller ?: return true
                val action = when {
                    prevHit.contains(x, y) -> "previous".also { c.transportControls.skipToPrevious() }
                    nextHit.contains(x, y) -> "next".also { c.transportControls.skipToNext() }
                    playHit.contains(x, y) -> if (playing) "pause".also { c.transportControls.pause() }
                        else "play".also { c.transportControls.play() }
                    openHit.contains(x, y) -> "open".also { openPlayer(c) }
                    else -> null
                }
                if (action != null) Log.d(CoverState.TAG, "media pill tap: $action (${c.packageName})")
                return true
            }
        }
        return downInside
    }

    private fun openPlayer(c: MediaController) {
        val pi = c.sessionActivity ?: return
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(display?.displayId ?: return)
        runCatching { pi.send(context, 0, null, null, null, null, options.toBundle()) }
            .onFailure { Log.w(CoverState.TAG, "could not open ${c.packageName}", it) }
    }

    private companion object {
        const val REF_H = 720f
        /** Density of the reference cover (Z Flip6, 320 dpi). */
        const val REF_DENSITY = 2.0f
        const val TALL_COVER_LIFT = 0.0463f

        // Measured on Samsung's locked cover (Z Flip6, One UI 9).
        const val PILL_L = 30f
        const val PILL_T = 574f
        const val PILL_R = 352f
        const val PILL_B = 685f
        const val ART_CX = 81.5f
        const val ART_CY = 630f
        const val ART_R = 31f
        const val TITLE_LEFT = 121f
        const val TITLE_RIGHT = 334f
        const val TITLE_BASELINE = 599f
        const val TITLE_SIZE = 21.5f
        const val TITLE_GAP = 70f
        const val FADE = 14f
        const val MARQUEE_SPEED = 30f // px per second
        const val MARQUEE_DELAY_MS = 1500L
        const val WAVE_FRAME_NANOS = 1_000_000_000L / 30
        const val SCROLL_FRAME_NANOS = 1_000_000_000L / 60
        const val FRAME_SLACK_NANOS = 4_000_000L
        const val CONTROLS_CX = 218.5f
        const val CONTROLS_STEP = 72f
        const val ICON_T = 626f
        const val ICON_B = 649.5f
        const val SKIP_W = 20f
        const val SKIP_BAR_W = 4f
        const val SKIP_TRI_GAP = 1.5f
        const val PAUSE_T = 624f
        const val PAUSE_B = 651.5f
        const val PAUSE_BAR_W = 6f
        const val PAUSE_GAP = 10f
        const val PLAY_T = 625f
        const val PLAY_B = 650.5f
        const val PLAY_W = 20f
        const val WAVE_H = 17f

        val DEFAULT_BG_TOP = Color.rgb(58, 58, 62)
        val DEFAULT_BG_BOTTOM = Color.rgb(34, 34, 37)
        val DEFAULT_WAVE = Color.rgb(255, 85, 91)

        fun lighten(c: Int, f: Float) = Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
            (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
            (Color.blue(c) + (255 - Color.blue(c)) * f).toInt(),
        )

        fun titleTypeface(): Typeface = runCatching {
            Typeface.Builder(File("/system/fonts/OneUISans-VF.ttf")).setFontVariationSettings("'wght' 450").build()
        }.getOrNull() ?: Typeface.DEFAULT
    }
}
