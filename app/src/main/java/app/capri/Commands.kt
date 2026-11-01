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

        // esconder o boneco
        if (Regex("^(?:se )?(?:escond\\w*|sum(?:ir|a|i)|some|desaparec\\w*)(?: (?:daqui|agora|da tela|ai|o boneco))?$|^sai(?:r)? (?:da tela|daqui)$|^vai embora$|^tchau$").containsMatchIn(t)) {
            return Action("HIDE")
        }

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

        // botões do sistema (precisam da Acessibilidade)
        val glob = if (Regex("^" + SEARCH_VERB).containsMatchIn(t)) null else globalArg(t)
        if (glob != null) return Action("GLOBAL", glob)

        // wifi e bluetooth
        if (Regex("^(?:abr\\w*|lig\\w*|desl\\w*|ativ\\w*|desativ\\w*|config\\w*|ajust\\w*)\\b").containsMatchIn(t)) {
            if (t.contains("wifi") || t.contains("wi fi")) return Action("WIFI")
            if (t.contains("bluetooth")) return Action("BLUETOOTH")
        }

        // outras telas de configuração
        val sk = settingsKey(t)
        if (sk != null) return Action("SETTINGS", sk)

        // jogo ou app aleatório
        val gameWord = Regex("\\b(?:jogo|jogos|game|games)\\b").containsMatchIn(t)
        val appWord = Regex("\\b(?:app|apps|aplicativo|aplicativos|programa)\\b").containsMatchIn(t)
        val randWord = Regex("\\b(?:aleatori\\w*|qualquer|sort\\w*|algum|alguma|surpresa|surpreend\\w*|escolh\\w*|um dos|algum dos|instalad\\w*|sugere\\w*|indica\\w*|recomenda\\w*)\\b").containsMatchIn(t)
        val startsOk = Regex("^(?:" + OPEN_VERB + "|sort\\w*|escolh\\w*|joga\\w*|quero|bora|vamos|me (?:da|de|sugere|indica|escolhe)|sugere\\w*|indica\\w*|recomenda\\w*|coloc\\w*|bota\\w*)").containsMatchIn(t)
        if (gameWord && startsOk && (randWord || Regex("^" + OPEN_VERB + "\\s+(?:um |o )?(?:jogo|game)$").containsMatchIn(t))) {
            return Action("GAME")
        }
        if (Regex("^(?:vamos |bora |quero |vou |deixa eu |me deixa )?jogar(?: (?:algo|alguma coisa|qualquer coisa|um jogo|algum jogo|um game))?$").containsMatchIn(t)) {
            return Action("GAME")
        }
        if (appWord && startsOk && Regex("\\b(?:aleatori\\w*|qualquer|sort\\w*|surpresa)\\b").containsMatchIn(t)) {
            return Action("RANDOMAPP")
        }

        // volume (antes de "tocar", para "bota o volume..." não virar busca)
        val volCtx = t.contains("volume") ||
            Regex("^(?:mut\\w*|silenc\\w*|desmut\\w*)(?: (?:o |a )?(?:celular|som|musica|video))?$|^(?:aument\\w*|abaix\\w*|diminu\\w*|baix\\w*|sob\\w*|reduz\\w*) (?:o |a )?(?:som|musica)$|^tir\\w* o som$|^tir\\w* do mudo$|^(?:coloc\\w*|bot\\w*|poe|pon\\w*) (?:o celular )?no mudo$|^sem som$|^modo mudo$").containsMatchIn(t)
        if (volCtx && !Regex("^" + SEARCH_VERB).containsMatchIn(t)) {
            val v = volumeArg(t)
            if (v != null) return Action("VOLUME", v)
        }

        // música tocando agora: pausar, continuar, pular, voltar
        val media = mediaArg(t)
        if (media != null) return Action("MEDIA", media)

        // rolar a tela
        val scr = Regex("^(?:sobe|subir|suba|rol\\w*|desc(?:e|er|a)|desliz\\w*)\\b(.*)$").find(t)
        if (scr != null && Regex("^(?: (?:a|o|para|pra|um pouco|mais|tela|pagina|lista|feed|baixo|cima|de novo|de vez))*$").matches(scr.groupValues[1])) {
            val up = Regex("^(?:sobe|subir|suba)\\b|\\bcima\\b").containsMatchIn(t)
            return Action("SCROLL", if (up) "up" else "down")
        }

        // tocar num botão da tela
        val clk = Regex("^(?:clica(?:r)?|clique|aperta(?:r)?|aperte|pressiona(?:r)?|pressione|selecion\\w*|toca(?:r)? (?:em|no botao|na opcao|no icone|na aba|no item)|toque (?:em|no botao|na opcao|no icone|na aba|no item))\\s+(?:(?:em|no|na|nos|nas|o|a|botao|opcao|aba|icone|item) )*(.+)$").find(t)
        if (clk != null) {
            val target = clk.groupValues[1].trim()
            if (target.isNotEmpty()) return Action("CLICK", target)
        }

        // digitar no campo de texto aberto (mantém o texto como foi dito)
        val typ = Regex("(?is)^\\s*(?:(?:ok\\s+)?capri[\\s,.!]*)?(?:pode\\s+)?(?:digit(?:a|ar|e)|escrev(?:e|er) (?:ai|isso|no campo|na tela))\\b[\\s:,]*(.+)$").find(raw)
        if (typ != null) {
            val txt = typ.groupValues[1].trim()
            if (txt.isNotEmpty()) return Action("TYPE", txt)
        }

        // mandar mensagem
        val msg = messageAction(t, raw)
        if (msg != null) return msg

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
        val dial = Regex("^(?:lig(?:a|ar)|chama(?:r)?|telefona(?:r)?|disca|discar)\\s+(?:para|pra|pro)\\s+(.+)$").find(t)
            ?: Regex("^(?:lig(?:a|ar)|chama(?:r)?|telefona(?:r)?|disca|discar)\\s+([0-9 ]{8,})$").find(t)
            ?: Regex("^(?:chama(?:r)?|telefona(?:r)?|disca|discar)\\s+(?:o |a )?(.+)$").find(t)
        if (dial != null) {
            val g = dial.groupValues[1]
            val digits = g.filter { it.isDigit() }
            if (digits.length >= 8) return Action("DIAL", digits)
            val who = g.replace(Regex("^(?:o|a|meu|minha)\\s+"), "").trim()
            if (who.isNotEmpty() && digits.isEmpty()) return Action("CALLNAME", who)
            return Action("DIAL", "")
        }

        // abrir aplicativo
        val open = Regex("^" + OPEN_VERB + "\\s+(.+)$").find(t)
        if (open != null) {
            var name = open.groupValues[1]
            name = name.replace(Regex("^(?:o|a|os|as|um|uma|no|na|nos|nas|do|da)\\s+"), "")
            name = name.replace(Regex("^(?:aplicativo|app|aplicacao|programa)\\s+(?:do |da |de )?"), "")
            name = name.replace(Regex("^(?:jogo|game)\\s+(?:do |da |de )?"), "")
            name = name.replace(Regex("\\s+(?:app|aplicativo)$"), "").trim()
            if (name.isNotEmpty()) return Action("OPEN", name)
        }
        return null
    }

    // ---------- volume, mídia, botões do sistema, configurações, mensagens ----------

    /** up, down, max, min, mute, unmute ou pct:NN. Null se não parece pedido de volume. */
    fun volumeArg(t: String): String? {
        val tn = numberize(t)
        if (Regex("\\bdesmut\\w*|\\btir\\w* do mudo\\b").containsMatchIn(t)) return "unmute"
        if (Regex("\\bmut\\w*|\\bsilenc\\w*|\\bsem som\\b|\\btir\\w* o som\\b|\\bmodo mudo\\b|\\bno mudo\\b").containsMatchIn(t)) return "mute"
        val pct = Regex("\\b(\\d{1,3})\\b").find(tn)?.groupValues?.get(1)?.toIntOrNull()
        val relative = Regex("\\b(?:aument\\w*|abaix\\w*|diminu\\w*|baix\\w*|reduz\\w*|sobe|suba|subir)\\b").containsMatchIn(t) &&
            !Regex("\\b(?:para|pra|ate)\\b").containsMatchIn(t)
        if (pct != null && pct in 0..100 && t.contains("volume") && !relative) return "pct:" + pct
        if (Regex("\\b(?:maxim\\w*|no talo|total)\\b").containsMatchIn(t)) return "max"
        if (Regex("\\b(?:minim\\w*|zero|zerar)\\b").containsMatchIn(t)) return "min"
        if (Regex("\\b(?:abaix\\w*|diminu\\w*|baix\\w*|reduz\\w*|menos|menor|abafa\\w*)\\b").containsMatchIn(t)) return "down"
        if (Regex("\\b(?:aument\\w*|sobe|suba|subir|eleva\\w*|mais alto|mais|maior|alto|alta)\\b").containsMatchIn(t)) return "up"
        return null
    }

    /** pause, play, next, prev. Null se não for pedido para a mídia que está tocando. */
    fun mediaArg(t: String): String? {
        val m = "(?:musica|som|video|cancao|faixa|reproducao|midia)"
        if (Regex("^(?:continu\\w*|retom\\w*|despaus\\w*|da play|play|volta a tocar|toca(?:r)?|toque)(?: (?:a|o|essa|esse))?(?: " + m + ")?$").containsMatchIn(t)) {
            return "play"
        }
        if (Regex("^(?:paus\\w*|par(?:a|ar|e)|stop|interromp\\w*)(?: (?:a|o|essa|esse|tudo))?(?: " + m + ")?$").containsMatchIn(t)) {
            return "pause"
        }
        if (Regex("^(?:pul\\w*|proxima|proximo|next|skip|avanc\\w*|passa(?:r)?)(?: (?:essa|esta|a|o|para a|pra))*(?: " + m + ")?(?: (?:proxima|proximo))?$").containsMatchIn(t)) {
            return "next"
        }
        val hasMedia = Regex("\\b(?:musica|faixa|cancao|video|som|midia|anterior)\\b").containsMatchIn(t)
        if (hasMedia && Regex("^(?:volt\\w*|retroced\\w*|anterior|previous|(?:musica|faixa|cancao) anterior)\\b").containsMatchIn(t)) {
            return "prev"
        }
        return null
    }

    /** back, recents, notifications, quick, screenshot ou lock. */
    fun globalArg(t: String): String? {
        if (Regex("^(?:volt(?:a|ar)|retorn(?:a|ar)|botao voltar|aperta(?:r)? voltar|clica(?:r)? em voltar|volta uma tela|volta pra tras|volta para tras)$").containsMatchIn(t)) {
            return "back"
        }
        if (Regex("\\b(?:configuracoes rapidas|painel rapido|atalhos rapidos|central de controle)\\b").containsMatchIn(t)) return "quick"
        if (Regex("^(?:(?:abr\\w*|mostr\\w*|ver|exib\\w*) )?(?:(?:os|as|o|a) )?(?:apps |aplicativos |tarefas )?(?:recentes|multitarefa)$").containsMatchIn(t)) return "recents"
        if (Regex("\\bnotificac\\w*\\b").containsMatchIn(t) && Regex("^(?:abr\\w*|mostr\\w*|ver|puxa\\w*|desc\\w*|baix\\w*|exib\\w*)\\b").containsMatchIn(t)) {
            return "notifications"
        }
        if (t.split(" ").size <= 6 &&
            Regex("\\b(?:print|printscreen|screenshot|captur\\w* (?:de |da |a )?tela|foto da tela)\\b").containsMatchIn(t)
        ) {
            return "screenshot"
        }
        if (Regex("^(?:bloque\\w*|trav\\w*|tranc\\w*)(?: (?:a |o )?(?:tela|celular|telefone|aparelho))?$|^(?:desliga\\w*|apaga\\w*) a tela$").containsMatchIn(t)) {
            return "lock"
        }
        return null
    }

    /** Chave da tela de configurações a abrir, ou null. */
    fun settingsKey(t: String): String? {
        if (!Regex("^(?:abr\\w*|mostr\\w*|lig\\w*|desl\\w*|ativ\\w*|desativ\\w*|config\\w*|ajust\\w*|va|vai|ir|me leva\\w*|entra\\w*)\\b").containsMatchIn(t)) {
            return null
        }
        val cfg = Regex("\\b(?:config\\w*|ajustes?)\\b").containsMatchIn(t)
        // estas funcionam mesmo sem dizer "configurações"
        if (Regex("\\b(?:bateria|economia de bateria)\\b").containsMatchIn(t)) return "battery"
        if (Regex("\\b(?:modo aviao|aviao)\\b").containsMatchIn(t)) return "airplane"
        if (Regex("\\bacessibilidade\\b").containsMatchIn(t)) return "accessibility"
        if (Regex("\\bnfc\\b").containsMatchIn(t)) return "nfc"
        if (Regex("\\b(?:hotspot|roteador|compartilh\\w* (?:de |da )?internet|tethering)\\b").containsMatchIn(t)) return "hotspot"
        if (Regex("\\b(?:localizacao|gps)\\b").containsMatchIn(t)) return "location"
        if (Regex("\\bdados moveis\\b").containsMatchIn(t)) return "data"
        if (!cfg) return null
        if (Regex("\\b(?:tela|brilho|display)\\b").containsMatchIn(t)) return "display"
        if (Regex("\\b(?:som|audio|toque)\\b").containsMatchIn(t)) return "sound"
        if (Regex("\\b(?:data|hora)\\b").containsMatchIn(t)) return "date"
        if (Regex("\\b(?:armazenamento|memoria|espaco)\\b").containsMatchIn(t)) return "storage"
        if (Regex("\\bprivacidade\\b").containsMatchIn(t)) return "privacy"
        if (Regex("\\bseguranca\\b").containsMatchIn(t)) return "security"
        if (Regex("\\b(?:idioma|linguagem)\\b").containsMatchIn(t)) return "locale"
        if (Regex("\\bteclado\\b").containsMatchIn(t)) return "keyboard"
        if (Regex("\\b(?:apps|aplicativos)\\b").containsMatchIn(t)) return "apps"
        if (Regex("\\b(?:transmitir|cast|espelhar)\\b").containsMatchIn(t)) return "cast"
        return null
    }

    /** "manda zap pra mãe dizendo oi", "avisa a mãe que cheguei", "manda sms pro joão: ok". */
    fun messageAction(t: String, raw: String): Action? {
        val avisa = Regex("^avis(?:a|ar|e)\\s+(?:para |pra |pro )?(?:o |a )?(.+?)\\s+(?:que|de que)\\s+.+$").find(t)
        if (avisa != null) {
            val text = Regex("(?is)\\bque\\b\\s+(.+)$").find(raw)?.groupValues?.get(1)?.trim() ?: ""
            return Action("ZAP", avisa.groupValues[1].trim() + "\n" + capitalize(text))
        }
        val send = Regex("^(?:mand(?:a|ar|e)|envi(?:a|ar|e)|escrev(?:e|er))\\s+(.*)$").find(t) ?: return null
        val rest = send.groupValues[1]
        val kind = when {
            Regex("\\b(?:sms|mensagem de texto)\\b").containsMatchIn(rest) -> "SMS"
            Regex("\\b(?:zap|whats\\w*|watsap|mensagem|msg|recado)\\b").containsMatchIn(rest) -> "ZAP"
            else -> return null
        }
        val nm = Regex("\\b(?:para|pra|pro|ao|a)\\s+(?:o |a |meu |minha )?([^:]+?)(?:(?::|\\s+(?:dizendo|falando|escrevendo|avisando|perguntando|com o texto|com a mensagem|que)\\b).*)?$").find(rest)
            ?: return null
        val name = nm.groupValues[1].trim()
        if (name.isEmpty()) return null
        var text = Regex("(?is)\\b(?:dizendo|falando|escrevendo|avisando|perguntando|com o texto|com a mensagem)\\b[\\s:,]*(.+)$").find(raw)?.groupValues?.get(1)?.trim()
        if (text == null) text = Regex("(?s):\\s*(.+)$").find(raw)?.groupValues?.get(1)?.trim()
        return Action(kind, name + "\n" + capitalize(text ?: ""))
    }

    /** "nome | texto" vira "nome\ntexto". */
    private fun splitMsg(a: String): String {
        val i = a.indexOf('|')
        return if (i < 0) a.trim() + "\n" else a.substring(0, i).trim() + "\n" + a.substring(i + 1).trim()
    }

    private fun capitalize(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)

    // ---------- achar o app certo pelo nome ----------

    private fun compact(s: String): String = plain(s).replace(" ", "")

    private val ALIASES: Map<String, List<String>> = mapOf(
        "zap" to listOf("whatsapp"), "zapzap" to listOf("whatsapp"), "whats" to listOf("whatsapp"),
        "whatsapp" to listOf("whatsapp"), "watsapp" to listOf("whatsapp"),
        "watsap" to listOf("whatsapp"), "whatsap" to listOf("whatsapp"), "uatsap" to listOf("whatsapp"),
        "insta" to listOf("instagram"), "face" to listOf("facebook"), "yt" to listOf("youtube"),
        "tiktok" to listOf("tiktok"), "twitter" to listOf("twitter", "x"),
        "youtubemusic" to listOf("ytmusic", "youtubemusic", "youtubemusica"),
        "ytmusic" to listOf("ytmusic", "youtubemusic"),
        "youtubemusica" to listOf("ytmusic", "youtubemusic"),
        "musicadoyoutube" to listOf("ytmusic", "youtubemusic"),
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
        // o nome falado só contém o rótulo: vale se o rótulo cobre quase tudo (evita "youtube music" abrir "youtube")
        if (label.length >= 4 && c.contains(label) && label.length * 10 >= c.length * 8) return 55
        return 0
    }

    /** Índice do app mais parecido com o nome falado, ou -1. */
    fun pickApp(name: String, labels: List<String>): Int {
        val c0 = compact(name)
        val cands = ArrayList<String>()
        cands.add(c0)
        val al = ALIASES[c0]
        if (al != null) cands.addAll(al)
        if (c0.startsWith("google") && c0.length > 6) {
            val rest = c0.removePrefix("google")
            cands.add(rest)
            val al2 = ALIASES[rest]
            if (al2 != null) cands.addAll(al2)
        }
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

    /** Até n rótulos parecidos com o nome falado, para avisar quando o app não for achado. */
    fun suggest(name: String, labels: List<String>, n: Int): List<String> {
        val c = compact(name)
        if (c.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Double, String>>()
        for (lab in labels) {
            val l = compact(lab)
            if (l.isEmpty()) continue
            var sim = 1.0 - lev(l, c).toDouble() / maxOf(l.length, c.length)
            if (l.contains(c) || c.contains(l)) sim = maxOf(sim, 0.6)
            if (l.take(3) == c.take(3)) sim = maxOf(sim, 0.5)
            if (sim >= 0.45) scored.add(Pair(sim, lab))
        }
        return scored.sortedByDescending { it.first }.take(n).map { it.second }
    }

    // ---------- etiquetas que a IA pode devolver ----------

    private val TAG = Regex("\\[\\[\\s*([A-Za-z]+)\\s*:\\s*([^\\]]*?)\\s*\\]\\]")

    /** Separa o texto falado das etiquetas [[ABRIR: x]] que a IA coloca no fim. */
    fun extractTags(text: String): Pair<String, List<Action>> {
        val acts = ArrayList<Action>()
        for (m in TAG.findAll(text)) {
            val k = plain(m.groupValues[1])
            val a = m.groupValues[2].trim()
            if (a.isEmpty() && k != "lanterna" && k != "jogo" && k != "appaleatorio") continue
            val p = plain(a)
            val act: Action? = when (k) {
                "abrir" -> Action("OPEN", a)
                "pesquisar" -> Action("SEARCH", a)
                "youtube" -> Action("YOUTUBE", a)
                "navegar" -> Action("MAPS", a)
                "site" -> Action("URL", a)
                "ligar" -> {
                    val d = a.filter { it.isDigit() }
                    if (d.length >= 8 || a.any { it.isDigit() }) Action("DIAL", d) else Action("CALLNAME", a)
                }
                "ligarpara" -> Action("CALLNAME", a)
                "jogo" -> Action("GAME")
                "appaleatorio" -> Action("RANDOMAPP")
                "volume" -> {
                    val v = volumeArg("volume " + p)
                    if (v != null) Action("VOLUME", v) else null
                }
                "midia" -> {
                    val v = mediaArg(p)
                    if (v != null) Action("MEDIA", v) else null
                }
                "tela" -> {
                    val g = if (p.contains("rapid")) "quick" else (globalArg(p) ?: globalArg("abrir " + p))
                    if (g != null) Action("GLOBAL", g) else null
                }
                "rolar" -> Action("SCROLL", if (p.contains("cima") || p.startsWith("sub") || p.startsWith("sob")) "up" else "down")
                "clicar" -> Action("CLICK", p)
                "digitar" -> Action("TYPE", a)
                "zap" -> Action("ZAP", splitMsg(a))
                "sms" -> Action("SMS", splitMsg(a))
                "config" -> {
                    val s = settingsKey("abrir configuracoes " + p)
                    if (s != null) Action("SETTINGS", s) else null
                }
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
