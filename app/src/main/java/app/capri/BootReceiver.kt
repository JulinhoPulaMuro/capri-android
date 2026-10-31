package app.capri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * O Android não deixa um app ligar o microfone sozinho logo após reiniciar.
 * Então só deixamos uma notificação para a pessoa tocar e ligar a Capri.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs(context).apiKey.isEmpty()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("capri_boot", "Lembrete para ligar", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            context, 4,
            Intent(context, StartActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, "capri_boot")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Capri desligada")
            .setContentText("Toque para ligar e voltar a ouvir \"Ok Capri\"")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }
}
