package app.capri

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Botão "Capri" nas configurações rápidas: liga a Capri ou chama ela, de dentro de qualquer app. */
class CapriTile : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_TOGGLE))
            return
        }
        val i = Intent(this, StartActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.label = "Capri"
        val on = OverlayService.running && OverlayService.shown
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(if (!OverlayService.running) "Desligada" else if (OverlayService.shown) "À mostra" else "Escondida")
        }
        t.updateTile()
    }
}
