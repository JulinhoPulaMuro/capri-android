package app.capri

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import java.io.File
import java.util.concurrent.Executors

/**
 * Fala com a voz natural: quebra o texto em frases, pede o áudio de cada uma e já toca a primeira
 * enquanto as outras chegam. Tudo (menos o pedido de rede) roda na thread principal, via [main].
 */
class NaturalSpeaker(private val ctx: Context, private val main: Handler) {

    private val io = Executors.newSingleThreadExecutor()
    private var gen = 0
    private val queue = ArrayList<File>()
    private var player: MediaPlayer? = null
    private var playing = false
    private var fetchDone = false
    private var onFinished: (() -> Unit)? = null

    /**
     * Fala o texto. onFinished: terminou de falar. onFail: não deu para gerar a voz (o motivo vai junto);
     * só vale se falhou antes de qualquer som, para quem chamou poder usar outra voz.
     */
    fun speak(
        key: String, voice: String, style: String, text: String,
        onFinished: () -> Unit, onFail: (String) -> Unit
    ) {
        stop()
        val my = gen
        this.onFinished = onFinished
        val chunks = split(text)
        if (chunks.isEmpty()) {
            main.post { if (my == gen) finish() }
            return
        }
        io.execute {
            var first = true
            for (c in chunks) {
                if (my != gen) return@execute
                var file: File? = null
                var why = ""
                try {
                    val bytes = GeminiTts.synth(key, voice, style, c)
                    val f = File(ctx.cacheDir, "voz_" + System.nanoTime() + ".wav")
                    f.writeBytes(bytes)
                    file = f
                } catch (e: ClaudeException) {
                    why = e.code + " " + (e.message ?: "").take(120)
                } catch (e: Exception) {
                    why = e.javaClass.simpleName + " " + (e.message ?: "").take(120)
                }
                if (my != gen) {
                    file?.delete()
                    return@execute
                }
                if (file == null) {
                    if (first) {
                        val reason = why
                        main.post {
                            if (my == gen) {
                                this.onFinished = null
                                onFail(reason)
                            }
                        }
                        return@execute
                    }
                    break
                }
                first = false
                val f = file
                main.post { if (my == gen) enqueue(f) else f.delete() }
            }
            main.post {
                if (my == gen) {
                    fetchDone = true
                    maybeFinish()
                }
            }
        }
    }

    /** Para na hora, sem chamar nenhum aviso. */
    fun stop() {
        gen++
        for (f in queue) f.delete()
        queue.clear()
        releasePlayer()
        playing = false
        fetchDone = false
        onFinished = null
    }

    fun shutdown() {
        stop()
        io.shutdownNow()
    }

    private fun enqueue(f: File) {
        queue.add(f)
        if (!playing) playNext()
    }

    private fun playNext() {
        if (queue.isEmpty()) {
            playing = false
            maybeFinish()
            return
        }
        val f = queue.removeAt(0)
        playing = true
        val my = gen
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                f.delete()
                if (my == gen) {
                    releasePlayer()
                    playNext()
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                f.delete()
                if (my == gen) {
                    releasePlayer()
                    playNext()
                }
                true
            }
            mp.prepare()
            player = mp
            mp.start()
        } catch (e: Exception) {
            f.delete()
            releasePlayer()
            playNext()
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try {
            p.stop()
        } catch (e: Exception) {
            // já parado
        }
        try {
            p.release()
        } catch (e: Exception) {
            // já liberado
        }
    }

    private fun maybeFinish() {
        if (fetchDone && queue.isEmpty() && !playing) finish()
    }

    private fun finish() {
        val cb = onFinished
        onFinished = null
        fetchDone = false
        cb?.invoke()
    }

    companion object {
        /** Quebra em pedaços de frases: o primeiro curto (para começar logo) e os outros maiores. */
        fun split(text: String): List<String> {
            val t = text.trim()
            if (t.isEmpty()) return emptyList()
            val sentences = t.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
            val out = ArrayList<String>()
            var cur = StringBuilder()
            for (s in sentences) {
                val limit = if (out.isEmpty()) 140 else 260
                if (cur.isNotEmpty() && cur.length + s.length + 1 > limit) {
                    out.add(cur.toString())
                    cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
    }
}
