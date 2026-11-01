package app.capri

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Executa os comandos no celular. Devolve a frase que a Capri fala. */
class Actions(private val ctx: Context) {

    fun run(a: Action): String {
        return try {
            when (a.kind) {
                "OPEN" -> openApp(a.arg)
                "SEARCH" -> view("https://www.google.com/search?q=" + Uri.encode(a.arg), "Pesquisando " + a.arg + ".")
                "YOUTUBE" -> view("https://www.youtube.com/results?search_query=" + Uri.encode(a.arg), "Procurando " + a.arg + " no YouTube.")
                "MAPS" -> view("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(a.arg), "Traçando a rota para " + a.arg + ".")
                "URL" -> {
                    val u = if (a.arg.startsWith("http")) a.arg else "https://" + a.arg
                    view(u, "Abrindo " + a.arg + ".")
                }
                "TIMER" -> timer(a.arg.toIntOrNull() ?: 0)
                "ALARM" -> alarm(a.arg)
                "TORCH" -> torch(a.arg)
                "TIME" -> timeText()
                "DATE" -> dateText()
                "HOME" -> {
                    val i = Intent(Intent.ACTION_MAIN)
                    i.addCategory(Intent.CATEGORY_HOME)
                    start(i)
                    "Voltando para a tela inicial."
                }
                "WIFI" -> {
                    start(Intent(Settings.ACTION_WIFI_SETTINGS))
                    "Abrindo as configurações de Wi-Fi."
                }
                "BLUETOOTH" -> {
                    start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    "Abrindo as configurações de Bluetooth."
                }
                "DIAL" -> {
                    if (a.arg.length >= 8) {
                        start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a.arg)))
                        "Abri o discador com o número. É só tocar para ligar."
                    } else {
                        start(Intent(Intent.ACTION_DIAL))
                        "Ainda não leio seus contatos, então abri o discador."
                    }
                }
                else -> "Não sei fazer isso ainda."
            }
        } catch (e: ActivityNotFoundException) {
            "O celular não tem um app para isso."
        } catch (e: SecurityException) {
            "O Android não deixou eu fazer isso."
        } catch (e: Exception) {
            "Não consegui fazer isso."
        }
    }

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun view(url: String, say: String): String {
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return say
    }

    // ---------- aplicativos ----------

    private class Entry(val label: String, val pkg: String, val cls: String)

    private fun launchables(): List<Entry> {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN)
        q.addCategory(Intent.CATEGORY_LAUNCHER)
        val out = ArrayList<Entry>()
        for (ri in pm.queryIntentActivities(q, 0)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == ctx.packageName) continue
            out.add(Entry(ri.loadLabel(pm).toString(), ai.packageName, ai.name))
        }
        return out
    }

    private fun openApp(name: String): String {
        val apps = launchables()
        val labels = apps.map { it.label }
        var idx = Commands.pickApp(name, labels)
        if (idx < 0 && name.contains(" e ")) {
            idx = Commands.pickApp(name.substringBefore(" e "), labels)
        }
        if (idx < 0) {
            val near = Commands.suggest(name, labels, 3)
            return if (near.isEmpty()) {
                "Não achei o app " + name + " aqui."
            } else {
                "Não achei o app " + name + ". Parecidos: " + near.joinToString(", ") + "."
            }
        }
        val e = apps[idx]
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        i.component = ComponentName(e.pkg, e.cls)
        i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        start(i)
        return "Abrindo " + e.label + "."
    }

    // ---------- relógio ----------

    private fun timer(seconds: Int): String {
        if (seconds <= 0) return "Não entendi quanto tempo."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
        i.putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, "Capri")
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        start(i)
        return "Timer de " + spoken(seconds) + " ligado."
    }

    private fun alarm(hhmm: String): String {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return "Não entendi a hora."
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
        i.putExtra(AlarmClock.EXTRA_HOUR, h)
        i.putExtra(AlarmClock.EXTRA_MINUTES, m)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, "Capri")
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        start(i)
        return "Alarme marcado para " + h + (if (m == 0) " horas." else " e " + m + ".")
    }

    private fun spoken(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add(h.toString() + (if (h == 1) " hora" else " horas"))
        if (m > 0) parts.add(m.toString() + (if (m == 1) " minuto" else " minutos"))
        if (s > 0) parts.add(s.toString() + (if (s == 1) " segundo" else " segundos"))
        return parts.joinToString(" e ")
    }

    private fun timeText(): String {
        val c = java.util.Calendar.getInstance()
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val m = c.get(java.util.Calendar.MINUTE)
        val hs = if (h == 1) "é 1 hora" else "são " + h + " horas"
        return if (m == 0) "Agora " + hs + " em ponto." else "Agora " + hs + " e " + m + " minutos."
    }

    private fun dateText(): String {
        val fmt = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
        return "Hoje é " + fmt.format(Date()) + "."
    }

    // ---------- lanterna ----------

    private fun torch(mode: String): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var id: String? = null
        for (cid in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(cid)
            val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val back = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            if (flash && back) {
                id = cid
                break
            }
        }
        if (id == null) return "Este celular não tem lanterna."
        val want = when (mode) {
            "on" -> true
            "off" -> false
            else -> !torchOn
        }
        cm.setTorchMode(id, want)
        torchOn = want
        return if (want) "Lanterna ligada." else "Lanterna desligada."
    }

    companion object {
        @Volatile
        private var torchOn = false

        /** True se a frase devolvida por run() é um aviso de que não deu certo. */
        fun failed(say: String): Boolean =
            say.startsWith("Não ") || say.startsWith("O celular") ||
                say.startsWith("O Android") || say.startsWith("Este celular")
    }
}
