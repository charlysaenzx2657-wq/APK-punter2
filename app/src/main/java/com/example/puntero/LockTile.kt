package com.example.puntero

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Botón en los ajustes rápidos para bloquear o liberar el doble toque desde cualquier app. */
class LockTile : TileService() {

    override fun onStartListening() {
        PointerState.load(this)
        refresh()
    }

    override fun onClick() {
        PointerState.setLocked(this, !PointerState.locked.value)
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (PointerState.locked.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Bloquear doble toque"
        t.updateTile()
    }
}
