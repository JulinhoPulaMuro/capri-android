package app.capri

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
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
                        "Abri o discador. Diga o nome de um contato para eu achar o número."
                    }
                }
                "CALLNAME" -> callName(a.arg)
                "ZAP" -> message(a.arg, true)
                "SMS" -> message(a.arg, false)
                "GAME" -> randomGame()
                "RANDOMAPP" -> randomApp()
                "VOLUME" -> volume(a.arg)
                "MEDIA" -> media(a.arg)
                "SETTINGS" -> settings(a.arg)
                "GLOBAL" -> withA11y { it.global(a.arg) }
                "CLICK" -> withA11y { it.click(a.arg) }
                "SCROLL" -> withA11y { it.scroll(a.arg) }
                "TYPE" -> withA11y { it.type(a.arg) }
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

    private class Entry(val label: String, val pkg: String, val cls: String, val info: ApplicationInfo?)

    private fun launchables(): List<Entry> {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN)
        q.addCategory(Intent.CATEGORY_LAUNCHER)
        val out = ArrayList<Entry>()
        for (ri in pm.queryIntentActivities(q, 0)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == ctx.packageName) continue
            out.add(Entry(ri.loadLabel(pm).toString(), ai.packageName, ai.name, ai.applicationInfo))
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


    // ---------- jogo e app aleatórios ----------

    private fun isGame(info: ApplicationInfo?): Boolean {
        if (info == null) return false
        if (info.category == ApplicationInfo.CATEGORY_GAME) return true
        @Suppress("DEPRECATION")
        if ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
        return GAME_PREFIXES.any { info.packageName.startsWith(it) }
    }

    private fun launchGame(e: Entry) {
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        i.component = ComponentName(e.pkg, e.cls)
        i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        start(i)
    }

    private fun pickRandom(list: List<Entry>): Entry {
        val rest = list.filter { it.pkg != lastRandom }
        val pool = if (rest.isEmpty()) list else rest
        val e = pool[java.util.Random().nextInt(pool.size)]
        lastRandom = e.pkg
        return e
    }

    private fun randomGame(): String {
        val games = launchables().filter { isGame(it.info) }
        if (games.isEmpty()) return "Não achei nenhum jogo instalado."
        val e = pickRandom(games)
        launchGame(e)
        return "Sorteei " + e.label + ". Bora jogar, choom!"
    }

    private fun randomApp(): String {
        val apps = launchables()
        if (apps.isEmpty()) return "Não achei nenhum app para abrir."
        val e = pickRandom(apps)
        launchGame(e)
        return "Abrindo " + e.label + ". Surpresa!"
    }

    // ---------- volume e mídia ----------

    private fun volume(arg: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val st = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(st)
        val cur = am.getStreamVolume(st)
        val step = maxOf(1, max / 5)
        return when {
            arg == "up" -> {
                am.setStreamVolume(st, minOf(max, cur + step), AudioManager.FLAG_SHOW_UI)
                "Aumentei o volume."
            }
            arg == "down" -> {
                am.setStreamVolume(st, maxOf(0, cur - step), AudioManager.FLAG_SHOW_UI)
                "Abaixei o volume."
            }
            arg == "max" -> {
                am.setStreamVolume(st, max, AudioManager.FLAG_SHOW_UI)
                "Volume no máximo."
            }
            arg == "min" -> {
                am.setStreamVolume(st, 0, AudioManager.FLAG_SHOW_UI)
                "Volume no mínimo."
            }
            arg == "mute" -> {
                am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "Mudo."
            }
            arg == "unmute" -> {
                am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "Som de volta."
            }
            arg.startsWith("pct:") -> {
                val p = (arg.removePrefix("pct:").toIntOrNull() ?: 50).coerceIn(0, 100)
                am.setStreamVolume(st, Math.round(max * p / 100f), AudioManager.FLAG_SHOW_UI)
                "Volume em " + p + " por cento."
            }
            else -> "Não entendi o que fazer com o volume."
        }
    }

    private fun media(arg: String): String {
        val (code, say) = when (arg) {
            "pause" -> Pair(KeyEvent.KEYCODE_MEDIA_PAUSE, "Pausei.")
            "play" -> Pair(KeyEvent.KEYCODE_MEDIA_PLAY, "Voltando a tocar.")
            "next" -> Pair(KeyEvent.KEYCODE_MEDIA_NEXT, "Próxima.")
            "prev" -> Pair(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Voltando uma.")
            else -> Pair(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Pronto.")
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return say
    }

    // ---------- contatos e mensagens ----------

    private class Contact(val name: String, val number: String, val type: Int)

    private fun contacts(): List<Contact>? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val out = ArrayList<Contact>()
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val c = ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)
        if (c != null) {
            try {
                while (c.moveToNext() && out.size < 6000) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    out.add(Contact(n, num, c.getInt(2)))
                }
            } finally {
                c.close()
            }
        }
        return out
    }

    /** Acha o contato pelo nome falado. Devolve o contato, ou a frase de erro. */
    private fun lookup(name: String): Pair<Contact?, String> {
        val digits = name.filter { it.isDigit() }
        if (digits.length >= 8) return Pair(Contact(name, digits, 0), "")
        val list = contacts() ?: return Pair(null, "Preciso da permissão de contatos. Toque em Contatos no app da Capri.")
        if (list.isEmpty()) return Pair(null, "Não achei nenhum contato com número neste celular.")
        val labels = list.map { it.name }
        val idx = Commands.pickApp(name, labels)
        if (idx < 0) {
            val near = Commands.suggest(name, labels.distinct(), 3)
            val tail = if (near.isEmpty()) "" else " Parecidos: " + near.joinToString(", ") + "."
            return Pair(null, "Não achei " + name + " nos contatos." + tail)
        }
        val chosen = list[idx].name
        val same = list.filter { it.name == chosen }
        val best = same.firstOrNull { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE } ?: same[0]
        return Pair(best, "")
    }

    private fun callName(name: String): String {
        val (c, err) = lookup(name)
        if (c == null) return err
        start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", c.number, null)))
        return "Abri o discador com " + c.name + ". É só tocar em ligar."
    }

    private fun waNumber(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (raw.trim().startsWith("+")) return d
        d = d.trimStart('0')
        return if (d.length in 10..11) "55" + d else d
    }

    private fun message(arg: String, zap: Boolean): String {
        val name = arg.substringBefore("\n").trim()
        val text = arg.substringAfter("\n", "").trim()
        val (c, err) = lookup(name)
        if (c == null) return err
        return if (zap) {
            var url = "https://wa.me/" + waNumber(c.number)
            if (text.isNotEmpty()) url += "?text=" + Uri.encode(text)
            start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            "Deixei a conversa com " + c.name + " aberta no WhatsApp. É só tocar em enviar."
        } else {
            val i = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", c.number, null))
            if (text.isNotEmpty()) i.putExtra("sms_body", text)
            start(i)
            "Deixei o SMS para " + c.name + " pronto. É só tocar em enviar."
        }
    }

    // ---------- configurações ----------

    private fun settings(key: String): String {
        val (action, say) = when (key) {
            "battery" -> Pair(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Abri as configurações de bateria.")
            "display" -> Pair(Settings.ACTION_DISPLAY_SETTINGS, "Abri as configurações de tela e brilho.")
            "sound" -> Pair(Settings.ACTION_SOUND_SETTINGS, "Abri as configurações de som.")
            "location" -> Pair(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Abri a tela de localização. O Android não deixa um app ligar isso sozinho.")
            "airplane" -> Pair(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Abri a tela do modo avião. O Android não deixa um app ligar isso sozinho.")
            "date" -> Pair(Settings.ACTION_DATE_SETTINGS, "Abri data e hora.")
            "accessibility" -> Pair(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Abri a acessibilidade.")
            "nfc" -> Pair(Settings.ACTION_NFC_SETTINGS, "Abri a tela do NFC. O Android não deixa um app ligar isso sozinho.")
            "data" -> Pair(Settings.ACTION_WIRELESS_SETTINGS, "Abri as redes. O Android não deixa um app ligar os dados sozinho.")
            "storage" -> Pair(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Abri o armazenamento.")
            "privacy" -> Pair(Settings.ACTION_PRIVACY_SETTINGS, "Abri a privacidade.")
            "security" -> Pair(Settings.ACTION_SECURITY_SETTINGS, "Abri a segurança.")
            "locale" -> Pair(Settings.ACTION_LOCALE_SETTINGS, "Abri o idioma.")
            "keyboard" -> Pair(Settings.ACTION_INPUT_METHOD_SETTINGS, "Abri o teclado.")
            "apps" -> Pair(Settings.ACTION_APPLICATION_SETTINGS, "Abri a lista de apps.")
            "cast" -> Pair(Settings.ACTION_CAST_SETTINGS, "Abri a transmissão de tela.")
            "hotspot" -> Pair(Settings.ACTION_WIRELESS_SETTINGS, "Abri as redes. O hotspot fica em Conexões ou Rede e internet. O Android não deixa um app ligar isso sozinho.")
            else -> return "Não sei abrir essa configuração."
        }
        start(Intent(action))
        return say
    }

    // ---------- Acessibilidade ----------

    private fun withA11y(block: (A11yService) -> String): String {
        val s = A11yService.instance ?: return "Preciso da Acessibilidade ligada no app da Capri para mexer na tela."
        return block(s)
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

        @Volatile
        private var lastRandom = ""

        /** Prefixos de pacotes de jogos conhecidos, para quando o Android não marca a categoria. */
        private val GAME_PREFIXES = listOf(
            "com.supercell.", "com.activision.", "com.garena.", "com.dts.freefire", "com.tencent.ig",
            "com.tencent.tmgp", "com.mojang.", "com.roblox.", "com.miHoYo.", "com.HoYoverse.",
            "com.kiloo.", "com.innersloth.", "com.ea.", "com.king.", "com.gameloft.", "com.ubisoft.",
            "com.rovio.", "com.nianticlabs.", "com.epicgames.", "com.netease.", "com.proximabeta.",
            "com.levelinfinite.", "com.mobile.legends", "com.moonton.", "com.zynga.", "com.playrix.",
            "com.miniclip.", "com.halfbrick.", "com.imangi.", "com.nintendo.", "com.sega.", "com.konami."
        )

        /** True se a frase devolvida por run() é um aviso de que não deu certo. */
        fun failed(say: String): Boolean =
            say.startsWith("Não ") || say.startsWith("Preciso") || say.startsWith("Isso mexe") ||
                say.startsWith("O celular") || say.startsWith("O Android") || say.startsWith("Este celular")
    }
}
