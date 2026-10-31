package app.capri

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Porta de entrada sem tela: liga a Capri (ou a chama, se já estiver ligada) e fecha. */
class StartActivity : Activity() {

    override fun onResume() {
        super.onResume()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!mic || !Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, MainActivity::class.java))
        } else if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        } else {
            startForegroundService(Intent(this, OverlayService::class.java))
        }
        finish()
    }
}
