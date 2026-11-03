package app.capri

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Chamada à API do Gemini (Google). Tem plano grátis com chave de aistudio.google.com. */
object Gemini {

    val MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.5-flash")

    /** Tenta o modelo pedido e, se ele não existir ou estourar o limite, os outros da lista. */
    fun ask(
        apiKey: String,
        preferred: String,
        system: String,
        history: List<Pair<String, String>>,
        onStatus: ((String) -> Unit)? = null
    ): String {
        val order = ArrayList<String>()
        if (preferred.startsWith("gemini")) order.add(preferred)
        for (m in MODELS) {
            if (!order.contains(m)) order.add(m)
        }
        var last: Exception? = null
        val start = System.currentTimeMillis()
        for (m in order) {
            if (last != null && System.currentTimeMillis() - start > 70000L) break
            for (attempt in 1..2) {
                try {
                    return one(apiKey, m, system, history)
                } catch (e: ClaudeException) {
                    if (e.code == "http_404" || e.code == "http_429") {
                        last = e
                        break
                    } else if (Net.retryable(e)) {
                        last = e
                        if (attempt == 1) {
                            onStatus?.invoke("O servidor está ocupado, tentando de novo...")
                            Net.sleep(Net.delayFor(1, e))
                        }
                    } else {
                        throw e
                    }
                } catch (e: java.io.IOException) {
                    last = e
                    if (attempt == 1) Net.sleep(1500L)
                }
            }
        }
        val l = last
        if (l is ClaudeException) throw l
        if (l is java.io.IOException) throw l
        throw ClaudeException("empty", "nenhum modelo respondeu")
    }

    private fun one(
        apiKey: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>
    ): String {
        val contents = JSONArray()
        for (m in history) {
            val role = if (m.first == "assistant") "model" else "user"
            val parts = JSONArray().put(JSONObject().put("text", m.second))
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        val gen = JSONObject().put("maxOutputTokens", 1024)
        if (model.startsWith("gemini-2.5") && model.contains("flash")) {
            // sem "pensar" antes de responder: mais rápido e não gasta o limite de texto
            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put("contents", contents)
            .put("generationConfig", gen)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                var msg = raw.take(300)
                try {
                    msg = JSONObject(raw).getJSONObject("error").optString("message", msg)
                } catch (e: Exception) {
                    // mantém o texto bruto
                }
                throw ClaudeException("http_$status", msg)
            }

            val cands = JSONObject(raw).optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                throw ClaudeException("empty", "sem resposta")
            }
            val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    sb.append(parts.getJSONObject(i).optString("text"))
                }
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) {
                throw ClaudeException("empty", "resposta vazia")
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
