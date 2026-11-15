package io.github.avengerdeveloper.flipfix

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: switch FlipFix on or off without opening the app. Off closes the cover face and
 * switches the accessibility service off; on lets the fold rule switch it back on when folded.
 */
class FlipFixTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val enable = !CoverState.isEnabled(this)
        CoverState.setEnabled(this, enable)
        if (!enable) {
            AccessibilityToggle.set(this, false, "switched off from the tile")
            CoverState.finishCover()
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = CoverState.isEnabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
