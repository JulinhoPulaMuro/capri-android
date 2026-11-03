package app.capri

import java.io.IOException

/** Ajudas para chamar a IA sem desistir na primeira falha. Não depende do Android. */
object Net {

    /** Falhas que costumam passar sozinhas: servidor ocupado, limite por minuto, erro do servidor. */
    fun retryable(e: ClaudeException): Boolean = when (e.code) {
        "http_429", "http_500", "http_502", "http_503", "http_504", "http_529", "empty" -> true
        else -> false
    }

    /** Quanto esperar antes da próxima tentativa (1 = depois da primeira falha). */
    fun delayFor(attempt: Int, e: ClaudeException?): Long {
        val ra = e?.retryAfterMs ?: 0L
        if (ra > 0L) return ra.coerceIn(800L, 8000L)
        return when (attempt) {
            1 -> 1500L
            2 -> 3500L
            else -> 6000L
        }
    }

    fun sleep(ms: Long) {
        Thread.sleep(ms)
    }

    /** Modelo reserva para quando o principal está sobrecarregado. */
    fun fallbackModel(primary: String): String =
        if (primary.contains("haiku")) "claude-sonnet-5-5" else "claude-haiku-5-5"

    /**
     * Encurta o histórico para o pedido ficar leve: corta mensagens enormes, joga fora as mais velhas
     * quando passa do orçamento e garante que começa com uma mensagem da pessoa.
     */
    fun budget(history: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var used = 0
        var i = history.size - 1
        while (i >= 0) {
            val m = history[i]
            val isLast = i == history.size - 1
            val cap = if (isLast) 4000 else 1200
            val text = if (m.second.length > cap) m.second.take(cap) + "..." else m.second
            if (!isLast && used + text.length > 7000) break
            used += text.length
            out.add(0, Pair(m.first, text))
            i--
        }
        while (out.isNotEmpty() && out[0].first != "user") out.removeAt(0)
        return out
    }
}

/** Escolhe o serviço pela chave: sk-ant- é Anthropic, o resto é tratado como chave do Google (Gemini). */
object Ai {

    /**
     * Pergunta à IA. Se o servidor estiver ocupado, tenta de novo e depois troca de modelo.
     * onStatus (opcional) é chamado, fora da thread principal, quando está insistindo.
     */
    fun ask(
        key: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>,
        onStatus: ((String) -> Unit)? = null
    ): String {
        val hist = Net.budget(history)
        if (!key.startsWith("sk-ant-")) {
            return Gemini.ask(key, model, system, hist, onStatus)
        }
        val primary = if (model.startsWith("claude")) model else Prefs.DEFAULT_MODEL
        val chain = ArrayList<String>()
        chain.add(primary)
        val fb = Net.fallbackModel(primary)
        if (fb != primary) chain.add(fb)

        val start = System.currentTimeMillis()
        var first: Exception? = null
        for ((mi, m) in chain.withIndex()) {
            val attempts = if (mi == 0) 3 else 2
            for (a in 1..attempts) {
                try {
                    return Claude.ask(key, m, system, hist)
                } catch (e: ClaudeException) {
                    if (!Net.retryable(e)) {
                        // o modelo reserva pode nem existir na conta: nesse caso vale o erro do principal
                        if (mi > 0 && first != null) throw first
                        throw e
                    }
                    if (first == null) first = e
                    if (a < attempts) {
                        onStatus?.invoke("O servidor da IA está ocupado, tentando de novo ($a/$attempts)...")
                        Net.sleep(Net.delayFor(a, e))
                    }
                } catch (e: IOException) {
                    if (first == null) first = e
                    if (a < attempts) {
                        onStatus?.invoke("A conexão falhou, tentando de novo ($a/$attempts)...")
                        Net.sleep(Net.delayFor(a, null))
                    }
                }
                if (System.currentTimeMillis() - start > 70000L) break
            }
            if (System.currentTimeMillis() - start > 70000L) break
            if (mi == 0 && chain.size > 1) onStatus?.invoke("Ainda ocupado, tentando com outro modelo...")
        }
        throw first ?: ClaudeException("empty", "sem resposta")
    }
}
