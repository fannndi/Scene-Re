package com.omarea.runtime

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.omarea.vtools.R

/**
 * Quick-settings tile for the opt-in bypass charging (AZenith parity).
 *
 * One tap toggles the feature; the state mirrors the pref, not the live node
 * (the threshold logic decides when charging actually pauses).
 */
@RequiresApi(24)
class BypassTileService : TileService() {

    override fun onClick() {
        BypassCharging.setEnabled(this, !BypassCharging.isEnabled(this))
        updateTile()
        super.onClick()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val enabled = BypassCharging.isEnabled(this)
        qsTile?.run {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.settings_bypass_charge)
            icon = Icon.createWithResource(this@BypassTileService, R.drawable.p1)
        }
        qsTile?.updateTile()
    }
}
