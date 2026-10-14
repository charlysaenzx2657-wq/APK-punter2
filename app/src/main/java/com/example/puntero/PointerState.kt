package com.example.puntero

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Posición de la línea de límite como fracción de la altura (0..1). -1 = sin límite. Una por orientación. */
data class Config(val portrait: Float = -1f, val land: Float = -1f)

object PointerState {
    private val _config = MutableStateFlow(Config())
    val config = _config.asStateFlow()
    private val _enabled = MutableStateFlow(true)
    val enabled = _enabled.asStateFlow()
    private val _touch = MutableStateFlow<Pair<Float, Float>?>(null)
    val touch = _touch.asStateFlow()
    val running = MutableStateFlow(false)
    var onEnabledChanged: (() -> Unit)? = null

    private fun prefs(c: Context) = c.getSharedPreferences("cfg3", Context.MODE_PRIVATE)

    fun load(c: Context) {
        val p = prefs(c)
        _config.value = Config(p.getFloat("p", -1f), p.getFloat("h", -1f))
        _enabled.value = p.getBoolean("on", true)
    }

    fun update(c: Context, n: Config) {
        _config.value = n
        prefs(c).edit().putFloat("p", n.portrait).putFloat("h", n.land).apply()
    }

    fun reset(c: Context) = update(c, Config())

    fun setEnabled(c: Context, e: Boolean) {
        _enabled.value = e
        prefs(c).edit().putBoolean("on", e).apply()
        onEnabledChanged?.invoke()
    }

    fun setTouch(x: Float, y: Float) { _touch.value = x to y }
    fun clearTouch() { _touch.value = null }
}
