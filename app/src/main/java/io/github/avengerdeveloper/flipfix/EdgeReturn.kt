package io.github.avengerdeveloper.flipfix

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * "Swipe right on Samsung's widgets to go back to FlipFix", with an interactive card peek.
 *
 * A transparent accessibility overlay sits along the left edge of the cover while Samsung's widgets show.
 *  - Drag right from it: a rounded card showing the cover wallpaper (Samsung's own still frame of the
 *    video, thumbnail.jpg) with a blurred clock slides in from the left with the finger, over dimmed
 *    widgets. Released past ~1/3 of the width (or flicked) it grows to fill the screen, FlipFix opens
 *    underneath, and once FlipFix has drawn the card fades out (still frame -> moving video, blurred ->
 *    sharp clock). Otherwise it slides back.
 *  - Any other gesture starting in the strip (tap, long press, vertical scroll, left swipe) is replayed
 *    to Samsung's widgets underneath, so the strip can be wide without blocking them.
 *
 * The card is generic on purpose: it never has to copy the user's clock style or widgets exactly.
 */
class EdgeReturn(
    private val service: AccessibilityService,
    private val coverDisplayId: () -> Int,
    private val onCommit: () -> Unit,
) {
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var card: FrameLayout? = null
    private var scrim: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenW = 0f
    private var density = 2f
    private var cornerRadius = 0f
    private var thumbnail: Bitmap? = null
    private var awaitingFace = false

    val isShown get() = root != null

    fun show() {
        if (root != null) return
        val display = service.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(coverDisplayId()) ?: return
        try {
            val ctx = service.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val windowManager = ctx.getSystemService(WindowManager::class.java)
            density = ctx.resources.displayMetrics.density
            screenW = windowManager.currentWindowMetrics.bounds.width().toFloat()
            val lp = WindowManager.LayoutParams(
                stripWidth(),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "FlipFix edge"
            }
            val view = FrameLayout(ctx).apply { setOnTouchListener(Touch()) }
            windowManager.addView(view, lp)
            wm = windowManager; root = view; params = lp
            loadThumbnailAsync()
            Log.d(CoverState.TAG, "edge strip shown")
        } catch (t: Throwable) {
            Log.w(CoverState.TAG, "could not show edge strip", t)
        }
    }

    /**
     * Screen off: let go of the snapshots and the still. They only serve the swipe animations of the current
     * session, and the next swipe-away takes fresh ones.
     */
    fun releaseImages() {
        if (root != null) return
        faceSnapshot = null
        preparedFace = null
        thumbnail = null
        thumbnailFolder = null
    }

    /** Remove the overlay, optionally after a delay. */
    fun hide(delayMs: Long = 0) {
        val view = root ?: return
        if (delayMs > 0) {
            view.postDelayed({ if (root === view) hide() }, delayMs)
            return
        }
        runCatching { wm?.removeView(view) }
        root = null; card = null; scrim = null; params = null; wm = null; awaitingFace = false
        Log.d(CoverState.TAG, "edge strip hidden")
    }

    /**
     * After a commit, keep the full-screen card until FlipFix has drawn underneath it, then fade it out
     * (still frame -> moving video, blurred -> sharp clock). Falls back to a timeout.
     */
    fun awaitFace() {
        val view = root ?: return
        awaitingFace = true
        CoverState.onFaceShownOnce { view.post { if (root === view) fadeOut() } }
        view.postDelayed({ if (root === view && awaitingFace) fadeOut() }, FACE_TIMEOUT_MS)
    }

    private fun fadeOut() {
        val view = root ?: return
        awaitingFace = false
        view.animate().alpha(0f).setDuration(HANDOVER_FADE_MS).withEndAction { hide() }.start()
    }

    private fun stripWidth() = (STRIP_DP * density).toInt()

    /** Folder the current [thumbnail] came from; the active preset may change. */
    private var thumbnailFolder: String? = null

    /** Samsung's still frame of the active preset's cover video, next to its clock template. */
    private fun loadThumbnailAsync() {
        Thread {
            val file = CoverFaceMatcher.activeFolder(service)?.let { File(it, "thumbnail.jpg") }?.takeIf { it.canRead() }
            val folder = file?.parentFile?.name
            if (folder == thumbnailFolder && (thumbnail != null || folder == null)) return@Thread
            thumbnail = file?.let { BitmapFactory.decodeFile(it.path) }
            thumbnailFolder = folder
            Log.d(CoverState.TAG, "edge peek still: ${folder ?: "none"}")
        }.start()
    }

    // ---- Card ----

    /**
     * Grow the overlay to full screen and put an optional [background], a scrim and a card (holding
     * [content]) in it.
     */
    private fun buildCard(content: List<View>, background: View? = null): FrameLayout? {
        val view = root ?: return null
        val lp = params ?: return null
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        runCatching { wm?.updateViewLayout(view, lp) }
        view.removeAllViews()
        view.alpha = 1f
        val ctx = view.context
        background?.let { view.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        scrim = View(ctx).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        val c = FrameLayout(ctx).apply {
            content.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) =
                    outline.setRoundRect(0, 0, v.width, v.height, cornerRadius)
            }
            elevation = CARD_ELEVATION_DP * density
        }
        view.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        view.addView(c, FrameLayout.LayoutParams(-1, -1))
        card = c
        return c
    }

    /** The card shown while dragging back: the snapshot taken when FlipFix was swiped away, lightly blurred. */
    private fun expand() {
        val ctx = root?.context ?: return
        cornerRadius = CORNER_DP * density
        val snapshot = faceSnapshot
        val content = if (snapshot != null) {
            listOf(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(snapshot)
                setRenderEffect(RenderEffect.createBlurEffect(LIGHT_BLUR_PX, LIGHT_BLUR_PX, Shader.TileMode.CLAMP))
            })
        } else {
            // Fallback (no snapshot yet): Samsung's still frame with a blurred clock.
            listOf(
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    thumbnail?.let(::setImageBitmap) ?: setBackgroundColor(FALLBACK_CARD)
                },
                CoverClockView(ctx).apply {
                    screenAnchored = false
                    setRenderEffect(RenderEffect.createBlurEffect(LIGHT_BLUR_PX, LIGHT_BLUR_PX, Shader.TileMode.DECAL))
                },
            )
        }
        buildCard(content) ?: return
        setProgress(0f)
    }

    /** Snapshot of FlipFix's face taken at the moment it was swiped away; reused for the way back. */
    private var faceSnapshot: Bitmap? = null

    // ---- Swiping FlipFix away, following the finger ----

    /** Called when a swipe-away has finished and Samsung's widgets are showing (to refresh the capture). */
    var onExitFinished: (() -> Unit)? = null

    private var preparedFace: Bitmap? = null
    private var preparedAt = 0L

    /** Just the idle edge strip on screen (no card, no animation): safe to capture the widgets. */
    val isIdleStrip get() = root != null && card == null && exitState == ExitState.IDLE && !awaitingFace

    /** A face snapshot taken moments ago (when the finger went down on FlipFix), if still fresh. */
    fun freshFace(): Bitmap? = preparedFace?.takeIf { SystemClock.uptimeMillis() - preparedAt < FACE_FRESH_MS }

    fun offerFaceSnapshot(bitmap: Bitmap) {
        preparedFace = bitmap
        preparedAt = SystemClock.uptimeMillis()
        if (exitState == ExitState.WAITING) buildExitCard()
    }

    private enum class ExitState { IDLE, WAITING, DRAGGING, FINISHING }
    private var exitState = ExitState.IDLE
    private var exitProgress = 0f
    private var exitImage: ImageView? = null
    private var exitBackdropIsFallback = false
    private var pendingRelease: Pair<Boolean, () -> Unit>? = null

    /** The finger started dragging FlipFix to the left. */
    fun startExitDrag() {
        if (exitState == ExitState.FINISHING) return
        exitState = ExitState.WAITING
        exitProgress = 0f
        pendingRelease = null
        if (freshFace() != null) buildExitCard()
    }

    /** 0 = card covers the screen, 1 = card shrunk and moved left by the full drag distance. */
    fun updateExitDrag(progress: Float) {
        exitProgress = progress.coerceIn(0f, 1f)
        if (exitState == ExitState.DRAGGING) applyExit(exitProgress)
    }

    /** Finger lifted: [commit] = go to the widgets ([onCovered] steps FlipFix aside), else spring back. */
    fun releaseExitDrag(commit: Boolean, onCovered: () -> Unit) {
        when (exitState) {
            ExitState.DRAGGING -> finishExit(commit, onCovered)
            ExitState.WAITING -> if (commit) {
                // The snapshot is still on its way; finish as soon as it lands, or step aside plainly.
                pendingRelease = true to onCovered
                root?.postDelayed({
                    if (exitState == ExitState.WAITING) { exitState = ExitState.IDLE; pendingRelease = null; onCovered() }
                }, FACE_WAIT_MS) ?: run { exitState = ExitState.IDLE; onCovered() }
            } else {
                exitState = ExitState.IDLE
            }
            else -> if (commit) onCovered()
        }
    }

    private fun buildExitCard() {
        val face = freshFace() ?: return
        show()
        val ctx = root?.context ?: return
        cornerRadius = 0f
        val widgets = CoverState.widgetsSnapshot
        exitBackdropIsFallback = widgets == null
        val backdrop = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            if (widgets != null) {
                setImageBitmap(widgets)
            } else {
                // No capture of the widget screen yet: imitate its background, which is the paused cover
                // video blurred and dimmed by Samsung (the widget cards then fade in at the end).
                setImageBitmap(face)
                val dim = ColorMatrixColorFilter(ColorMatrix().apply { setScale(FALLBACK_DIM, FALLBACK_DIM, FALLBACK_DIM, 1f) })
                setRenderEffect(
                    RenderEffect.createChainEffect(
                        RenderEffect.createColorFilterEffect(dim),
                        RenderEffect.createBlurEffect(FALLBACK_BLUR_PX, FALLBACK_BLUR_PX, Shader.TileMode.CLAMP),
                    ),
                )
            }
        }
        val image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(face)
        }
        buildCard(listOf(image), backdrop) ?: return
        faceSnapshot = face
        exitImage = image
        exitState = ExitState.DRAGGING
        awaitingFace = true // the overlay ignores new touches; the drag itself stays with FlipFix
        applyExit(exitProgress)
        pendingRelease?.let { (commit, onCovered) -> pendingRelease = null; finishExit(commit, onCovered) }
    }

    private fun applyExit(q: Float) {
        val c = card ?: return
        val s = 1f - (1f - CARD_SCALE_MIN) * q
        c.scaleX = s; c.scaleY = s
        c.translationX = -q * screenW * DRAG_DISTANCE_FRACTION // 1:1 with the finger
        cornerRadius = CORNER_DP * density * min(1f, q * 3f)
        c.invalidateOutline()
        val blur = LIGHT_BLUR_PX * q
        exitImage?.setRenderEffect(
            if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null,
        )
        scrim?.alpha = SCRIM_MAX * (1 - q)
    }

    private fun finishExit(commit: Boolean, onCovered: () -> Unit) {
        val c = card ?: run { exitState = ExitState.IDLE; if (commit) onCovered(); return }
        exitState = ExitState.FINISHING
        val from = exitProgress
        if (commit) {
            onCovered() // FlipFix steps aside underneath; the real widgets now match the backdrop
            val startX = c.translationX
            val startScale = c.scaleX
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (EXIT_MS * (1 - from * 0.5f)).toLong()
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    val s = startScale + (CARD_SCALE_MIN - startScale) * f
                    c.scaleX = s; c.scaleY = s
                    c.translationX = startX + (-screenW - startX) * f
                    cornerRadius = CORNER_DP * density
                    c.invalidateOutline()
                    scrim?.alpha = SCRIM_MAX * (1 - from) * (1 - f)
                }
                withEnd {
                    val finish: () -> Unit = {
                        exitState = ExitState.IDLE
                        awaitingFace = false
                        exitImage = null
                        collapse()
                        Log.d(CoverState.TAG, "edge exit played (dragged${if (exitBackdropIsFallback) ", fallback backdrop" else ""})")
                        onExitFinished?.invoke()
                    }
                    val view = root
                    if (exitBackdropIsFallback && view != null) {
                        // The stand-in backdrop has no widget cards: fade it so the real ones fade in.
                        view.animate().alpha(0f).setDuration(FALLBACK_FADE_MS).withEndAction { finish() }.start()
                    } else {
                        finish()
                    }
                }
            }.start()
        } else {
            ValueAnimator.ofFloat(from, 0f).apply {
                duration = CANCEL_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { applyExit(it.animatedValue as Float) }
                withEnd {
                    exitState = ExitState.IDLE
                    awaitingFace = false
                    exitImage = null
                    hide() // FlipFix never left; just remove the card
                    Log.d(CoverState.TAG, "edge exit cancelled")
                }
            }.start()
        }
    }

    /**
     * Swipe-away animation (the reverse of the peek): a card showing [snapshot] covers the screen exactly,
     * [onCovered] lets FlipFix step aside underneath it, then the card shrinks, rounds its corners, blurs
     * slightly and slides off to the left while the dim lifts from Samsung's widgets.
     */
    fun playExit(snapshot: Bitmap, onCovered: () -> Unit) {
        faceSnapshot = snapshot
        show()
        val view = root ?: return onCovered()
        cornerRadius = 0f
        val image = ImageView(view.context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(snapshot)
        }
        val c = buildCard(listOf(image)) ?: return onCovered()
        c.scaleX = 1f; c.scaleY = 1f; c.translationX = 0f
        scrim?.alpha = SCRIM_MAX
        awaitingFace = true // ignore touches on the overlay while it animates
        // Step aside only once the card is really on screen, so the widgets never flash through.
        view.viewTreeObserver.registerFrameCommitCallback {
            view.post {
                onCovered()
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = EXIT_MS
                    startDelay = EXIT_DELAY_MS
                    interpolator = android.view.animation.AccelerateInterpolator(1.2f)
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        val s = 1f - (1f - CARD_SCALE_MIN) * f
                        c.scaleX = s; c.scaleY = s
                        c.translationX = -screenW * f
                        cornerRadius = CORNER_DP * density * min(1f, f * 3f)
                        c.invalidateOutline()
                        val blur = LIGHT_BLUR_PX * f
                        image.setRenderEffect(
                            if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null,
                        )
                        scrim?.alpha = SCRIM_MAX * (1 - f)
                    }
                    withEnd {
                        awaitingFace = false
                        collapse()
                        Log.d(CoverState.TAG, "edge exit played")
                        onExitFinished?.invoke()
                    }
                }.start()
            }
        }
        view.invalidate()
    }

    /** 0 = card just off the left edge, 1 = card in place (still slightly inset, rounded). */
    private fun setProgress(p: Float) {
        val q = p.coerceIn(0f, 1f)
        val c = card ?: return
        val scale = CARD_SCALE_MIN + (CARD_SCALE_DRAG_MAX - CARD_SCALE_MIN) * q
        c.scaleX = scale
        c.scaleY = scale
        c.translationX = -(1 - q) * screenW
        scrim?.alpha = q * SCRIM_MAX
    }

    private fun collapse() {
        val view = root ?: return
        view.removeAllViews()
        view.animate().cancel()
        view.alpha = 1f
        card = null; scrim = null
        val lp = params ?: return
        lp.width = stripWidth()
        runCatching { wm?.updateViewLayout(view, lp) }
    }

    /** Grow the card to fill the screen (corners square off), then open FlipFix underneath it. */
    private fun commit() {
        val c = card ?: return
        val startScale = c.scaleX
        val startX = c.translationX
        val startRadius = cornerRadius
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COMMIT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val s = startScale + (1f - startScale) * f
                c.scaleX = s; c.scaleY = s
                c.translationX = startX * (1 - f)
                cornerRadius = startRadius * (1 - f)
                c.invalidateOutline()
            }
            withEnd {
                Log.d(CoverState.TAG, "edge peek committed")
                onCommit()
            }
        }.start()
        scrim?.animate()?.alpha(SCRIM_MAX)?.setDuration(COMMIT_MS)?.start()
    }

    private fun cancel() {
        val c = card ?: return collapse()
        c.animate().translationX(-screenW).scaleX(CARD_SCALE_MIN).scaleY(CARD_SCALE_MIN).setDuration(CANCEL_MS)
            .withEndAction { collapse() }.start()
        scrim?.animate()?.alpha(0f)?.setDuration(CANCEL_MS)?.start()
    }

    private fun ValueAnimator.withEnd(action: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = action()
    })

    // ---- Replay gestures that were not "drag right" to Samsung's widgets underneath ----

    private fun replay(points: List<Triple<Float, Float, Long>>) {
        val view = root ?: return
        val lp = params ?: return
        if (points.isEmpty()) return
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { lineTo(it.first, it.second) }
        }
        val duration = (points.last().third - points.first().third)
            .coerceIn(1, GestureDescription.getMaxGestureDuration())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .setDisplayId(coverDisplayId())
            .build()
        // Let the replayed touch pass through our strip, then make it touchable again.
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm?.updateViewLayout(view, lp) }
        val restore = {
            if (root === view) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm?.updateViewLayout(view, lp) }
            }
        }
        val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = restore()
            override fun onCancelled(g: GestureDescription?) = restore()
        }, null)
        if (!sent) restore()
    }

    private inner class Touch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private val points = mutableListOf<Triple<Float, Float, Long>>()
        private var dragging = false
        private var decided = false
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (awaitingFace) return true
            val now = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear(); points += Triple(e.rawX, e.rawY, now)
                    downX = e.rawX; downY = e.rawY; downAt = now
                    dragging = false; decided = false
                }
                MotionEvent.ACTION_MOVE -> {
                    points += Triple(e.rawX, e.rawY, now)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!decided && hypot(dx, dy) > slop) {
                        decided = true
                        dragging = dx > 0 && dx > abs(dy) * 1.2f
                        if (dragging) expand()
                    }
                    if (dragging) setProgress(dx / (screenW * DRAG_DISTANCE_FRACTION))
                }
                MotionEvent.ACTION_UP -> {
                    points += Triple(e.rawX, e.rawY, now)
                    val dx = e.rawX - downX
                    if (dragging) {
                        val progress = (dx / (screenW * DRAG_DISTANCE_FRACTION)).coerceIn(0f, 1f)
                        val velocity = dx / (now - downAt).coerceAtLeast(1) // px per ms
                        if (progress > COMMIT_PROGRESS || (velocity > FLICK_PX_PER_MS * density / 2 && dx > slop * 2)) {
                            commit()
                        } else {
                            cancel()
                        }
                    } else {
                        replay(points.toList())
                    }
                }
                MotionEvent.ACTION_CANCEL -> if (dragging) cancel()
            }
            return true
        }
    }

    private companion object {
        const val STRIP_DP = 36f
        const val DRAG_DISTANCE_FRACTION = 0.6f // drag this share of the width for full progress
        const val COMMIT_PROGRESS = 0.33f
        const val FLICK_PX_PER_MS = 0.8f
        const val CARD_SCALE_MIN = 0.86f
        const val CARD_SCALE_DRAG_MAX = 0.93f
        const val CORNER_DP = 26f
        const val CARD_ELEVATION_DP = 10f
        const val LIGHT_BLUR_PX = 5f
        const val EXIT_MS = 260L
        const val EXIT_DELAY_MS = 40L // let the widgets settle in underneath first
        const val FACE_FRESH_MS = 1500L // a face snapshot this recent still matches the screen
        const val FACE_WAIT_MS = 300L
        const val FALLBACK_BLUR_PX = 40f
        const val FALLBACK_DIM = 0.72f
        const val FALLBACK_FADE_MS = 160L
        const val SCRIM_MAX = 0.5f
        const val COMMIT_MS = 190L
        const val CANCEL_MS = 180L
        const val HANDOVER_FADE_MS = 170L
        const val FACE_TIMEOUT_MS = 900L
        val FALLBACK_CARD = Color.rgb(40, 40, 46)
    }
}
