package io.github.avengerdeveloper.flipfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.io.File

/**
 * The top row of the cover face, drawn like Samsung's One UI 9 cover lock screen:
 *  - top-left: one white icon per app with a notification (newest first), at most 3, then a dot;
 *  - top-right: the battery pill, filled to the battery level, with the percentage and a shield
 *    (battery protection holding the charge) or a bolt (charging).
 *
 * Geometry is in cover-display pixels of the Z Flip6 (748x720) as measured on Samsung's lock screen,
 * scaled to the actual cover size.
 */
class CoverStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var level = -1
    private var plugged = false
    private var status = BatteryManager.BATTERY_STATUS_UNKNOWN
    private var icons: List<Drawable> = emptyList()
    private var moreIcons = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = INK
        typeface = batteryTypeface()
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PILL_FULL
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pill = RectF()
    private val path = Path()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readBattery(intent)
    }
    private val iconObserver: () -> Unit = { post { loadIcons() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
            ?.let(::readBattery)
        CoverState.observeNotificationIcons(iconObserver)
        loadIcons()
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        CoverState.stopObservingNotificationIcons(iconObserver)
        super.onDetachedFromWindow()
    }

    private fun readBattery(intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        level = if (raw >= 0 && scale > 0) raw * 100 / scale else -1
        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        invalidate()
    }

    private fun loadIcons() {
        val all = CoverState.notificationIcons
        icons = all.take(MAX_ICONS).mapNotNull { n ->
            runCatching { n.icon.loadDrawable(context)?.mutate()?.apply { setTint(Color.WHITE) } }
                .onFailure { Log.w(CoverState.TAG, "icon for ${n.packageName} unavailable", it) }
                .getOrNull()
        }
        moreIcons = all.size > MAX_ICONS
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        val sw = (bounds?.width() ?: width).toFloat()
        val sh = (bounds?.height() ?: height).toFloat()
        val loc = IntArray(2).also(::getLocationOnScreen)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val sx = sw / REF_W
        val sy = sh / REF_H
        drawIcons(canvas, sx, sy)
        if (level >= 0) drawBattery(canvas, sx, sy)
        canvas.restore()
    }

    private fun drawIcons(canvas: Canvas, sx: Float, sy: Float) {
        val box = ICON_BOX * sy
        val top = (ICON_CENTER_Y * sy) - box / 2
        var x = ICON_LEFT * sx
        for (d in icons) {
            d.setBounds(x.toInt(), top.toInt(), (x + box).toInt(), (top + box).toInt())
            d.draw(canvas)
            x += box + ICON_GAP * sx
        }
        if (moreIcons) canvas.drawCircle(x + DOT_RADIUS * sy, ICON_CENTER_Y * sy, DOT_RADIUS * sy, dotPaint)
    }

    private fun drawBattery(canvas: Canvas, sx: Float, sy: Float) {
        val text = level.toString()
        textPaint.textSize = BATTERY_TEXT_SIZE * sy
        val textW = textPaint.measureText(text)
        val glyph = when {
            plugged && status == BatteryManager.BATTERY_STATUS_CHARGING -> Glyph.BOLT
            plugged && status == BatteryManager.BATTERY_STATUS_NOT_CHARGING -> Glyph.SHIELD
            else -> Glyph.NONE
        }

        val right = PILL_RIGHT * sx
        val textRight = right - PILL_PAD_RIGHT * sx
        val textLeft = textRight - textW
        val glyphLeft = textLeft - GLYPH_GAP * sx - GLYPH_W * sy
        val left = (if (glyph == Glyph.NONE) textLeft else glyphLeft) - PILL_PAD_LEFT * sx
        pill.set(left, PILL_TOP * sy, right, PILL_BOTTOM * sy)
        val radius = pill.height() / 2

        // Track (empty part), then the filled part clipped to the pill shape.
        fillPaint.color = PILL_EMPTY
        canvas.drawRoundRect(pill, radius, radius, fillPaint)
        canvas.save()
        path.reset(); path.addRoundRect(pill, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        fillPaint.color = PILL_FULL
        canvas.drawRect(pill.left, pill.top, pill.left + pill.width() * level / 100f, pill.bottom, fillPaint)
        canvas.restore()

        canvas.drawText(text, textLeft, BATTERY_BASELINE * sy, textPaint)

        val gx = glyphLeft
        val gy = GLYPH_TOP * sy
        val gw = GLYPH_W * sy
        val gh = GLYPH_H * sy
        when (glyph) {
            Glyph.SHIELD -> drawShield(canvas, gx, gy, gw, gh)
            Glyph.BOLT -> drawBolt(canvas, gx, gy, gw, gh)
            Glyph.NONE -> Unit
        }
    }

    /** Shield with a check mark, as Samsung shows while battery protection holds the charge. */
    private fun drawShield(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        path.reset()
        path.moveTo(x + w * 0.5f, y)
        path.lineTo(x + w, y + h * 0.17f)
        path.lineTo(x + w, y + h * 0.5f)
        path.cubicTo(x + w, y + h * 0.78f, x + w * 0.78f, y + h * 0.93f, x + w * 0.5f, y + h)
        path.cubicTo(x + w * 0.22f, y + h * 0.93f, x, y + h * 0.78f, x, y + h * 0.5f)
        path.lineTo(x, y + h * 0.17f)
        path.close()
        canvas.drawPath(path, inkPaint)
        checkPaint.strokeWidth = w * 0.12f
        path.reset()
        path.moveTo(x + w * 0.28f, y + h * 0.52f)
        path.lineTo(x + w * 0.45f, y + h * 0.67f)
        path.lineTo(x + w * 0.74f, y + h * 0.38f)
        canvas.drawPath(path, checkPaint)
    }

    /** Lightning bolt for charging. */
    private fun drawBolt(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        path.reset()
        path.moveTo(x + w * 0.62f, y)
        path.lineTo(x + w * 0.12f, y + h * 0.58f)
        path.lineTo(x + w * 0.46f, y + h * 0.58f)
        path.lineTo(x + w * 0.36f, y + h)
        path.lineTo(x + w * 0.88f, y + h * 0.40f)
        path.lineTo(x + w * 0.54f, y + h * 0.40f)
        path.close()
        canvas.drawPath(path, inkPaint)
    }

    private enum class Glyph { NONE, SHIELD, BOLT }

    private companion object {
        // Reference cover (Z Flip6) and measurements from Samsung's locked cover screen, in its pixels.
        const val REF_W = 748f
        const val REF_H = 720f

        const val MAX_ICONS = 3
        const val ICON_LEFT = 33f
        const val ICON_CENTER_Y = 36f
        const val ICON_BOX = 30f
        const val ICON_GAP = 6f
        const val DOT_RADIUS = 4f

        const val PILL_RIGHT = 714f
        const val PILL_TOP = 21f
        const val PILL_BOTTOM = 51.5f
        const val PILL_PAD_LEFT = 10f
        const val PILL_PAD_RIGHT = 9.5f
        const val GLYPH_GAP = 5f
        const val GLYPH_W = 14f
        const val GLYPH_H = 16f
        const val GLYPH_TOP = 28f
        const val BATTERY_TEXT_SIZE = 23.2f
        const val BATTERY_BASELINE = 45.3f

        val PILL_FULL = Color.rgb(243, 244, 246)
        val PILL_EMPTY = Color.rgb(221, 223, 221)
        val INK = Color.rgb(77, 77, 82)

        fun batteryTypeface(): Typeface = runCatching {
            Typeface.Builder(File("/system/fonts/OneUISans-VF.ttf"))
                .setFontVariationSettings("'wght' 550")
                .build()
        }.getOrNull() ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
