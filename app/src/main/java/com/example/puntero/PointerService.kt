package com.example.puntero

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot
import rikka.shizuku.Shizuku

/**
 * Ya no toca tus toques. Aquí solo vive el puntito de confirmación y la conexión con el motor de Shizuku
 * (UserService), que es quien lee tu dedo y corta el toque al llegar a la línea.
 */
class PointerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var dot: View? = null
    private var lineView: View? = null
    private var sw = 1
    private var sh = 1
    private var rotation = 0
    private val density by lazy { resources.displayMetrics.density }
    private val dotSize by lazy { (16 * density).toInt() }
    private val dotHit by lazy { 32 * density }
    private var dotShown = false
    private var dotX = 0f
    private var dotY = 0f

    private var engine: IUserService? = null
    private var binding = false
    private var args: Shizuku.UserServiceArgs? = null

    private val binderListener = Shizuku.OnBinderReceivedListener { bindEngine() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        PointerState.refreshShizuku()
        if (result == PackageManager.PERMISSION_GRANTED) bindEngine()
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            engine = IUserService.Stub.asInterface(binder)
            binding = false
            push(true)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
            binding = false
            PointerState.status.value = "Motor desconectado"
        }
    }

    private val callback = object : ICallback.Stub() {
        override fun onDown(x: Float, y: Float) {
            handler.post {
                if (dotShown && hypot(x - dotX, y - dotY) > dotHit) hideDot()
                PointerState.setTouch(x / sw, y / sh)
            }
        }
        override fun onDoubleTap(x: Float, y: Float) { handler.post { handleDoubleTap(x, y) } }
        override fun onTouch(x: Float, y: Float, down: Boolean) {
            handler.post { if (down) PointerState.setTouch(x / sw, y / sh) else PointerState.clearTouch() }
        }
        override fun onLog(text: String?) { handler.post { PointerState.status.value = text ?: "" } }
    }

    override fun onServiceConnected() {
        PointerState.load(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        measure()
        addDot()
        addLine()
        PointerState.onChanged = { push() }
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        PointerState.running.value = true
    }

    // ---------- motor (Shizuku) ----------

    private fun bindEngine() {
        PointerState.refreshShizuku()
        if (engine != null || binding) return
        if (PointerState.shizuku.value != 2) {
            PointerState.status.value = "Shizuku no está listo"
            return
        }
        binding = true
        val a = Shizuku.UserServiceArgs(ComponentName(packageName, UserService::class.java.name))
            .processNameSuffix("motor").debuggable(false).daemon(false).version(1)
        args = a
        runCatching { Shizuku.bindUserService(a, conn) }.onFailure {
            binding = false
            PointerState.status.value = "No pude iniciar el motor: ${it.message}"
        }
    }

    private fun push(start: Boolean = false) {
        val e = engine ?: return
        val c = PointerState.config.value
        runCatching {
            if (start) e.start(callback, sw, sh, rotation, c.portrait, c.land, PointerState.enabled.value)
            else e.update(sw, sh, rotation, c.portrait, c.land, PointerState.enabled.value)
        }
    }

    // ---------- pantalla y puntito ----------

    private fun measure() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            sw = b.width(); sh = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels; sh = dm.heightPixels
        }
        rotation = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(0)?.rotation ?: 0
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

    private val lineH by lazy { (3 * density).toInt().coerceAtLeast(2) }

    private fun addLine() {
        val v = View(this).apply {
            setBackgroundColor(Color.parseColor("#E636D6C3"))
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, lineH, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        lineView = v
    }

    private fun showLine(y: Float) {
        val v = lineView ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.y = (y - lineH / 2f).toInt()
        v.visibility = View.VISIBLE
        wm.updateViewLayout(v, lp)
    }

    private fun showDot(x: Float, y: Float) {
        val v = dot ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = (x - dotSize / 2f).toInt()
        lp.y = (y - dotSize / 2f).toInt()
        v.visibility = View.VISIBLE
        wm.updateViewLayout(v, lp)
        dotShown = true; dotX = x; dotY = y
        showLine(y)
    }

    private fun hideDot() {
        dot?.visibility = View.GONE
        lineView?.visibility = View.GONE
        dotShown = false
    }

    /** Doble toque: fija el límite a esa altura y muestra el puntito donde tocaste. */
    private fun handleDoubleTap(x: Float, y: Float) {
        if (PointerState.locked.value) return // doble toque bloqueado
        val f = (y / sh).coerceIn(0.02f, 0.98f)
        val c = PointerState.config.value
        PointerState.update(this, if (sh >= sw) c.copy(portrait = f) else c.copy(land = f))
        showDot(x, y)
        dot?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        measure(); hideDot(); push()
        handler.postDelayed({ measure(); push() }, 300L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        runCatching { Shizuku.removeBinderReceivedListener(binderListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permListener) }
        runCatching { engine?.stop() }
        runCatching { args?.let { Shizuku.unbindUserService(it, conn, true) } }
        engine = null; binding = false
        runCatching { dot?.let { wm.removeView(it) } }
        runCatching { lineView?.let { wm.removeView(it) } }
        dot = null; lineView = null
        PointerState.onChanged = null
        PointerState.clearTouch()
        PointerState.running.value = false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
}
