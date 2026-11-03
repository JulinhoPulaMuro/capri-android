package app.capri

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Configurações e histórico, guardados só neste celular. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("capri", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(value) {
            sp.edit().putString("apiKey", value.trim()).apply()
        }

    var model: String
        get() = sp.getString("model", "") ?: ""
        set(value) {
            sp.edit().putString("model", value.trim()).apply()
        }

    var listenWake: Boolean
        get() = sp.getBoolean("listenWake", true)
        set(value) {
            sp.edit().putBoolean("listenWake", value).apply()
        }

    var speak: Boolean
        get() = sp.getBoolean("speak", true)
        set(value) {
            sp.edit().putBoolean("speak", value).apply()
        }

    var autoHide: Boolean
        get() = sp.getBoolean("autoHide", false)
        set(value) {
            sp.edit().putBoolean("autoHide", value).apply()
        }

    var voiceName: String
        get() = sp.getString("voiceName", "") ?: ""
        set(value) {
            sp.edit().putString("voiceName", value).apply()
        }

    var pitch: Float
        get() = sp.getFloat("pitch", 1.0f)
        set(value) {
            sp.edit().putFloat("pitch", value).apply()
        }

    var rate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(value) {
            sp.edit().putFloat("rate", value).apply()
        }

    var ttsEngine: String
        get() = sp.getString("ttsEngine", "") ?: ""
        set(value) {
            sp.edit().putString("ttsEngine", value).apply()
        }

    var onlyFemale: Boolean
        get() = sp.getBoolean("onlyFemale", true)
        set(value) {
            sp.edit().putBoolean("onlyFemale", value).apply()
        }

    var femaleVoices: Set<String>
        get() = HashSet<String>(sp.getStringSet("female", emptySet<String>()) ?: emptySet<String>())
        set(value) {
            sp.edit().putStringSet("female", HashSet<String>(value)).apply()
        }

    var chatter: Boolean
        get() = sp.getBoolean("chatter", true)
        set(value) {
            sp.edit().putBoolean("chatter", value).apply()
        }

    var cutout: Boolean
        get() = sp.getBoolean("cutout", false)
        set(value) {
            sp.edit().putBoolean("cutout", value).apply()
        }

    /** 0 sem corpo, 1 fofo, 2 esguio, 3 robô. */
    var bodyType: Int
        get() = sp.getInt("bodyType", 1)
        set(value) {
            sp.edit().putInt("bodyType", value).apply()
        }

    var bodyColor: Int
        get() = sp.getInt("bodyColor", 0xFFFF4F9D.toInt())
        set(value) {
            sp.edit().putInt("bodyColor", value).apply()
        }

    /** Onde fica o rosto sobre a imagem do corpo: centro e largura, como fração da imagem. */
    var faceX: Float
        get() = sp.getFloat("faceX", 0.5f)
        set(value) {
            sp.edit().putFloat("faceX", value).apply()
        }

    var faceY: Float
        get() = sp.getFloat("faceY", 0.3f)
        set(value) {
            sp.edit().putFloat("faceY", value).apply()
        }

    var faceW: Float
        get() = sp.getFloat("faceW", 0.2f)
        set(value) {
            sp.edit().putFloat("faceW", value).apply()
        }

    /** Onde fica o rosto sobre a foto do esqueleto (centro e largura, frações da foto). */
    var rigFaceX: Float
        get() = sp.getFloat("rigFaceX", 0.5f)
        set(value) {
            sp.edit().putFloat("rigFaceX", value).apply()
        }

    var rigFaceY: Float
        get() = sp.getFloat("rigFaceY", 0.12f)
        set(value) {
            sp.edit().putFloat("rigFaceY", value).apply()
        }

    var rigFaceW: Float
        get() = sp.getFloat("rigFaceW", 0.2f)
        set(value) {
            sp.edit().putFloat("rigFaceW", value).apply()
        }

    /** Tamanho da figura na tela, 0.6 a 1.6. */
    var figureScale: Float
        get() = sp.getFloat("figureScale", 1.0f)
        set(value) {
            sp.edit().putFloat("figureScale", value).apply()
        }

    /** Juntas do esqueleto (36 números, frações da imagem). Vazio = posição padrão. */
    var rigJoints: String
        get() = sp.getString("rigJoints", "") ?: ""
        set(value) {
            sp.edit().putString("rigJoints", value).apply()
        }

    /** Onde ficam as janelas, em pixels do canto de cima e da esquerda da tela. -1 = ainda não escolhido. */
    var charX: Int
        get() = sp.getInt("charX", -1)
        set(value) {
            sp.edit().putInt("charX", value).apply()
        }

    var charY: Int
        get() = sp.getInt("charY", -1)
        set(value) {
            sp.edit().putInt("charY", value).apply()
        }

    var inputX: Int
        get() = sp.getInt("inputX", -1)
        set(value) {
            sp.edit().putInt("inputX", value).apply()
        }

    var inputY: Int
        get() = sp.getInt("inputY", -1)
        set(value) {
            sp.edit().putInt("inputY", value).apply()
        }

    /** Faz poses e danças sozinha de vez em quando. */
    var moves: Boolean
        get() = sp.getBoolean("moves", true)
        set(value) {
            sp.edit().putBoolean("moves", value).apply()
        }

    /** Passeia pela tela de vez em quando. */
    var wander: Boolean
        get() = sp.getBoolean("wander", true)
        set(value) {
            sp.edit().putBoolean("wander", value).apply()
        }

    /** Efeito de falha de sinal: some e reaparece em outro lugar, com rastro, e dá piscadas soltas. */
    var glitch: Boolean
        get() = sp.getBoolean("glitch", true)
        set(value) {
            sp.edit().putBoolean("glitch", value).apply()
        }

    /** As duas cores que se separam do corpo no glitch. */
    var glitchColorA: Int
        get() = sp.getInt("glitchColorA", 0xFFFF2BD6.toInt())
        set(value) {
            sp.edit().putInt("glitchColorA", value).apply()
        }

    var glitchColorB: Int
        get() = sp.getInt("glitchColorB", 0xFF2AF0FF.toInt())
        set(value) {
            sp.edit().putInt("glitchColorB", value).apply()
        }

    /** Voz natural (Gemini): mais humana que a voz do celular. */
    var naturalVoice: Boolean
        get() = sp.getBoolean("naturalVoice", true)
        set(value) {
            sp.edit().putBoolean("naturalVoice", value).apply()
        }

    var naturalVoiceName: String
        get() = sp.getString("naturalVoiceName", "Leda") ?: "Leda"
        set(value) {
            sp.edit().putString("naturalVoiceName", value).apply()
        }

    var naturalStyle: Int
        get() = sp.getInt("naturalStyle", 0)
        set(value) {
            sp.edit().putInt("naturalStyle", value).apply()
        }

    /** Chave do Gemini só para a voz (quando a chave principal é de outro serviço). */
    var voiceKey: String
        get() = sp.getString("voiceKey", "") ?: ""
        set(value) {
            sp.edit().putString("voiceKey", value.trim()).apply()
        }

    /** A chave que vale para a voz natural: a própria, ou a principal se for do Google. Vazio = não tem. */
    fun naturalKey(): String {
        val v = voiceKey
        if (v.isNotEmpty()) return v
        val k = apiKey
        return if (k.isNotEmpty() && !k.startsWith("sk-ant-")) k else ""
    }

    var posX: Int
        get() = sp.getInt("posX", -1)
        set(value) {
            sp.edit().putInt("posX", value).apply()
        }

    var posY: Int
        get() = sp.getInt("posY", -1)
        set(value) {
            sp.edit().putInt("posY", value).apply()
        }

    fun loadHistory(): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(sp.getString("history", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val role = o.optString("r")
                val text = o.optString("t")
                if ((role == "user" || role == "assistant") && text.isNotEmpty()) {
                    out.add(Pair(role, text))
                }
            }
        } catch (e: Exception) {
            out.clear()
        }
        return out
    }

    fun saveHistory(items: List<Pair<String, String>>) {
        val arr = JSONArray()
        for (p in items) {
            arr.put(JSONObject().put("r", p.first).put("t", p.second))
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
    }
}
