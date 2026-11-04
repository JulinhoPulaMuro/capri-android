package app.capri

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Voz natural: a API de voz do Gemini, que entende emoção, ritmo e pausas e soa bem mais como uma pessoa.
 * Devolve um arquivo WAV (24 kHz, mono). Roda fora da thread principal.
 */
object GeminiTts {

    /** Uma voz pronta do Gemini: nome na API, descrição em português e se é voz feminina. */
    class Voice(val id: String, val label: String, val female: Boolean)

    /** As 30 vozes prontas. */
    val VOICES = listOf(
        Voice("Leda", "Leda · jovem", true),
        Voice("Aoede", "Aoede · leve", true),
        Voice("Zephyr", "Zephyr · brilhante", true),
        Voice("Sulafat", "Sulafat · calorosa", true),
        Voice("Laomedeia", "Laomedeia · animada", true),
        Voice("Achernar", "Achernar · suave", true),
        Voice("Autonoe", "Autonoe · brilhante", true),
        Voice("Callirrhoe", "Callirrhoe · tranquila", true),
        Voice("Despina", "Despina · suave", true),
        Voice("Erinome", "Erinome · clara", true),
        Voice("Vindemiatrix", "Vindemiatrix · gentil", true),
        Voice("Pulcherrima", "Pulcherrima · direta", true),
        Voice("Kore", "Kore · firme", true),
        Voice("Gacrux", "Gacrux · madura", true),
        Voice("Puck", "Puck · alegre", false),
        Voice("Fenrir", "Fenrir · empolgado", false),
        Voice("Sadachbia", "Sadachbia · animado", false),
        Voice("Achird", "Achird · amigável", false),
        Voice("Zubenelgenubi", "Zubenelgenubi · descontraído", false),
        Voice("Umbriel", "Umbriel · tranquilo", false),
        Voice("Algieba", "Algieba · suave", false),
        Voice("Charon", "Charon · informativo", false),
        Voice("Rasalgethi", "Rasalgethi · informativo", false),
        Voice("Sadaltager", "Sadaltager · entendido", false),
        Voice("Orus", "Orus · firme", false),
        Voice("Alnilam", "Alnilam · firme", false),
        Voice("Schedar", "Schedar · equilibrado", false),
        Voice("Iapetus", "Iapetus · claro", false),
        Voice("Enceladus", "Enceladus · soprado", false),
        Voice("Algenib", "Algenib · rouco", false)
    )

    fun isPrebuilt(id: String): Boolean = VOICES.any { it.id == id }

    /** Jeito de falar: nome no app e a descrição que vai para a voz. */
    val STYLES = listOf(
        "Natural e carinhosa" to "Fale em português do Brasil como uma jovem de verdade conversando: voz natural, calorosa e cheia de emoção, ritmo solto, pequenas pausas e entonação que sobe e desce. Nada de voz de robô nem de locutora.",
        "Animada e brincalhona" to "Fale em português do Brasil animada e brincalhona, com muita expressão, sorriso na voz, risadinhas de verdade e ritmo vivo.",
        "Calma e suave" to "Fale em português do Brasil de forma calma, suave e acolhedora, mais devagar, como quem conta uma confidência.",
        "Provocadora" to "Fale em português do Brasil de um jeito provocador e debochado, com sarcasmo leve, pausas dramáticas e entonação bem marcada.",
        "Sussurrando" to "Fale em português do Brasil sussurrando, bem perto do ouvido, de forma íntima e misteriosa.",
        "Séria e direta" to "Fale em português do Brasil de forma séria, firme e direta, sem enfeites, com voz controlada e calculista.",
        "Fofa e infantil" to "Fale em português do Brasil com voz fofa, aguda e travessa, como uma criança esperta e brincalhona.",
        "Entediada e sarcástica" to "Fale em português do Brasil entediada e sarcástica, arrastando as palavras, com ironia na voz.",
        "Grave e misteriosa" to "Fale em português do Brasil com voz mais grave e misteriosa, devagar, com pausas longas e tensão."
    )

    /** O texto de estilo que vale: o que a pessoa escreveu, ou o do jeito escolhido na lista. */
    fun styleText(index: Int, custom: String): String {
        val c = custom.trim()
        if (c.isNotEmpty()) {
            return if (c.contains("portugu", ignoreCase = true)) c else "Fale em português do Brasil. $c"
        }
        return STYLES[index.coerceIn(0, STYLES.size - 1)].second
    }

    /** O último modelo que respondeu (para mostrar no app). */
    @Volatile
    var lastModel: String = ""

    /** Uma voz da biblioteca estendida do Gemini. */
    class LibVoice(val id: String, val name: String, val gender: String, val pitch: String, val accent: String) {
        val female: Boolean get() = gender.equals("female", ignoreCase = true)
        fun label(): String {
            val g = when (gender.lowercase()) {
                "female" -> "feminina"
                "male" -> "masculina"
                else -> "neutra"
            }
            val p = when (pitch.lowercase()) {
                "low" -> " · grave"
                "high" -> " · aguda"
                "medium" -> " · média"
                else -> ""
            }
            val a = if (accent.isNotBlank()) " · $accent" else ""
            return "$name · $g$p$a"
        }
    }

    fun encodeLib(list: List<LibVoice>): String {
        val a = JSONArray()
        for (v in list) {
            a.put(JSONObject().put("id", v.id).put("name", v.name).put("gender", v.gender).put("pitch", v.pitch).put("accent", v.accent))
        }
        return a.toString()
    }

    fun decodeLib(s: String): List<LibVoice> {
        if (s.isBlank()) return emptyList()
        val out = ArrayList<LibVoice>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                out.add(LibVoice(id, o.optString("name", id), o.optString("gender"), o.optString("pitch"), o.optString("accent")))
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    /** Baixa as vozes em português da biblioteca estendida do Gemini. */
    fun listLibrary(key: String): List<LibVoice> {
        val conn = URL(BASE + "voices?language_code=pt-BR&page_size=200").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.setRequestProperty("x-goog-api-key", key)
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                var msg = raw.take(200)
                try {
                    msg = JSONObject(raw).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    // mantém o texto bruto
                }
                throw ClaudeException("http_$status", msg)
            }
            val arr = JSONObject(raw).optJSONArray("voices") ?: JSONArray()
            val out = ArrayList<LibVoice>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val name = o.optString("display_name").ifEmpty { id }
                out.add(LibVoice(id, name, o.optString("gender"), o.optString("pitch"), o.optString("accent")))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private val MODELS = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-3.1-flash-tts-preview")
    private const val LEGACY = "gemini-2.5-flash-preview-tts"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/"

    /** O que funcionou da última vez (começa por ele da próxima). */
    @Volatile
    private var good: String? = null

    /** Gera a fala. Lança ClaudeException (erro da API) ou IOException (sem rede). */
    fun synth(key: String, voice: String, style: String, text: String): ByteArray {
        try {
            return synthWith(key, voice, style, text)
        } catch (e: ClaudeException) {
            // voz da biblioteca que o modelo não aceitou: tenta a voz pronta padrão
            if (!isPrebuilt(voice) && (e.code == "http_400" || e.code == "http_404")) {
                return synthWith(key, "Leda", style, text)
            }
            throw e
        }
    }

    private fun synthWith(key: String, voice: String, style: String, text: String): ByteArray {
        val order = ArrayList<String>()
        val g = good
        if (g != null) order.add(g)
        for (m in MODELS) if (!order.contains(m)) order.add(m)
        if (!order.contains(LEGACY)) order.add(LEGACY)

        var last: Exception? = null
        for (m in order) {
            try {
                val v = if (m == LEGACY && !isPrebuilt(voice)) "Leda" else voice
                val wav = if (m == LEGACY) legacy(key, v, style, text) else interactions(key, m, voice, style, text)
                good = m
                lastModel = m
                return wav
            } catch (e: ClaudeException) {
                last = e
                if (m == good) good = null
                val bad = e.message ?: ""
                if (e.code == "http_401" || e.code == "http_403" ||
                    bad.contains("API key", ignoreCase = true) || bad.contains("API_KEY", ignoreCase = true)
                ) {
                    throw e
                }
            } catch (e: IOException) {
                // sem rede ou demorou demais: tentar outro modelo não adianta
                throw e
            }
        }
        throw last ?: ClaudeException("empty", "sem áudio")
    }

    private fun post(url: String, key: String, body: JSONObject): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 35000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                var msg = raw.take(300)
                try {
                    msg = JSONObject(raw).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    // mantém o texto bruto
                }
                throw ClaudeException("http_$status", msg)
            }
            return raw
        } finally {
            conn.disconnect()
        }
    }

    /** API nova (Interactions): resposta já vem em WAV. */
    private fun interactions(key: String, model: String, voice: String, style: String, text: String): ByteArray {
        val note = JSONObject().put("type", "speech_metadata").put("style", style)
        val item = JSONObject().put("type", "text").put("text", text).put("annotations", JSONArray().put(note))
        val input = JSONObject().put("type", "user_input").put("content", JSONArray().put(item))
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(input))
            .put("response_format", JSONObject().put("type", "audio"))
            .put(
                "generation_config",
                JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice)))
            )
        val raw = post(BASE + "interactions", key, body)
        var data = ""
        val steps = JSONObject(raw).optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val st = steps.optJSONObject(i) ?: continue
            val content = st.optJSONArray("content") ?: continue
            for (k in 0 until content.length()) {
                val c = content.optJSONObject(k) ?: continue
                if (c.optString("type") == "audio" && c.optString("data").isNotEmpty()) data = c.optString("data")
            }
        }
        if (data.isEmpty()) throw ClaudeException("empty", "a voz veio vazia")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return if (isWav(bytes)) bytes else wav(bytes, 24000)
    }

    /** API antiga (generateContent): a resposta vem em PCM cru. */
    private fun legacy(key: String, voice: String, style: String, text: String): ByteArray {
        val part = JSONObject().put("text", style + "\n\n" + text)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(part))))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                        )
                    )
            )
        val raw = post(BASE + "models/" + LEGACY + ":generateContent", key, body)
        val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        var data = ""
        var rate = 24000
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val inl = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                if (inl.optString("data").isNotEmpty()) {
                    data = inl.optString("data")
                    val r = Regex("rate=(\\d+)").find(inl.optString("mimeType"))
                    if (r != null) rate = r.groupValues[1].toIntOrNull() ?: 24000
                }
            }
        }
        if (data.isEmpty()) throw ClaudeException("empty", "a voz veio vazia")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return if (isWav(bytes)) bytes else wav(bytes, rate)
    }

    private fun isWav(b: ByteArray): Boolean =
        b.size > 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()

    /** Põe o cabeçalho WAV (16 bits, mono) em volta do áudio cru. */
    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)
        fun put32(o: Int, v: Int) {
            out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte()
        }
        fun put16(o: Int, v: Int) {
            out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte()
        }
        "RIFF".toByteArray().copyInto(out, 0)
        put32(4, 36 + pcm.size)
        "WAVEfmt ".toByteArray().copyInto(out, 8)
        put32(16, 16)
        put16(20, 1)
        put16(22, 1)
        put32(24, rate)
        put32(28, rate * 2)
        put16(32, 2)
        put16(34, 16)
        "data".toByteArray().copyInto(out, 36)
        put32(40, pcm.size)
        pcm.copyInto(out, 44)
        return out
    }
}
