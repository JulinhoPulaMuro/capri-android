package app.capri

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ClaudeException(val code: String, message: String, val retryAfterMs: Long = 0L) : Exception(message)

/** Chamada direta à API de mensagens da Anthropic. Roda fora da thread principal. */
object Claude {

    fun ask(
        apiKey: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>
    ): String {
        val msgs = JSONArray()
        for (m in history) {
            msgs.put(JSONObject().put("role", m.first).put("content", m.second))
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 400)
            .put("system", system)
            .put("messages", msgs)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
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
                val ra = (conn.getHeaderField("retry-after") ?: "").trim().toDoubleOrNull()
                val raMs = if (ra != null && ra > 0.0) (ra * 1000.0).toLong() else 0L
                throw ClaudeException("http_$status", msg, raMs)
            }

            val content = JSONObject(raw).optJSONArray("content")
                ?: throw ClaudeException("empty", "sem conteúdo")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") {
                    sb.append(block.optString("text"))
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
