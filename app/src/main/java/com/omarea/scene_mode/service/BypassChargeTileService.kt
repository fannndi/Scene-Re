package com.omarea.scene_mode.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.omarea.Scene
import com.omarea.vtools.R
import com.omarea.scene_mode.power.BypassCharge

/**
 * Quick Settings tile that toggles bypass charging. The shell probe is slow, so
 * every state read happens off the main thread and only the tile update is
 * posted back to it.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
class BypassChargeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        Thread {
            val supported = BypassCharge.supported()
            val active = BypassCharge.isActive()
            Scene.post { applyTile(supported, active) }
        }.start()
    }

    override fun onClick() {
        Thread {
            if (BypassCharge.isActive()) {
                BypassCharge.disable()
            } else if (BypassCharge.supported()) {
                BypassCharge.enable()
            }
            val supported = BypassCharge.supported()
            val active = BypassCharge.isActive()
            Scene.post { applyTile(supported, active) }
        }.start()
        super.onClick()
    }

    private fun applyTile(supported: Boolean, active: Boolean) {
        qsTile?.apply {
            label = getString(R.string.tile_bypass_charge)
            state = when {
                !supported -> Tile.STATE_UNAVAILABLE
                active -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }
}
