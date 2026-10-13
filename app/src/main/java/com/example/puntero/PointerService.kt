package com.example.puntero

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator

class PointerService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var cursor: View? = null
    private var pad: View? = null
    private var anim: ValueAnimator? = null
    private var sw = 0
    private var sh = 0
    private val cursorSize by lazy { (28 * resources.displayMetrics.density).toInt() }
    private val gain = 2.2f // sensibilidad del touchpad

    private var fx = 0.5f
    private var fy = 0.35f

    override fun onServiceConnected() {
        PointerState.load(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            sw = b.width(); sh = b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels; sh = dm.heightPixels
        }
        fx = PointerState.config.value.stopX
        fy = PointerState.config.value.stopY
        addCursor()
        addPad()
        move(fx, fy)
        PointerState.running.value = true
    }

    private fun addCursor() {
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC7C5CFF"))
                setStroke(4, Color.WHITE)
            }
        }
        val lp = WindowManager.LayoutParams(
            cursorSize, cursorSize, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        cursor = v
    }

    private fun addPad() {
        val v = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(60f, 60f, 60f, 60f, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#66181A22"))
                setStroke(3, Color.parseColor("#557C5CFF"))
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (sh * 0.35f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                // dx/dy vienen invertidos; se limita al rectángulo: no avanza más allá del límite
                move(fx - dx * gain / sw, fy - dy * gain / sh)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val c = PointerState.config.value
                glideTo(c.stopX, c.stopY)
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean { click(); return true }
        })
        v.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) anim?.cancel()
            gd.onTouchEvent(ev)
        }
        wm.addView(v, lp)
        pad = v
    }

    private fun move(x: Float, y: Float) {
        val c = PointerState.config.value
        fx = x.coerceIn(c.left, c.right)
        fy = y.coerceIn(c.top, c.bottom)
        val lp = cursor?.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = (fx * sw - cursorSize / 2f).toInt()
        lp.y = (fy * sh - cursorSize / 2f).toInt()
        wm.updateViewLayout(cursor, lp)
        PointerState.setPos(fx, fy)
    }

    private fun glideTo(tx: Float, ty: Float) {
        anim?.cancel()
        val x0 = fx; val y0 = fy
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { val t = it.animatedFraction; move(x0 + (tx - x0) * t, y0 + (ty - y0) * t) }
            start()
        }
    }

    private fun click() {
        val path = Path().apply { moveTo(fx * sw, fy * sh) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        dispatchGesture(g, null, null)
    }

    private fun cleanup() {
        anim?.cancel()
        runCatching { cursor?.let { wm.removeView(it) } }
        runCatching { pad?.let { wm.removeView(it) } }
        cursor = null; pad = null
        PointerState.running.value = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
}
