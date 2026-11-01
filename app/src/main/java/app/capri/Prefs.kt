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
