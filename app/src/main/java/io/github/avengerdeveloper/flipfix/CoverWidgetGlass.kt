package io.github.avengerdeveloper.flipfix

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * Glass panes for the widget stand-ins on the resting face ([CoverWidgets]): each pane shows the moving
 * cover video behind it through Samsung's own live blur, the frosted look of Samsung's widget panels, with
 * the widget's blurred marks on top. Where Samsung's blur isn't available, a see-through tint stands in.
 */
class CoverWidgetGlass @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private var tiles: List<CoverWidgets.Tile> = emptyList()
    private val origin = IntArray(2)

    /** Shows [tiles] (the active preset's), replacing the ones shown. */
    fun show(tiles: List<CoverWidgets.Tile>) {
        if (tiles === this.tiles) return
        this.tiles = tiles
        removeAllViews()
        for (t in tiles) addView(Pane(context, t))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Panes are measured in onLayout, where their spots are known.
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Tiles are placed on the cover display, wherever this view sits (as the clock is).
        val bounds = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        val w = (bounds?.width() ?: width).toFloat()
        val h = (bounds?.height() ?: height).toFloat()
        restingOrigin(origin)
        for (i in 0 until childCount) {
            val pane = getChildAt(i) as Pane
            val tile = pane.tile
            val left = (tile.left * w).roundToInt() - origin[0]
            val top = (tile.top * h).roundToInt() - origin[1]
            val right = (tile.right * w).roundToInt() - origin[0]
            val bottom = (tile.bottom * h).roundToInt() - origin[1]
            pane.measure(
                MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.EXACTLY),
            )
            pane.layout(left, top, right, bottom)
        }
    }

    /**
     * Where this view sits on the display, ignoring the face's slide (translation) when it's dismissed, so
     * a layout during the slide doesn't misplace the panes.
     */
    private fun restingOrigin(out: IntArray) {
        rootView.getLocationOnScreen(out)
        var v: View? = this
        while (v != null && v !== rootView) {
            out[0] += v.left; out[1] += v.top
            v = v.parent as? View
        }
    }

    /** One widget's pane: Samsung's live blur behind it if it takes, its marks on top. */
    private class Pane(context: Context, val tile: CoverWidgets.Tile) : View(context) {
        private var frosted = false
        private val rect = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            frosted = SamsungBlur.frost(this, CoverWidgets.corner(h.toFloat()))
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            CoverWidgets.drawTile(canvas, tile, rect, frosted)
        }
    }

    /**
     * Samsung's live background blur (SemBlurInfo, what Samsung's own widget panels use), reached by name:
     * it is in One UI's framework, not in Android's SDK. Android's own cross-window blur is off on the cover.
     */
    private object SamsungBlur {
        /** SemBlurInfo.BLUR_MODE_WINDOW: blurs what's behind the window (the cover video), live. */
        private const val MODE_WINDOW = 0

        /** SemBlurInfo.BLUR_BG_REGULAR_DARK: radius 250, tones squeezed into 34-175, as Samsung's panels look. */
        private const val PRESET_BG_REGULAR_DARK = 135

        private class Api(
            val builder: Constructor<*>, val preset: Method, val corner: Method, val build: Method, val apply: Method,
        )

        private val api: Api? by lazy {
            try {
                val info = Class.forName("android.view.SemBlurInfo")
                val builder = Class.forName("android.view.SemBlurInfo\$Builder")
                Api(
                    builder.getConstructor(Int::class.javaPrimitiveType),
                    builder.getMethod("setColorCurvePreset", Int::class.javaPrimitiveType),
                    builder.getMethod("setBackgroundCornerRadius", Float::class.javaPrimitiveType),
                    builder.getMethod("build"),
                    View::class.java.getMethod("semSetBlurInfo", info),
                ).also { Log.d(CoverState.TAG, "widget glass: Samsung's live blur") }
            } catch (e: Throwable) {
                Log.d(CoverState.TAG, "widget glass: no Samsung blur ($e), tinted instead")
                null
            }
        }

        /** Frosts [view]'s bounds, rounded by [corner]; false if Samsung's blur isn't there. */
        fun frost(view: View, corner: Float): Boolean {
            val a = api ?: return false
            return try {
                val b = a.builder.newInstance(MODE_WINDOW)
                a.preset.invoke(b, PRESET_BG_REGULAR_DARK)
                a.corner.invoke(b, corner)
                a.apply.invoke(view, a.build.invoke(b))
                true
            } catch (e: Throwable) {
                Log.w(CoverState.TAG, "widget glass: Samsung's blur refused ($e), tinted instead")
                false
            }
        }
    }
}
