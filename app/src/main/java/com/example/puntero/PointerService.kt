package com.example.puntero

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Capa invisible que recibe tu dedo y lo repite en la pantalla, pero sin pasar de la línea de límite.
 * - Deslizar: se repite el deslizamiento recortado al límite.
 * - Un toque: se repite como toque normal (con una pequeña espera para detectar el doble toque).
 * - Doble toque: fija un nuevo límite a esa altura y muestra un puntito.
 */
class PointerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var touch: View? = null
    private var dot: View? = null
    private var sw = 1
    private var sh = 1
    private val density by lazy { resources.displayMetrics.density }
    private val dotSize by lazy { (16 * density).toInt() }
    private val dotHit by lazy { 32 * density }
    private var slop = 8f
    private var doubleSlop = 100f
    private var doubleTapTimeout = 300L
    private var longPressTimeout = 500L
    private val seg = 40L // duración de cada tramo del deslizamiento repetido

    // toque actual
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var below = true // el dedo empezó por debajo de la línea (no podrá subir más allá de ella)

    // deslizamiento repetido
    private var dragging = false
    private var ending = false
    private var stroke: GestureDescription.StrokeDescription? = null
    private var curX = 0f
    private var curY = 0f
    private var tgtX = 0f
    private var tgtY = 0f
    private var busy = false
    private var tapInFlight = false
    private var injecting = false // la capa está "atravesable" para que el gesto repetido llegue a la app

    // doble toque y puntito
    private var tapRunnable: Runnable? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var dotShown = false
    private var dotX = 0f
    private var dotY = 0f

    override fun onServiceConnected() {
        PointerState.load(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val vc = ViewConfiguration.get(this)
        slop = vc.scaledTouchSlop.toFloat()
        doubleSlop = vc.scaledDoubleTapSlop.toFloat()
        doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        measure()
        addTouchLayer()
        addDot()
        PointerState.onEnabledChanged = { applyEnabled() }
        applyEnabled()
        PointerState.running.value = true
    }

    // ---------- pantalla y capas ----------

    private fun measure() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            sw = b.width(); sh = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels; sh = dm.heightPixels
        }
    }

    private fun layerFlags(touchable: Boolean) =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

    private fun addTouchLayer() {
        val v = View(this) // invisible
        v.setOnTouchListener { _, ev -> onTouch(ev) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            layerFlags(true), PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        touch = v
    }

    private fun addDot() {
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((3 * density).toInt(), Color.parseColor("#8B6CFF"))
            }
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            dotSize, dotSize, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        dot = v
    }

    private fun setTouchable(on: Boolean) {
        val v = touch ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val f = layerFlags(on && PointerState.enabled.value)
        if (lp.flags != f) { lp.flags = f; wm.updateViewLayout(v, lp) }
    }

    private fun applyEnabled() {
        setTouchable(!injecting)
        if (!PointerState.enabled.value) hideDot()
    }

    private fun showDot(x: Float, y: Float) {
        val v = dot ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = (x - dotSize / 2f).toInt()
        lp.y = (y - dotSize / 2f).toInt()
        v.visibility = View.VISIBLE
        wm.updateViewLayout(v, lp)
        dotShown = true; dotX = x; dotY = y
    }

    private fun hideDot() {
        dot?.visibility = View.GONE
        dotShown = false
    }

    // ---------- límite ----------

    private fun lineY(): Float {
        val c = PointerState.config.value
        val f = if (sh >= sw) c.portrait else c.land
        return if (f < 0f) -1f else f * sh
    }

    private fun clampY(y: Float): Float {
        val ly = lineY()
        return when {
            ly < 0f -> y
            below -> max(y, ly)
            else -> min(y, ly)
        }
    }

    private fun setLimit(y: Float) {
        val f = (y / sh).coerceIn(0.02f, 0.98f)
        val c = PointerState.config.value
        PointerState.update(this, if (sh >= sw) c.copy(portrait = f) else c.copy(land = f))
        touch?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // ---------- dedo ----------

    private fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; downTime = ev.eventTime
                moved = false
                val ly = lineY()
                below = ly < 0f || downY >= ly
                if (dotShown && hypot(downX - dotX, downY - dotY) > dotHit) hideDot()
                PointerState.setTouch(downX / sw, downY / sh)
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1) return true // un solo dedo
                val x = ev.rawX
                val y = clampY(ev.rawY)
                PointerState.setTouch(x / sw, y / sh)
                if (dragging) {
                    tgtX = x; tgtY = y
                    pump()
                } else if (!moved && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                    moved = true
                    startDrag(x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                PointerState.clearTouch()
                if (dragging) {
                    tgtX = ev.rawX; tgtY = clampY(ev.rawY)
                    ending = true
                    pump()
                } else if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                    val held = ev.eventTime - downTime
                    if (held >= longPressTimeout) {
                        flushTap()
                        sendTap(downX, downY, held.coerceAtMost(3000L))
                    } else {
                        handleTap(downX, downY, ev.eventTime)
                    }
                }
            }
        }
        return true
    }

    // ---------- toques y doble toque ----------

    private fun handleTap(x: Float, y: Float, t: Long) {
        val pending = tapRunnable
        if (pending != null && t - lastTapTime <= doubleTapTimeout &&
            hypot(x - lastTapX, y - lastTapY) <= doubleSlop
        ) {
            handler.removeCallbacks(pending)
            tapRunnable = null
            setLimit(y)
            showDot(x, y)
            return
        }
        flushTap()
        lastTapTime = t; lastTapX = x; lastTapY = y
        val r = Runnable { tapRunnable = null; sendTap(x, y, 50L) }
        tapRunnable = r
        handler.postDelayed(r, doubleTapTimeout)
    }

    private fun flushTap() {
        val r = tapRunnable ?: return
        handler.removeCallbacks(r)
        r.run()
    }

    private fun sendTap(x: Float, y: Float, dur: Long) {
        if (dragging || busy || tapInFlight) {
            handler.postDelayed({ sendTap(x, y, dur) }, 40L)
            return
        }
        tapInFlight = true
        beginInjection {
            val p = Path().apply { moveTo(x.coerceIn(0f, sw - 1f), y.coerceIn(0f, sh - 1f)) }
            send(GestureDescription.StrokeDescription(p, 0L, dur))
        }
    }

    // ---------- deslizamiento repetido ----------

    private fun startDrag(x: Float, y: Float) {
        flushTap()
        dragging = true; ending = false; stroke = null
        curX = downX; curY = downY
        tgtX = x; tgtY = y
        beginInjection { pump() }
    }

    private fun pump() {
        if (busy || tapInFlight || !dragging || !injecting) return
        val tx = tgtX.coerceIn(0f, sw - 1f)
        val ty = tgtY.coerceIn(0f, sh - 1f)
        val moving = tx != curX || ty != curY
        if (!moving && !ending) return
        val path = Path().apply {
            moveTo(curX, curY)
            if (moving) lineTo(tx, ty)
        }
        val cont = !ending
        val prev = stroke
        val sd = if (prev == null) GestureDescription.StrokeDescription(path, 0L, seg, cont)
        else prev.continueStroke(path, 0L, seg, cont)
        stroke = if (cont) sd else null
        curX = tx; curY = ty
        send(sd)
    }

    private val watch = Runnable { busy = false; abortDrag() }

    private val cb = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) {
            handler.removeCallbacks(watch); busy = false; afterSegment()
        }
        override fun onCancelled(g: GestureDescription?) {
            handler.removeCallbacks(watch); busy = false; abortDrag()
        }
    }

    private fun send(sd: GestureDescription.StrokeDescription) {
        busy = true
        handler.removeCallbacks(watch)
        handler.postDelayed(watch, 2500L)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(sd).build(), cb, null)
        if (!ok) {
            handler.removeCallbacks(watch)
            busy = false
            abortDrag()
        }
    }

    private fun afterSegment() {
        if (tapInFlight) {
            tapInFlight = false
            if (dragging) pump() else endInjection()
            return
        }
        if (!dragging) { endInjection(); return }
        if (ending && stroke == null) {
            dragging = false; ending = false
            endInjection()
        } else {
            pump()
        }
    }

    private fun abortDrag() {
        dragging = false; ending = false; stroke = null; tapInFlight = false
        endInjection()
    }

    /** La capa se vuelve atravesable un instante para que el gesto repetido llegue a la app y no a la propia capa. */
    private fun beginInjection(then: () -> Unit) {
        if (injecting) { then(); return }
        injecting = true
        setTouchable(false)
        handler.postDelayed({ then() }, 45L)
    }

    private fun endInjection() {
        injecting = false
        setTouchable(true)
    }

    // ---------- teclado y giro ----------

    /** Con el teclado abierto la capa no lo cubre, para poder escribir normal. */
    private fun updateIme() {
        val v = touch ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val ime = runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrNull()
        val newH = if (ime == null) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            val r = Rect()
            ime.getBoundsInScreen(r)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            (r.top - loc[1]).coerceAtLeast(1)
        }
        if (lp.height != newH) {
            lp.height = newH
            wm.updateViewLayout(v, lp)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { updateIme() }
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        measure()
        hideDot()
        handler.postDelayed({ measure() }, 300L)
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        runCatching { touch?.let { wm.removeView(it) } }
        runCatching { dot?.let { wm.removeView(it) } }
        touch = null; dot = null
        PointerState.onEnabledChanged = null
        PointerState.clearTouch()
        PointerState.running.value = false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
}
