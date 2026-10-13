package com.example.puntero

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Todo en fracciones de pantalla (0f..1f) para que funcione en cualquier teléfono. */
data class Config(
    val stopX: Float = 0.5f, val stopY: Float = 0.35f,      // punto donde se detiene el puntero
    val left: Float = 0.05f, val top: Float = 0.05f,         // límite: el puntero no sale de este rectángulo
    val right: Float = 0.95f, val bottom: Float = 0.60f
)

object PointerState {
    private val _config = MutableStateFlow(Config())
    val config = _config.asStateFlow()
    private val _pos = MutableStateFlow(0.5f to 0.35f)
    val pos = _pos.asStateFlow()
    val running = MutableStateFlow(false)

    fun load(c: Context) {
        val p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val d = Config()
        _config.value = Config(
            p.getFloat("sx", d.stopX), p.getFloat("sy", d.stopY),
            p.getFloat("l", d.left), p.getFloat("t", d.top),
            p.getFloat("r", d.right), p.getFloat("b", d.bottom)
        )
    }

    fun update(c: Context, n: Config) {
        val cfg = n.copy(stopX = n.stopX.coerceIn(n.left, n.right), stopY = n.stopY.coerceIn(n.top, n.bottom))
        _config.value = cfg
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putFloat("sx", cfg.stopX).putFloat("sy", cfg.stopY)
            .putFloat("l", cfg.left).putFloat("t", cfg.top)
            .putFloat("r", cfg.right).putFloat("b", cfg.bottom).apply()
    }

    fun reset(c: Context) = update(c, Config())

    fun setPos(x: Float, y: Float) { _pos.value = x to y }
}
