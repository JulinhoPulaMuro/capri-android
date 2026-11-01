package app.capri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Mantém o boneco flutuando sobre os outros apps, escuta "Ok Capri",
 * fala as respostas e conversa com o Claude.
 */
class OverlayService : Service() {

    private enum class State { OFF, WAKE, COMMAND, THINKING, SPEAKING }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var params: WindowManager.LayoutParams? = null
    private var root: LinearLayout? = null
    private var bubble: TextView? = null
    private var inputRow: LinearLayout? = null
    private var input: EditText? = null
    private var figure: ImageView? = null
    private var dot: View? = null
    private var hideBubble: Runnable? = null
    private var expanded = false
    private var savedY = 0
    private var hideTask: Runnable? = null
    private var dead = false
    private var engineInUse = ""
    private val rnd = java.util.Random()
    private var chatTask: Runnable? = null
    private var userHidden = false

    private var state = State.OFF
    private var voiceTurn = false
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val history = ArrayList<Pair<String, String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        refreshTile()
        prefs = Prefs(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            toast("Falta a permissão para sobrepor outros apps.")
            stopSelf()
            return
        }
        history.addAll(prefs.loadHistory())
        trimHistory()
        if (!buildOverlay()) {
            stopSelf()
            return
        }
        initTts()
        if (prefs.listenWake) {
            startWake()
            say(Lines.greet(rnd), false)
        } else {
            setDot(DIM)
            say("Conectada, mas de ouvidos tapados: a escuta está desligada. Toque em mim para escrever.", false)
        }
        shown = true
        refreshTile()
        scheduleAutoHide(9000L)
        scheduleChatter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RELOAD -> loadFigure()
            ACTION_PREFS -> {
                applyWakePref()
                if (prefs.ttsEngine != engineInUse) {
                    tts?.stop()
                    tts?.shutdown()
                    tts = null
                    ttsReady = false
                    initTts()
                } else {
                    val t = tts
                    if (t != null && ttsReady) applyVoice(t)
                }
            }
            ACTION_SHOW -> summon()
            ACTION_TOGGLE -> toggleShown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        dead = true
        shown = false
        refreshTile()
        main.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // ignora
        }
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        val r = root
        if (r != null) {
            try {
                wm.removeView(r)
            } catch (e: Exception) {
                // ignora
            }
        }
        root = null
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------- notificação ----------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Capri ativa", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getService(
            this, 3,
            Intent(this, OverlayService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_IMMUTABLE
        )
        val callAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notify), "Chamar", show
        ).build()
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notify), "Desativar", stop
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Capri ativa")
            .setContentText("Diga \"Ok Capri\" ou toque em Chamar")
            .setContentIntent(open)
            .addAction(callAction)
            .addAction(action)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- janela flutuante ----------

    private fun buildOverlay(): Boolean {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.gravity = Gravity.END

        val b = TextView(this)
        b.setTextColor(INK)
        b.textSize = 15f
        b.setPadding(dp(14), dp(10), dp(14), dp(10))
        b.maxWidth = dp(280)
        b.maxHeight = dp(230)
        b.movementMethod = ScrollingMovementMethod()
        b.background = box(PANEL, TEAL, 12)
        b.visibility = View.GONE
        r.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(8) })

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.visibility = View.GONE
        val et = EditText(this)
        et.hint = "Fale com a Capri"
        et.setHintTextColor(DIM)
        et.setTextColor(INK)
        et.textSize = 15f
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.imeOptions = EditorInfo.IME_ACTION_SEND
        et.background = box(PANEL, TEAL, 8)
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else {
                false
            }
        }
        row.addView(et, LinearLayout.LayoutParams(dp(200), WRAP))
        val send = TextView(this)
        send.text = "Enviar"
        send.setTextColor(ON_TEAL)
        send.textSize = 14f
        send.gravity = Gravity.CENTER
        send.setPadding(dp(12), 0, dp(12), 0)
        send.background = box(TEAL, TEAL, 8)
        send.setOnClickListener { sendTyped() }
        row.addView(send, LinearLayout.LayoutParams(WRAP, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(6)
        })
        r.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(8) })

        val fr = FrameLayout(this)
        val img = ImageView(this)
        fr.addView(img, FrameLayout.LayoutParams(dp(110), dp(140)))
        val d = View(this)
        d.background = oval(TEAL)
        val dl = FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.END)
        dl.topMargin = dp(4)
        dl.rightMargin = dp(4)
        fr.addView(d, dl)
        r.addView(fr, LinearLayout.LayoutParams(WRAP, WRAP))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.BOTTOM or Gravity.END
        p.x = if (prefs.posX >= 0) prefs.posX else dp(12)
        p.y = if (prefs.posY >= 0) prefs.posY else dp(120)

        try {
            wm.addView(r, p)
        } catch (e: Exception) {
            toast("Não consegui mostrar o boneco sobre os outros apps.")
            return false
        }

        root = r
        bubble = b
        inputRow = row
        input = et
        figure = img
        dot = d
        params = p
        attachDrag(fr)
        loadFigure()
        return true
    }

    private fun loadFigure() {
        val img = figure ?: return
        val f = File(filesDir, "capri.png")
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        if (bmp == null) {
            img.clipToOutline = false
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageResource(R.drawable.ic_chip)
            return
        }
        img.setImageBitmap(bmp)
        if (prefs.cutout) {
            img.clipToOutline = false
            img.scaleType = ImageView.ScaleType.FIT_CENTER
        } else {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            img.clipToOutline = true
        }
    }

    private fun attachDrag(v: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            toggleWake()
        }
        v.setOnTouchListener { _, e ->
            val p = params
            if (p == null) {
                false
            } else {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = p.x
                        startY = p.y
                        moved = false
                        longFired = false
                        main.postDelayed(longPress, 700)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true
                            main.removeCallbacks(longPress)
                        }
                        if (moved && !expanded) {
                            p.x = startX - dx.toInt()
                            p.y = startY - dy.toInt()
                            clampPos(p)
                            val r = root
                            if (r != null) wm.updateViewLayout(r, p)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(longPress)
                        if (moved) {
                            if (!expanded) {
                                prefs.posX = p.x
                                prefs.posY = p.y
                            }
                        } else if (!longFired) {
                            setExpanded(!expanded)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(longPress)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun clampPos(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        p.x = p.x.coerceIn(0, maxOf(0, dm.widthPixels - dp(60)))
        p.y = p.y.coerceIn(0, maxOf(0, dm.heightPixels - dp(100)))
    }

    private fun setExpanded(on: Boolean) {
        val p = params ?: return
        val r = root ?: return
        expanded = on
        inputRow?.visibility = if (on) View.VISIBLE else View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (on) {
            // sobe a janela para o teclado não cobrir o campo de texto
            savedY = p.y
            p.y = maxOf(p.y, (resources.displayMetrics.heightPixels * 0.42f).toInt())
            bubble?.maxHeight = dp(120)
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            wm.updateViewLayout(r, p)
            input?.requestFocus()
            main.postDelayed({ imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 200)
        } else {
            imm.hideSoftInputFromWindow(r.windowToken, 0)
            p.y = savedY
            bubble?.maxHeight = dp(230)
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(r, p)
            scheduleAutoHide()
        }
    }

    // ---------- aparecer e sumir ----------

    private fun setShown(on: Boolean) {
        root?.visibility = if (on) View.VISIBLE else View.GONE
        shown = on
        refreshTile()
    }

    private fun wakeUp() {
        val h = hideTask
        if (h != null) main.removeCallbacks(h)
        userHidden = false
        setShown(true)
    }

    /** Esconde o boneco na hora, sem esperar. Ela continua ouvindo "Ok Capri". */
    private fun hideNow() {
        if (expanded) setExpanded(false)
        val h = hideTask
        if (h != null) main.removeCallbacks(h)
        bubble?.visibility = View.GONE
        userHidden = true
        setShown(false)
    }

    /** Botão das configurações rápidas: aparece se estiver escondida, some se estiver à mostra. */
    private fun toggleShown() {
        if (shown) {
            hideNow()
        } else {
            wakeUp()
            say(Lines.ack(rnd), false)
        }
    }

    /** Chamada pelo botão Chamar da notificação ou pelo atalho de configurações rápidas. */
    private fun summon() {
        wakeUp()
        say("Pode falar ou digitar.", false)
        if (!expanded) setExpanded(true)
    }

    private fun scheduleAutoHide(delay: Long = 4000L) {
        val old = hideTask
        if (old != null) main.removeCallbacks(old)
        if (!prefs.autoHide || dead) return
        val t = Runnable {
            if (!expanded && (state == State.WAKE || state == State.OFF)) {
                bubble?.visibility = View.GONE
                setShown(false)
            }
        }
        hideTask = t
        main.postDelayed(t, delay)
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, CapriTile::class.java))
        } catch (e: Exception) {
            // sem atalho de configurações rápidas
        }
    }

    private fun say(text: String, sticky: Boolean) {
        val b = bubble ?: return
        b.text = text
        b.visibility = View.VISIBLE
        b.scrollTo(0, 0)
        val old = hideBubble
        if (old != null) main.removeCallbacks(old)
        if (!sticky) {
            val h = Runnable {
                if (!expanded && state != State.COMMAND && state != State.THINKING) {
                    b.visibility = View.GONE
                }
            }
            hideBubble = h
            main.postDelayed(h, 20000)
        }
    }

    private fun setDot(color: Int) {
        dot?.background = oval(color)
    }

    // ---------- escuta ----------

    private fun applyWakePref() {
        if (prefs.autoHide) scheduleAutoHide() else wakeUp()
        scheduleChatter()
        if (prefs.listenWake) {
            if (state == State.OFF) startWake()
        } else {
            stopListening()
            if (state == State.WAKE || state == State.COMMAND) {
                state = State.OFF
                setDot(DIM)
            }
        }
    }

    private fun toggleWake() {
        prefs.listenWake = !prefs.listenWake
        if (prefs.listenWake) {
            if (state == State.OFF) startWake()
            say("Escuta ligada.", false)
        } else {
            stopListening()
            if (state == State.WAKE || state == State.COMMAND) {
                state = State.OFF
                setDot(DIM)
            }
            say("Escuta desligada. Segure em mim de novo para ligar.", false)
        }
    }

    private fun startWake() {
        scheduleAutoHide()
        if (!prefs.listenWake) {
            state = State.OFF
            setDot(DIM)
            return
        }
        state = State.WAKE
        setDot(TEAL)
        listen()
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return false
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r
        return true
    }

    private fun listen() {
        if (dead) return
        if (state != State.WAKE && state != State.COMMAND) return
        if (!ensureRecognizer()) {
            say("Este celular não tem reconhecimento de voz.", true)
            state = State.OFF
            setDot(DIM)
            return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        if (state == State.WAKE) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            retryLater(1500)
        }
    }

    private fun stopListening() {
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            // ignora
        }
    }

    private fun retryLater(ms: Long) {
        main.postDelayed({
            if (state == State.WAKE) listen()
        }, ms)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            handleHeard(list ?: ArrayList<String>())
        }
    }

    private fun handleError(error: Int) {
        when (state) {
            State.WAKE -> when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    say("Preciso da permissão do microfone.", true)
                    state = State.OFF
                    setDot(DIM)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    stopListening()
                    retryLater(1500)
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> retryLater(4000)
                else -> retryLater(400)
            }
            State.COMMAND -> {
                say("Não ouvi nada. Diga \"Ok Capri\" de novo.", false)
                startWake()
            }
            else -> {}
        }
    }

    private fun handleHeard(candidates: List<String>) {
        when (state) {
            State.WAKE -> {
                for (t in candidates) {
                    val rest = afterWake(t)
                    if (rest != null) {
                        wakeUp()
                        if (rest.length >= 3) {
                            startTurn(rest, true)
                        } else {
                            beginCommand()
                        }
                        return
                    }
                }
                retryLater(300)
            }
            State.COMMAND -> {
                if (candidates.isEmpty()) {
                    say("Não ouvi nada. Diga \"Ok Capri\" de novo.", false)
                    startWake()
                } else {
                    startTurn(candidates[0], true)
                }
            }
            else -> {}
        }
    }

    /** Se o texto tem "capri" logo no começo, devolve o que veio depois. Senão, null. */
    private fun afterWake(raw: String): String? {
        val parts = raw.trim().split(Regex("\\s+"))
        var idx = -1
        for (i in parts.indices) {
            if (plain(parts[i]) in WAKE_WORDS) {
                idx = i
                break
            }
        }
        if (idx < 0 || idx > 3) return null
        return parts.drop(idx + 1).joinToString(" ").trim()
    }

    private fun plain(s: String): String {
        val lower = s.lowercase(Locale("pt", "BR"))
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("[^a-z]"), "")
    }

    private fun beginCommand() {
        stopListening()
        state = State.COMMAND
        setDot(HOT)
        say(Lines.ack(rnd), true)
        beep()
        main.postDelayed({ listen() }, 350)
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            main.postDelayed({ tg.release() }, 500)
        } catch (e: Exception) {
            // sem som
        }
    }

    // ---------- conversa ----------

    private fun sendTyped() {
        val et = input ?: return
        val t = et.text.toString().trim()
        if (t.isEmpty() || state == State.THINKING) return
        et.setText("")
        startTurn(t, false)
    }

    private fun startTurn(text: String, voice: Boolean) {
        stopListening()
        tts?.stop()
        val q = text.trim()
        if (q.isEmpty()) {
            startWake()
            return
        }
        voiceTurn = voice
        val cmd = Commands.parse(q)
        if (cmd != null && cmd.kind == "HIDE") {
            hideNow()
            val line = Lines.hide(rnd)
            if (prefs.speak && ttsReady) speak(line) else startWake()
            return
        }
        if (cmd != null) {
            val said = Actions(this).run(cmd)
            finishTurn(said, true)
            return
        }
        state = State.THINKING
        setDot(AMBER)
        say("Você: " + q + "\n…", true)
        history.add(Pair("user", q))
        trimHistory()

        val key = prefs.apiKey
        if (key.isEmpty()) {
            onReply(false, "Cole sua chave da API no app da Capri para eu poder conversar.")
            return
        }
        val model = prefs.model
        val system = Persona.system()
        val snapshot = ArrayList<Pair<String, String>>(history)
        io.execute {
            val res: Pair<Boolean, String> = try {
                Pair(true, Ai.ask(key, model, system, snapshot))
            } catch (e: ClaudeException) {
                Pair(false, errorText(e))
            } catch (e: IOException) {
                Pair(false, "Sem conexão com a internet (" + e.javaClass.simpleName + ").")
            } catch (e: Exception) {
                Pair(false, "Erro: " + e.javaClass.simpleName + " " + (e.message ?: "").take(120))
            }
            main.post { onReply(res.first, res.second) }
        }
    }

    private fun errorText(e: ClaudeException): String {
        val m = e.message ?: ""
        if (m.contains("credit balance", ignoreCase = true)) {
            return "Sua conta da API está sem crédito. Adicione crédito em console.anthropic.com, em Plans & Billing."
        }
        if (m.contains("API key not valid", ignoreCase = true) || m.contains("API_KEY_INVALID", ignoreCase = true)) {
            return "A chave da API foi recusada. Confira no app da Capri."
        }
        if (m.contains("location is not supported", ignoreCase = true)) {
            return "O Google não libera a API grátis nesta região."
        }
        return when (e.code) {
            "http_401" -> "A chave da API foi recusada. Confira no app da Capri."
            "http_403" -> "Essa chave não tem permissão para usar este modelo."
            "http_404" -> "Modelo não encontrado. Ajuste o modelo no app da Capri."
            "http_429" -> "Limite grátis atingido por agora. Espere um minuto ou tente amanhã."
            "http_529", "http_503" -> "O serviço está sobrecarregado. Tente de novo em instantes."
            "empty" -> "A resposta veio vazia. Tente de novo."
            else -> "Erro " + e.code.removePrefix("http_") + ": " + m.take(160)
        }
    }

    private fun onReply(ok: Boolean, text: String) {
        if (dead) return
        if (!ok) {
            if (history.isNotEmpty() && history[history.size - 1].first == "user") {
                history.removeAt(history.size - 1)
            }
        } else {
            history.add(Pair("assistant", text))
            trimHistory()
            prefs.saveHistory(history)
        }
        var out = text
        if (ok) {
            val parts = Commands.extractTags(text)
            out = if (parts.first.isEmpty()) "Pronto." else parts.first
            for (a in parts.second.take(2)) {
                val r = Actions(this).run(a)
                if (Actions.failed(r)) out = out + " " + r
            }
        }
        finishTurn(out, ok)
    }

    private fun finishTurn(text: String, speakIt: Boolean) {
        say(text, false)
        if (speakIt && prefs.speak && ttsReady) {
            speak(text)
        } else {
            startWake()
            scheduleAutoHide((4000L + text.length * 45L).coerceAtMost(15000L))
        }
    }

    // ---------- falas soltas ----------

    private fun scheduleChatter() {
        val old = chatTask
        if (old != null) main.removeCallbacks(old)
        if (!prefs.chatter || dead) return
        val t = Runnable { chatterTick() }
        chatTask = t
        main.postDelayed(t, 240000L + rnd.nextInt(300000))
    }

    /** De tempos em tempos ela solta uma frase, se estiver quieta e o celular não estiver ocupado. */
    private fun chatterTick() {
        if (dead) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val busy = am.isMusicActive || am.mode != AudioManager.MODE_NORMAL
        val idle = (state == State.WAKE || state == State.OFF) && !expanded
        if (prefs.chatter && idle && !busy && !userHidden) {
            wakeUp()
            stopListening()
            voiceTurn = true
            finishTurn(Lines.idle(rnd, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)), true)
        }
        scheduleChatter()
    }

    private fun trimHistory() {
        while (history.size > 16) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
    }

    // ---------- voz de resposta ----------

    private fun initTts() {
        engineInUse = prefs.ttsEngine
        val engine: String? = if (engineInUse.isEmpty()) null else engineInUse
        tts = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS && engine != null) {
                // o mecanismo escolhido não abriu: volta ao padrão do celular
                prefs.ttsEngine = ""
                main.post { initTts() }
            } else if (status == TextToSpeech.SUCCESS) {
                val t = tts
                if (t != null) {
                    t.setLanguage(Locale("pt", "BR"))
                    applyVoice(t)
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            main.post { afterSpeak() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            main.post { afterSpeak() }
                        }
                    })
                    ttsReady = true
                }
            }
        }, engine)
    }

    private fun applyVoice(t: TextToSpeech) {
        t.setPitch(prefs.pitch)
        t.setSpeechRate(prefs.rate)
        val name = prefs.voiceName
        if (name.isNotEmpty()) {
            val list: Set<android.speech.tts.Voice>? = try {
                t.voices
            } catch (e: Exception) {
                null
            }
            val v = list?.firstOrNull { it.name == name }
            if (v != null) t.setVoice(v)
        }
    }

    private fun speak(text: String) {
        state = State.SPEAKING
        setDot(HOT)
        val clean = text.replace(Regex("[*_`#>]+"), " ")
        val t = tts
        if (t == null) {
            afterSpeak()
            return
        }
        t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "capri")
    }

    private fun afterSpeak() {
        if (state == State.SPEAKING) startWake()
    }

    // ---------- utilidades ----------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.setStroke(dp(1), stroke)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    companion object {
        @Volatile
        var running = false

        const val ACTION_STOP = "app.capri.STOP"
        const val ACTION_RELOAD = "app.capri.RELOAD"
        const val ACTION_PREFS = "app.capri.PREFS"
        const val ACTION_SHOW = "app.capri.SHOW"
        const val ACTION_TOGGLE = "app.capri.TOGGLE"

        /** True quando o boneco está à mostra na tela. */
        @Volatile
        var shown = false

        private const val CHANNEL = "capri"
        private const val NOTIF_ID = 1
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val WAKE_WORDS = setOf("capri", "kapri", "capre", "kapre", "cabri", "capry")

        private val PANEL = 0xFF141229.toInt()
        private val INK = 0xFFEBE9FF.toInt()
        private val DIM = 0xFF9692C8.toInt()
        private val TEAL = 0xFF46F0D4.toInt()
        private val ON_TEAL = 0xFF04211C.toInt()
        private val HOT = 0xFFFF4F9D.toInt()
        private val AMBER = 0xFFFFC145.toInt()
    }
}
