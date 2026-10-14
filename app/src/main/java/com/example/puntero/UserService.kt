package com.example.puntero

import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.FileInputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Corre con permisos de shell (lo inicia Shizuku). No toca tus toques: los lee en bruto de la pantalla táctil.
 * - Deslizar: pasa directo a las apps, sin retraso. Si tu dedo llega a la línea de límite, se corta el toque ahí.
 * - Doble toque: avisa a la app para fijar un nuevo límite a esa altura.
 * Manda un diagnóstico con números reales para ver en qué paso falla algo.
 */
class UserService : IUserService.Stub() {

    companion object {
        private const val EV_SYN = 0
        private const val EV_ABS = 3
        private const val SYN_REPORT = 0
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private const val SLOP = 24f
        private const val LONG_PRESS = 500L
        private const val DOUBLE_GAP = 400L   // del primer toque (al soltar) al segundo (al tocar)
        private const val DOUBLE_SLOP = 150f
    }

    @Volatile private var cb: ICallback? = null
    @Volatile private var sw = 1080
    @Volatile private var sh = 2400
    @Volatile private var rot = 0
    @Volatile private var linePortrait = -1f
    @Volatile private var lineLand = -1f
    @Volatile private var enabled = true
    @Volatile private var running = false

    private var devPath = ""
    private var minX = 0
    private var maxX = 1
    private var minY = 0
    private var maxY = 1
    private var deviceId = -1
    private var im: Any? = null
    private var injectM: Method? = null
    private var useTarget = false
    private var injOk = 0
    private var injFail = 0

    // dedo actual
    private var down = false
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var curX = 0f
    private var curY = 0f
    private var moved = false
    private var cut = false
    private var below = true
    private var secondTap = false
    private var tapPending = false
    private var lastUpT = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastCb = 0L
    private val xy = FloatArray(2)

    // diagnóstico
    private var evCount = 0L
    private var downs = 0
    private var taps = 0
    private var doubles = 0
    private var lastStatus = 0L

    override fun destroy() {
        running = false
        System.exit(0)
    }

    override fun start(cbArg: ICallback?, w: Int, h: Int, rotation: Int, lp: Float, ll: Float, en: Boolean) {
        cb = cbArg
        update(w, h, rotation, lp, ll, en)
        if (running) return
        running = true
        Thread { loop() }.apply { isDaemon = true; start() }
    }

    override fun update(w: Int, h: Int, rotation: Int, lp: Float, ll: Float, en: Boolean) {
        sw = w; sh = h; rot = rotation
        linePortrait = lp; lineLand = ll; enabled = en
    }

    override fun stop() { running = false }

    private fun log(s: String) {
        try { cb?.onLog(s) } catch (_: Throwable) {}
    }

    private fun report(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastStatus < 700L) return
        lastStatus = now
        val ly = lineY()
        log(
            "táctil id=$deviceId $devPath | eventos=$evCount dedos=$downs toques=$taps dobles=$doubles | " +
                "dedo=(${curX.toInt()},${curY.toInt()}) pantalla=${sw}x$sh giro=$rot " +
                "línea=${if (ly < 0f) "ninguna" else ly.toInt().toString()} | cortes=$injOk ok/$injFail mal"
        )
    }

    // ---------- preparación ----------

    private fun findDevice(): Boolean {
        var wanted: String? = null
        try {
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id) ?: continue
                if (d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                    deviceId = id
                    wanted = d.name
                    break
                }
            }
        } catch (e: Throwable) {
            log("No pude listar dispositivos: ${e.message}")
        }
        val proc = Runtime.getRuntime().exec(arrayOf("getevent", "-p"))
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val rx = Regex("""\b0035\s+:\s+value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
        val ry = Regex("""\b0036\s+:\s+value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
        var fallback: Array<String>? = null
        for (block in text.split("add device").drop(1)) {
            val path = Regex("""(/dev/input/event\d+)""").find(block)?.groupValues?.get(1) ?: continue
            val name = Regex("""name:\s+"(.*)"""").find(block)?.groupValues?.get(1)
            val mx = rx.find(block) ?: continue
            val my = ry.find(block) ?: continue
            val data = arrayOf(path, mx.groupValues[1], mx.groupValues[2], my.groupValues[1], my.groupValues[2])
            if (wanted == null || name == wanted) {
                useDevice(data)
                return true
            }
            if (fallback == null) fallback = data
        }
        if (fallback != null) { useDevice(fallback); return true }
        return false
    }

    private fun useDevice(d: Array<String>) {
        devPath = d[0]
        minX = d[1].toInt(); maxX = d[2].toInt()
        minY = d[3].toInt(); maxY = d[4].toInt()
    }

    private fun initInject(): Boolean = try {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val iface = Class.forName("android.hardware.input.IInputManager")
        val intT = Int::class.javaPrimitiveType!!
        injectM = try {
            iface.getMethod("injectInputEvent", InputEvent::class.java, intT)
        } catch (_: NoSuchMethodException) {
            useTarget = true
            iface.getMethod("injectInputEventToTarget", InputEvent::class.java, intT, intT)
        }
        true
    } catch (e: Throwable) {
        log("Inyección no disponible: ${e.message}")
        false
    }

    // ---------- lectura de la pantalla táctil ----------

    private fun readFully(ins: FileInputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = ins.read(buf, n, buf.size - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    private fun loop() {
        try {
            if (!findDevice()) { log("No encontré la pantalla táctil"); return }
            initInject()
            val is64 = Process.is64Bit()
            val buf = ByteArray(if (is64) 24 else 16)
            val off = if (is64) 16 else 8
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val ins = try {
                FileInputStream(devPath)
            } catch (e: Throwable) {
                log("Sin permiso para leer $devPath: ${e.message}")
                return
            }
            report(true)
            var slot = 0
            var primary = -1 // casilla del dedo que seguimos
            var rx = 0
            var ry = 0
            var dirty = false
            var downEvt = false
            var upEvt = false
            while (running) {
                if (!readFully(ins, buf)) break
                evCount++
                val type = bb.getShort(off).toInt() and 0xffff
                val code = bb.getShort(off + 2).toInt() and 0xffff
                val value = bb.getInt(off + 4)
                if (type == EV_ABS) {
                    when (code) {
                        ABS_MT_SLOT -> slot = value
                        ABS_MT_TRACKING_ID -> {
                            if (value >= 0) {
                                if (primary < 0 || slot == primary) { primary = slot; downEvt = true }
                            } else if (slot == primary) {
                                upEvt = true
                            }
                        }
                        ABS_MT_POSITION_X -> if (slot == primary) { rx = value; dirty = true }
                        ABS_MT_POSITION_Y -> if (slot == primary) { ry = value; dirty = true }
                    }
                } else if (type == EV_SYN && code == SYN_REPORT) {
                    if (downEvt) fingerDown(rx, ry)
                    if (upEvt) {
                        if (down) fingerUp()
                        primary = -1
                    } else if (down && dirty && !downEvt) {
                        fingerMove(rx, ry)
                    }
                    downEvt = false; upEvt = false; dirty = false
                    report()
                }
            }
        } catch (e: Throwable) {
            log("Error del motor: ${e.message}")
        } finally {
            running = false
        }
    }

    /** Pasa la posición en bruto de la pantalla táctil a píxeles de pantalla, según el giro. */
    private fun map(x: Int, y: Int) {
        val u = (x - minX).toFloat() / max(1, maxX - minX)
        val v = (y - minY).toFloat() / max(1, maxY - minY)
        val w = sw.toFloat()
        val h = sh.toFloat()
        when (rot) {
            1 -> { xy[0] = (1f - v) * w; xy[1] = u * h }
            2 -> { xy[0] = (1f - u) * w; xy[1] = (1f - v) * h }
            3 -> { xy[0] = v * w; xy[1] = (1f - u) * h }
            else -> { xy[0] = u * w; xy[1] = v * h }
        }
    }

    private fun lineY(): Float {
        val f = if (sh >= sw) linePortrait else lineLand
        return if (f < 0f) -1f else f * sh
    }

    // ---------- dedo ----------

    private fun fingerDown(rx: Int, ry: Int) {
        map(rx, ry)
        downX = xy[0]; downY = xy[1]; curX = downX; curY = downY
        downT = SystemClock.uptimeMillis()
        moved = false; cut = false; down = true
        downs++
        secondTap = tapPending && downT - lastUpT <= DOUBLE_GAP &&
            hypot(downX - lastTapX, downY - lastTapY) <= DOUBLE_SLOP
        val ly = lineY()
        below = ly < 0f || downY >= ly
        if (!enabled) return
        try { cb?.onDown(downX, downY) } catch (_: Throwable) {}
    }

    private fun fingerMove(rx: Int, ry: Int) {
        map(rx, ry)
        val x = xy[0]
        val y = xy[1]
        curX = x; curY = y
        if (!enabled) return
        if (!moved && hypot(x - downX, y - downY) > SLOP) moved = true
        val ly = lineY()
        if (!cut && ly >= 0f && (if (below) y <= ly else y >= ly)) {
            cut = true
            injectCancel(x, y)
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastCb >= 40L) {
            lastCb = now
            val shownY = if (ly < 0f) y else if (below) max(y, ly) else min(y, ly)
            try { cb?.onTouch(x, shownY, true) } catch (_: Throwable) {}
        }
    }

    private fun fingerUp() {
        down = false
        try { cb?.onTouch(0f, 0f, false) } catch (_: Throwable) {}
        if (!enabled) { tapPending = false; return }
        val now = SystemClock.uptimeMillis()
        val shortTap = !cut && !moved && now - downT < LONG_PRESS
        if (!shortTap) { tapPending = false; return }
        taps++
        if (secondTap) {
            tapPending = false
            doubles++
            try { cb?.onDoubleTap(downX, downY) } catch (_: Throwable) {}
            report(true)
        } else {
            tapPending = true
            lastUpT = now; lastTapX = downX; lastTapY = downY
        }
    }

    /** Cancela el toque en curso: la app deja de recibir el dedo, y el resto del gesto se descarta. */
    private fun injectCancel(x: Float, y: Float) {
        val m = injectM
        val target = im
        if (m == null || target == null) {
            injFail++
            log("No puedo cortar: sin inyección")
            return
        }
        try {
            val t = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, x, y, 1f, 1f, 0, 1f, 1f, deviceId, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            val ok = (if (useTarget) m.invoke(target, ev, 0, -1) else m.invoke(target, ev, 0)) as? Boolean ?: false
            ev.recycle()
            if (ok) injOk++ else injFail++
        } catch (e: Throwable) {
            injFail++
            log("Error al cortar: ${e.cause?.message ?: e.message}")
        }
        report(true)
    }
}
