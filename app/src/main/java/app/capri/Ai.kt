package app.capri

/** Escolhe o serviço pela chave: sk-ant- é Anthropic, o resto é tratado como chave do Google (Gemini). */
object Ai {

    fun ask(
        key: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>
    ): String {
        return if (key.startsWith("sk-ant-")) {
            val m = if (model.startsWith("claude")) model else Prefs.DEFAULT_MODEL
            Claude.ask(key, m, system, history)
        } else {
            Gemini.ask(key, model, system, history)
        }
    }
}
