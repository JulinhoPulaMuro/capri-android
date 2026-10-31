package app.capri

import java.text.Normalizer

/** Um pedido que o app sabe executar (abrir app, pesquisar, timer...). */
class Action(val kind: String, val arg: String = "")

/**
 * Entende comandos diretos em português sem gastar a IA.
 * Não depende do Android, para poder ser testado no computador.
 */
object Commands {

    fun plain(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return n.replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9: ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val FILLERS = listOf(
        "por favor", "pra mim", "para mim", "capri", "agora", "entao", "voce", "vc",
        "pode", "poderia", "consegue", "quero que", "eu quero", "queria", "gostaria de", "ok"
    ).map { Regex("\\b" + it + "\\b") }

    private val NUM_WORDS = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4,
        "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10,
        "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14, "quatorze" to 14,
        "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18,
        "dezenove" to 19, "vinte" to 20, "trinta" to 30, "quarenta" to 40,
        "cinquenta" to 50, "sessenta" to 60
    )

    /** Troca "vinte e cinco" por "25", "cinco" por "5" etc. */
    fun numberize(t: String): String {
        val tok = t.split(" ")
        val out = ArrayList<String>()
        var i = 0
        while (i < tok.size) {
            val v = NUM_WORDS[tok[i]]
            if (v == null) {
                out.add(tok[i])
                i++
            } else if (v >= 20 && i + 2 < tok.size && tok[i + 1] == "e" &&
                (NUM_WORDS[tok[i + 2]] ?: 99) in 1..9
            ) {
                out.add((v + NUM_WORDS[tok[i + 2]]!!).toString())
                i += 3
            } else {
                out.add(v.toString())
                i++
            }
        }
        return out.joinToString(" ")
    }

    /** Soma "1 hora e 30 minutos", "meia hora", "45 segundos" em segundos. 0 se não achar. */
    fun duration(text: String): Int {
        var t = text
        t = Regex("\\b(\\d+) horas? e meia\\b").replace(t) { (it.groupValues[1].toInt() * 60 + 30).toString() + " minutos" }
        t = t.replace("meia hora", "30 minutos")
        var total = 0
        for (m in Regex("\\b(\\d+)\\s*(horas?|hs?|minutos?|mins?|segundos?|segs?)(?![a-z])").findAll(t)) {
            val n = m.groupValues[1].toInt()
            val u = m.groupValues[2]
            total += when {
                u.startsWith("h") -> n * 3600
                u.startsWith("m") -> n * 60
                else -> n
            }
        }
        return total
    }

    /** Lê "7:30", "7h30", "7 horas e 30", "às 7 e meia". Devolve hora e minuto ou null. */
    fun alarmTime(t: String): Pair<Int, Int>? {
        val tail = "\\s*(?:e\\s*)?(\\d{1,2}(?![0-9a-z])|meia(?![a-z]))?"
        val a = Regex("\\b(\\d{1,2})\\s*(?::|h(?![a-z])|horas?(?![a-z]))" + tail).find(t)
        val m = a ?: Regex("\\b(?:as|pras|pra as|para as)\\s+(\\d{1,2})(?![0-9:])" + tail).find(t)
        if (m == null) return null
        var h = m.groupValues[1].toInt()
        val g = m.groupValues[2]
        val min = if (g == "meia") 30 else if (g.isNotEmpty()) g.toInt() else 0
        if ((t.contains("da tarde") || t.contains("da noite")) && h in 1..11) h += 12
        if (h !in 0..23 || min !in 0..59) return null
        return Pair(h, min)
    }

    private val SEARCH_VERB = "(?:pesquis(?:a|ar|e)|procur(?:a|ar|e)|busc(?:a|ar)|busque|googl(?:a|ar|e))"
    private val PLAY_VERB = "(?:toc(?:a|ar)|toque|coloc(?:a|ar|que)|bot(?:a|ar)|reproduz(?:ir)?|ouvir|assist(?:ir|a|e))"
    private val OPEN_VERB = "(?:abr\\w*|inici(?:a|ar|e)|execut(?:a|ar|e)|lanc(?:a|ar|e)|aciona(?:r)?|vai (?:para|pro|pra)|ir (?:para|pro|pra)|entra(?:r)? (?:no|na)|me leva(?:r)? (?:no|na|pro|pra))"

    private fun cleanTerm(s: String): String {
        var term = s.trim()
        term = Regex("\\b(?:no|na|pelo|pela) (?:youtube|you tube|google|internet|web|navegador|chrome)\\b").replace(term, " ")
        term = term.replace(Regex("\\s+"), " ").trim()
        for (k in 0 until 2) {
            term = term.replace(Regex("^(?:sobre|por|pelo|pela|a|o|as|os|para|pra|de|do|da|uma|um) "), "")
        }
        return term.trim()
    }

    /** Tenta entender o texto como um comando. Null se não parecer comando. */
    fun parse(raw: String): Action? {
        var t = plain(raw)
        for (f in FILLERS) t = f.replace(t, " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        val tn = numberize(t)

        // lanterna
        if (t.contains("lanterna")) {
            val off = Regex("\\b(?:apag\\w*|deslig\\w*|fecha\\w*|desativ\\w*)").containsMatchIn(t)
            val on = Regex("\\b(?:lig(?:a|ar|ue)|acend\\w*|ativ(?:a|ar|e))\\b").containsMatchIn(t)
            return Action("TORCH", if (off) "off" else if (on) "on" else "toggle")
        }

        // timer
        if (Regex("\\b(?:timer|temporizador)\\b").containsMatchIn(t)) {
            val s = duration(tn)
            if (s > 0) return Action("TIMER", s.toString())
            return null
        }

        // alarme
        val alarmWord = t.contains("alarme") || Regex("\\b(?:acorda|acorde|desperta|desperte)\\b").containsMatchIn(t)
        if (alarmWord) {
            if (Regex("\\b(?:cancel\\w*|desativ\\w*|apag\\w*|delet\\w*|remov\\w*|exclu\\w*|deslig\\w*)").containsMatchIn(t)) {
                return Action("OPEN", "relogio")
            }
            if (t.contains("daqui")) {
                val s = duration(tn)
                if (s > 0) return Action("TIMER", s.toString())
            }
            val at = alarmTime(tn)
            if (at != null) {
                return Action("ALARM", "%02d:%02d".format(at.first, at.second))
            }
            if (t.contains("alarme")) return Action("OPEN", "relogio")
            return null
        }

        // horas e data
        if (Regex("\\bque horas\\b|\\bhora certa\\b|\\b(?:diz|diga|fala|fale|ver|saber) as horas\\b|\\bhoras sao\\b").containsMatchIn(t)) {
            return Action("TIME")
        }
        if (t.contains("que dia e") || t.contains("data de hoje") || t.contains("qual a data") || t.contains("qual o dia")) {
            return Action("DATE")
        }

        // tela inicial
        if (Regex("\\b(?:tela inicial|pagina inicial|pra home|para home)\\b").containsMatchIn(t) || t == "home") {
            return Action("HOME")
        }

        // wifi e bluetooth
        if (Regex("^(?:abr\\w*|lig\\w*|desl\\w*|ativ\\w*|desativ\\w*|config\\w*|ajust\\w*)\\b").containsMatchIn(t)) {
            if (t.contains("wifi") || t.contains("wi fi")) return Action("WIFI")
            if (t.contains("bluetooth")) return Action("BLUETOOTH")
        }

        // endereço de site
        var r = raw.lowercase()
        r = r.replace(" ponto com ponto br", ".com.br").replace(" ponto com", ".com")
            .replace(" ponto net", ".net").replace(" ponto org", ".org").replace(" ponto br", ".br")
        val url = Regex("(?:[a-z0-9-]+\\.)+(?:com|net|org|gov|edu|io|app|dev|br)(?:\\.br)?(?:/\\S*)?").find(r)
        if (url != null && (Regex("^" + OPEN_VERB).containsMatchIn(t) || t.split(" ").size <= 2)) {
            return Action("URL", url.value)
        }

        // rotas e mapas
        val maps = Regex("^(?:como (?:eu )?(?:chego|chegar)|navega(?:r)?|rota|traca (?:uma )?rota|me leva(?:r)?|leva(?:r)?)\\s*(?:ate|para|pra|em|na|no|a|ao|de)?\\s*(.+)$").find(t)
            ?: Regex("^(?:onde fica|mapa de)\\s+(.+)$").find(t)
        if (maps != null) {
            val place = cleanTerm(maps.groupValues[1])
            if (place.isNotEmpty()) return Action("MAPS", place)
        }

        // pesquisar e tocar
        val sv = Regex("^" + SEARCH_VERB + "\\s+(.+)$").find(t)
        if (sv != null) {
            val whole = sv.groupValues[1]
            val term = cleanTerm(whole)
            if (term.isEmpty()) return null
            val yt = Regex("\\b(?:no|na|pelo) (?:youtube|you tube)\\b").containsMatchIn(whole)
            return Action(if (yt) "YOUTUBE" else "SEARCH", term)
        }
        val pv = Regex("^" + PLAY_VERB + "\\s+(.+)$").find(t)
        if (pv != null) {
            val term = cleanTerm(pv.groupValues[1])
            if (term.isNotEmpty()) return Action("YOUTUBE", term)
            return null
        }

        // ligar para um número
        val dial = Regex("^(?:lig(?:a|ar)|chama(?:r)?|telefona(?:r)?|disca|discar)\\s+(?:para|pra)\\s+(.+)$").find(t)
            ?: Regex("^(?:lig(?:a|ar)|chama(?:r)?|telefona(?:r)?|disca|discar)\\s+([0-9 ]{8,})$").find(t)
        if (dial != null) {
            val digits = dial.groupValues[1].filter { it.isDigit() }
            return Action("DIAL", if (digits.length >= 8) digits else "")
        }

        // abrir aplicativo
        val open = Regex("^" + OPEN_VERB + "\\s+(.+)$").find(t)
        if (open != null) {
            var name = open.groupValues[1]
            name = name.replace(Regex("^(?:o|a|os|as|um|uma|no|na|nos|nas|do|da)\\s+"), "")
            name = name.replace(Regex("^(?:aplicativo|app|aplicacao|programa)\\s+(?:do |da |de )?"), "")
            name = name.replace(Regex("\\s+(?:app|aplicativo)$"), "").trim()
            if (name.isNotEmpty()) return Action("OPEN", name)
        }
        return null
    }

    // ---------- achar o app certo pelo nome ----------

    private fun compact(s: String): String = plain(s).replace(" ", "")

    private val ALIASES: Map<String, List<String>> = mapOf(
        "zap" to listOf("whatsapp"), "zapzap" to listOf("whatsapp"), "whats" to listOf("whatsapp"),
        "whatsapp" to listOf("whatsapp"), "watsapp" to listOf("whatsapp"),
        "watsap" to listOf("whatsapp"), "whatsap" to listOf("whatsapp"), "uatsap" to listOf("whatsapp"),
        "insta" to listOf("instagram"), "face" to listOf("facebook"), "yt" to listOf("youtube"),
        "tiktok" to listOf("tiktok"), "twitter" to listOf("twitter", "x"),
        "camera" to listOf("camera"), "galeria" to listOf("galeria", "fotos", "photos", "gallery"),
        "fotos" to listOf("fotos", "photos", "galeria", "gallery"),
        "relogio" to listOf("relogio", "clock", "alarme"), "despertador" to listOf("relogio", "clock"),
        "configuracoes" to listOf("configuracoes", "settings", "ajustes"),
        "config" to listOf("configuracoes", "settings", "ajustes"),
        "ajustes" to listOf("configuracoes", "settings", "ajustes"),
        "configuracao" to listOf("configuracoes", "settings", "ajustes"),
        "loja" to listOf("playstore", "googleplay", "lojadeapps"),
        "playstore" to listOf("playstore", "googleplay"), "googleplay" to listOf("playstore", "googleplay"),
        "navegador" to listOf("chrome", "navegador", "browser", "internet", "firefox"),
        "internet" to listOf("chrome", "navegador", "browser", "internet"),
        "telefone" to listOf("telefone", "phone", "discador"), "discador" to listOf("telefone", "phone", "discador"),
        "contatos" to listOf("contatos", "contacts"), "agenda" to listOf("contatos", "calendario", "calendar"),
        "calculadora" to listOf("calculadora", "calculator"),
        "mapas" to listOf("maps", "mapas"), "maps" to listOf("maps", "mapas"),
        "musica" to listOf("youtubemusic", "spotify", "musica", "music"),
        "email" to listOf("gmail", "email"), "mensagens" to listOf("mensagens", "messages", "sms"),
        "sms" to listOf("mensagens", "messages", "sms"),
        "calendario" to listOf("calendario", "calendar"),
        "arquivos" to listOf("arquivos", "files", "meusarquivos"),
        "notas" to listOf("keep", "notas", "notes"), "blocodenotas" to listOf("keep", "notas", "notes")
    )

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun score(label: String, c: String): Int {
        if (label.isEmpty() || c.isEmpty()) return 0
        if (label == c) return 100
        if (c.length >= 3 && label.startsWith(c)) return 80
        if (c.length >= 5 && lev(label, c) <= (if (c.length >= 8) 2 else 1)) return 70
        if (c.length >= 3 && label.contains(c)) return 60
        if (label.length >= 4 && c.contains(label)) return 55
        return 0
    }

    /** Índice do app mais parecido com o nome falado, ou -1. */
    fun pickApp(name: String, labels: List<String>): Int {
        val c0 = compact(name)
        val cands = ArrayList<String>()
        cands.add(c0)
        val al = ALIASES[c0]
        if (al != null) cands.addAll(al)
        var best = -1
        var bestScore = 0
        var bestLen = Int.MAX_VALUE
        for (i in labels.indices) {
            val l = compact(labels[i])
            var s = 0
            for (k in cands.indices) {
                var x = score(l, cands[k])
                if (x > 0 && k == 0) x += 1
                if (x > s) s = x
            }
            if (s >= 55 && (s > bestScore || (s == bestScore && l.length < bestLen))) {
                best = i
                bestScore = s
                bestLen = l.length
            }
        }
        return best
    }

    // ---------- etiquetas que a IA pode devolver ----------

    private val TAG = Regex("\\[\\[\\s*([A-Za-z]+)\\s*:\\s*([^\\]]*?)\\s*\\]\\]")

    /** Separa o texto falado das etiquetas [[ABRIR: x]] que a IA coloca no fim. */
    fun extractTags(text: String): Pair<String, List<Action>> {
        val acts = ArrayList<Action>()
        for (m in TAG.findAll(text)) {
            val k = plain(m.groupValues[1])
            val a = m.groupValues[2].trim()
            if (a.isEmpty() && k != "lanterna") continue
            val act: Action? = when (k) {
                "abrir" -> Action("OPEN", a)
                "pesquisar" -> Action("SEARCH", a)
                "youtube" -> Action("YOUTUBE", a)
                "navegar" -> Action("MAPS", a)
                "site" -> Action("URL", a)
                "ligar" -> Action("DIAL", a.filter { it.isDigit() })
                "lanterna" -> Action("TORCH", if (plain(a).startsWith("desl")) "off" else if (plain(a).startsWith("lig")) "on" else "toggle")
                "timer" -> {
                    val s = a.filter { it.isDigit() }.toIntOrNull() ?: 0
                    val d = duration(numberize(plain(a)))
                    val secs = if (d > 0) d else s
                    if (secs > 0) Action("TIMER", secs.toString()) else null
                }
                "alarme" -> {
                    val at = alarmTime(numberize(plain(a)))
                    if (at != null) Action("ALARM", "%02d:%02d".format(at.first, at.second)) else null
                }
                else -> null
            }
            if (act != null) acts.add(act)
        }
        val clean = TAG.replace(text, "").replace(Regex("[ \\t]{2,}"), " ").trim()
        return Pair(clean, acts)
    }
}
