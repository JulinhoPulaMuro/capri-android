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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import android.os.SystemClock
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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
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

    // três janelas: a figura, o balão de fala dela e a caixa de escrever
    private var charRoot: FrameLayout? = null
    private var charParams: WindowManager.LayoutParams? = null
    private var charAttached = false
    private var figure: BodyView? = null
    private var dot: View? = null
    private var headIsFallback = false
    private var bubbleRoot: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleText: TextView? = null
    private var bubbleTail: TailView? = null
    private var bubbleAttached = false
    private var bubbleDirty = true
    private var bubbleW = 0
    private var bubbleH = 0
    private var hideBubble: Runnable? = null
    private var inputRoot: LinearLayout? = null
    private var inputParams: WindowManager.LayoutParams? = null
    private var input: EditText? = null
    private var inputAdded = false
    private var inputW = 0
    private var inputH = 0
    private var expanded = false
    private var hideTask: Runnable? = null

    // posição e rotina dela (pixels da tela, canto de cima e da esquerda da janela da figura)
    private var figW = 0
    private var figH = 0
    private var curX = 0f
    private var curY = 0f
    private var env: Env? = null
    private var envDirty = true
    private var envW = 0
    private var envH = 0
    private val acts = ArrayList<Choreo.Act>()
    private var actIdx = 0
    private var actStart = 0L
    private var lastTick = 0L
    private var lastDtMs = 16L
    private var dragging = false
    private var touching = false
    private val tmp2 = FloatArray(2)
    private val tmpA = FloatArray(2)
    private val tmpB = FloatArray(2)
    private var teleAct: Choreo.Act? = null
    private var ghost: GhostView? = null
    private val planner = Choreo.Planner(java.util.Random())
    private var dead = false
    private var engineInUse = ""
    private val rnd = java.util.Random()
    private var chatTask: Runnable? = null
    private var userHidden = false

    private var state = State.OFF
    private var voiceTurn = false
    private var lastTyped = ""
    private var turnId = 0
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
        if (prefs.moves) queueGesture(Move.WAVE, 2200L)
        lastTick = SystemClock.uptimeMillis()
        main.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RELOAD -> {
                loadFigure()
                resetActs()
            }
            ACTION_PREFS -> {
                applyBody()
                resetActs()
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
        removeGhost()
        for (v in listOf<View?>(charRoot, bubbleRoot, if (inputAdded) inputRoot else null)) {
            if (v != null) {
                try {
                    wm.removeView(v)
                } catch (e: Exception) {
                    // ignora
                }
            }
        }
        charRoot = null
        bubbleRoot = null
        inputRoot = null
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

    // ---------- janelas flutuantes ----------
    // Três janelas separadas: a figura (que anda, voa e senta), o balão de fala dela
    // (preso em cima da cabeça) e a caixa de escrever (solta, que a pessoa arrasta).

    private val tickRunnable = Runnable { tick() }

    @Suppress("DEPRECATION")
    private fun screenSize(): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return intArrayOf(b.width(), b.height())
        }
        val dm = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    private fun dpf(n: Int): Float = n * resources.displayMetrics.density

    private fun overlayParams(focusable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 0
        p.y = 0
        return p
    }

    private fun buildOverlay(): Boolean {
        // 1) a figura
        val fr = FrameLayout(this)
        val img = BodyView(this)
        fr.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = View(this)
        d.background = oval(TEAL)
        val dl = FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.END)
        dl.topMargin = dp(4)
        dl.rightMargin = dp(4)
        fr.addView(d, dl)

        // 2) o balão de fala dela, com a pontinha para a cabeça
        val br = LinearLayout(this)
        br.orientation = LinearLayout.VERTICAL
        br.gravity = Gravity.CENTER_HORIZONTAL
        val b = TextView(this)
        b.setTextColor(INK)
        b.textSize = 15f
        b.setPadding(dp(14), dp(10), dp(14), dp(10))
        b.maxWidth = dp(280)
        b.maxHeight = dp(200)
        b.movementMethod = ScrollingMovementMethod()
        b.background = box(PANEL, TEAL, 12)
        b.setOnClickListener { hideBubbleNow() }
        br.addView(b, LinearLayout.LayoutParams(WRAP, WRAP))
        val tl = TailView(this)
        br.addView(tl, LinearLayout.LayoutParams(dp(18), dp(10)).apply { topMargin = -dp(1) })
        br.visibility = View.GONE

        // 3) a caixa onde a pessoa escreve: outra janela, que se arrasta pela alça
        val ir = LinearLayout(this)
        ir.orientation = LinearLayout.VERTICAL
        ir.background = box(PANEL, TEAL, 14)
        ir.setPadding(dp(8), dp(2), dp(8), dp(8))
        val grip = LinearLayout(this)
        grip.orientation = LinearLayout.HORIZONTAL
        grip.gravity = Gravity.CENTER_VERTICAL
        val gt = TextView(this)
        gt.text = "≡  arraste aqui para mover"
        gt.setTextColor(DIM)
        gt.textSize = 12f
        gt.setPadding(dp(4), dp(10), dp(4), dp(8))
        grip.addView(gt, LinearLayout.LayoutParams(0, WRAP, 1f))
        val close = TextView(this)
        close.text = "Fechar"
        close.setTextColor(DIM)
        close.textSize = 12f
        close.setPadding(dp(12), dp(10), dp(4), dp(8))
        close.setOnClickListener { closeInput() }
        grip.addView(close, LinearLayout.LayoutParams(WRAP, WRAP))
        ir.addView(grip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
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
        row.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
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
        ir.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP))

        val cp = overlayParams(false)
        val bp = overlayParams(false)
        val ip = overlayParams(true)
        ip.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING

        charRoot = fr
        charParams = cp
        bubbleRoot = br
        bubbleText = b
        bubbleTail = tl
        bubbleParams = bp
        inputRoot = ir
        inputParams = ip
        input = et
        figure = img
        img.autoGlitch = prefs.glitch
        dot = d

        loadFigure()

        // posição inicial: a última onde a pessoa deixou, ou o canto de baixo à direita
        val s = screenSize()
        val env = ensureEnv()
        if (prefs.charX >= 0 && prefs.charY >= 0) {
            curX = prefs.charX.toFloat()
            curY = prefs.charY.toFloat()
        } else {
            val ox = if (prefs.posX >= 0) prefs.posX else dp(12)
            val oy = if (prefs.posY >= 0) prefs.posY else dp(120)
            curX = (s[0] - figW - ox).toFloat()
            curY = (s[1] - figH - oy).toFloat()
        }
        curX = env.clampX(curX)
        curY = env.clampY(curY)
        cp.x = curX.toInt()
        cp.y = curY.toInt()

        try {
            wm.addView(fr, cp)
            charAttached = true
            wm.addView(br, bp)
            bubbleAttached = true
        } catch (e: Exception) {
            toast("Não consegui mostrar o boneco sobre os outros apps.")
            return false
        }
        attachCharTouch(fr)
        attachInputDrag(grip)
        return true
    }

    private fun loadFigure() {
        val img = figure ?: return
        val f = File(filesDir, "capri.png")
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        headIsFallback = bmp == null
        if (bmp == null) {
            img.setHead(null, resources.getDrawable(R.drawable.ic_chip, theme))
        } else {
            img.setHead(bmp, null)
        }
        val bf = File(filesDir, "capri_body.png")
        img.setSprite(if (bf.exists()) BitmapFactory.decodeFile(bf.absolutePath) else null)
        img.loadRigFiles(prefs)
        applyBody()
    }

    /** Aplica o corpo escolhido e ajusta o tamanho da janela da figura. */
    private fun applyBody() {
        val f = figure ?: return
        f.applyPrefs(prefs)
        f.cutout = prefs.cutout || headIsFallback
        val box = f.boxDp()
        val k = prefs.figureScale.coerceIn(0.6f, 1.6f)
        figW = dp((box.first * k).toInt())
        figH = dp((box.second * k).toInt())
        val cp = charParams
        val cr = charRoot
        if (cp != null) {
            cp.width = figW
            cp.height = figH
            if (charAttached && cr != null) {
                try {
                    wm.updateViewLayout(cr, cp)
                } catch (e: Exception) {
                    // janela já removida
                }
            }
        }
        envDirty = true
        f.invalidate()
    }

    // ---------- o que ela faz sozinha ----------

    /** Medidas da tela e da figura (e da caixa de escrever, se estiver aberta). */
    private fun ensureEnv(): Env {
        val sz = screenSize()
        val old = env
        if (old != null && !envDirty && sz[0] == envW && sz[1] == envH) return old
        envW = sz[0]
        envH = sz[1]
        val f = figure
        val fh = figH.toFloat()
        val ne = Env(
            sz[0].toFloat(), sz[1].toFloat(), dpf(28), (sz[1] - dp(56)).toFloat(),
            figW.toFloat(), fh,
            (f?.feetInsetFrac() ?: 0.02f) * fh,
            (f?.pelvisFrac() ?: 0.42f) * fh,
            resources.displayMetrics.density
        )
        env = ne
        envDirty = false
        if (inputAdded) syncInputRect()
        return ne
    }

    private fun syncInputRect() {
        val ip = inputParams ?: return
        val e = env ?: return
        e.setInput(ip.x.toFloat(), ip.y.toFloat(), (ip.x + inputW).toFloat(), (ip.y + inputH).toFloat())
    }

    private fun overridePose(): Move? = when (state) {
        State.THINKING -> Move.THINK
        State.COMMAND -> Move.LISTEN
        State.SPEAKING -> Move.TALK
        else -> null
    }

    private fun resetActs() {
        acts.clear()
        actIdx = 0
    }

    /** Faz um gesto agora, no lugar onde ela está, e depois volta à rotina. */
    private fun queueGesture(m: Move, ms: Long) {
        resetActs()
        val a = Anchor.Abs(curX, curY)
        acts.add(Choreo.Act(m, ms, a, a))
        actStart = SystemClock.uptimeMillis()
    }

    private fun planNext(env: Env) {
        resetActs()
        val had = env.hasInput
        if (!prefs.wander) env.hasInput = false
        try {
            figure?.autoGlitch = prefs.glitch
            acts.addAll(planner.plan(env, curX, curY, prefs.moves, prefs.wander, prefs.glitch))
        } finally {
            env.hasInput = had
        }
        if (acts.isEmpty()) {
            val a = Anchor.Abs(curX, curY)
            acts.add(Choreo.Act(Move.IDLE, 1500L, a, a))
        }
    }

    private fun runChoreo(now: Long, env: Env) {
        val f = figure ?: return
        if (actIdx >= acts.size) {
            planNext(env)
            actStart = now
        }
        val a = acts[actIdx]
        val k = (now - actStart).toFloat() / maxOf(1L, a.durMs).toFloat()
        val px = curX
        if (k >= 1f) {
            a.pos(1f, env, tmp2)
            curX = tmp2[0]
            curY = tmp2[1]
            actIdx++
            actStart = now
            f.glitch = 0f
        } else {
            val want = if (a.teleport) {
                if (k > 0.08f && k < 0.62f) Move.GLITCH else Move.IDLE
            } else {
                a.move
            }
            if (f.currentPose() != want) f.setPose(want)
            a.pos(k, env, tmp2)
            curX = tmp2[0]
            curY = tmp2[1]
            if (a.teleport) {
                // a força do glitch sobe até o salto e depois vai apagando
                val j = Choreo.TELE_K
                f.glitch = if (k < j) 0.2f + 0.8f * (k / j) * (k / j) else 1f - 0.9f * ((k - j) / (1f - j))
                if (teleAct !== a && k >= j) {
                    teleAct = a
                    a.pos(0f, env, tmpA)
                    a.pos(1f, env, tmpB)
                    spawnGhost(tmpA[0], tmpA[1], tmpB[0], tmpB[1])
                }
            } else {
                f.glitch = 0f
            }
        }
        // no teleporte ela não anda: o salto não conta como velocidade
        val vx = if (a.teleport) 0f else curX - px
        f.kick(vx.coerceIn(-8f, 8f))
        // velocidade em pixels por segundo: inclina para o lado em que ela vai (voando mais, andando menos)
        // e faz o passo acompanhar o chão
        val vps = vx * 1000f / maxOf(8L, lastDtMs).toFloat()
        val leanK = if (a.move == Move.FLY) 0.0004f else 0.0002f
        f.lean = (vps * leanK).coerceIn(-0.22f, 0.22f)
        f.speed = if (a.move == Move.WALK) Math.abs(vps) else -1f
    }

    // ---------- rastro do teleporte ----------

    private fun spawnGhost(ax: Float, ay: Float, bx: Float, by: Float) {
        val f = figure ?: return
        val bmp = f.snapshot() ?: return
        removeGhost()
        val left = minOf(ax, bx).toInt()
        val top = minOf(ay, by).toInt()
        val gw = Math.abs(bx - ax).toInt() + bmp.width + 2
        val gh = Math.abs(by - ay).toInt() + bmp.height + 2
        val p = WindowManager.LayoutParams(
            gw, gh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = left
        p.y = top
        val g = GhostView(this, bmp, ax - left, ay - top, bx - left, by - top)
        g.onDone = { removeGhost() }
        try {
            wm.addView(g, p)
            ghost = g
        } catch (e: Exception) {
            ghost = null
        }
    }

    private fun removeGhost() {
        val g = ghost ?: return
        ghost = null
        try {
            wm.removeView(g)
        } catch (e: Exception) {
            // já removido
        }
    }

    /** Se ela estava sentada na caixa e vai falar ou pensar, levanta antes. */
    private fun settleForOverride(env: Env) {
        if (actIdx < acts.size && acts[actIdx].move == Move.SIT && env.hasInput) {
            curY = env.clampY(env.inT + env.feetInset - env.figH)
        }
    }

    private fun tick() {
        if (dead) return
        val now = SystemClock.uptimeMillis()
        val dtMs = now - lastTick
        lastTick = now
        lastDtMs = dtMs
        val cr = charRoot
        val visible = cr != null && cr.visibility == View.VISIBLE && charAttached
        if (visible) {
            val env = ensureEnv()
            val ov = overridePose()
            val f = figure
            if (dragging || touching) {
                f?.lean = 0f
                f?.glitch = 0f
                actStart += dtMs
            } else if (ov != null) {
                if (acts.isNotEmpty()) {
                    settleForOverride(env)
                    resetActs()
                }
                if (f != null && f.currentPose() != ov) f.setPose(ov)
                f?.lean = 0f
                f?.glitch = 0f
            } else {
                runChoreo(now, env)
            }
            applyCharPos()
            positionBubble()
        }
        main.postDelayed(tickRunnable, if (visible) 16L else 250L)
    }

    private fun applyCharPos() {
        val cr = charRoot ?: return
        val cp = charParams ?: return
        val xi = curX.toInt()
        val yi = curY.toInt()
        // o que a janela não mostra (menos de 1 pixel) a figura desenha por dentro: movimento mais liso
        val fg = figure
        if (fg != null) {
            fg.subX = curX - xi
            fg.subY = curY - yi
        }
        if (xi != cp.x || yi != cp.y) {
            cp.x = xi
            cp.y = yi
            try {
                wm.updateViewLayout(cr, cp)
            } catch (e: Exception) {
                // janela já removida
            }
        }
    }

    // ---------- o balão de fala: fica em cima da cabeça dela ----------

    private fun showBubble(text: String) {
        val bt = bubbleText ?: return
        val br = bubbleRoot ?: return
        bt.text = text
        bt.scrollTo(0, 0)
        val charVisible = charRoot?.visibility == View.VISIBLE
        br.visibility = if (charVisible) View.VISIBLE else View.GONE
        bubbleDirty = true
        positionBubble()
    }

    private fun hideBubbleNow() {
        bubbleRoot?.visibility = View.GONE
    }

    private fun bubbleVisible(): Boolean = bubbleRoot?.visibility == View.VISIBLE

    private fun positionBubble() {
        val br = bubbleRoot ?: return
        val bp = bubbleParams ?: return
        val bt = bubbleText ?: return
        if (!bubbleAttached || br.visibility != View.VISIBLE) return
        val s = screenSize()
        if (bubbleDirty) {
            val maxW = minOf(dp(300), s[0] - dp(16))
            bt.maxWidth = maxW
            br.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(s[1], View.MeasureSpec.AT_MOST)
            )
            bubbleW = br.measuredWidth
            bubbleH = br.measuredHeight
            bubbleDirty = false
        }
        val bw = bubbleW.toFloat()
        val bh = bubbleH.toFloat()
        val m = dpf(4)
        val minTop = dpf(26)
        val headTop = curY + figH * (figure?.headTopFrac() ?: 0.05f)
        val cx = curX + figW / 2f
        var x = (cx - bw / 2f).coerceIn(m, maxOf(m, s[0] - bw - m))
        var y = headTop - bh
        var above = true
        if (y < minTop) {
            // sem espaço em cima da cabeça: o balão vai para o lado dela
            above = false
            val right = curX + figW + dpf(2)
            x = if (right + bw <= s[0] - m) right else curX - bw - dpf(2)
            x = x.coerceIn(m, maxOf(m, s[0] - bw - m))
            y = headTop
        }
        y = y.coerceIn(minTop, maxOf(minTop, s[1] - bh - m))
        val tl = bubbleTail
        if (tl != null) {
            val want = if (above) View.VISIBLE else View.GONE
            if (tl.visibility != want) {
                tl.visibility = want
                bubbleDirty = true
            }
            if (above) {
                val lim = maxOf(0f, bw / 2f - dpf(16))
                tl.translationX = (cx - (x + bw / 2f)).coerceIn(-lim, lim)
            }
        }
        val xi = x.toInt()
        val yi = y.toInt()
        if (xi != bp.x || yi != bp.y) {
            bp.x = xi
            bp.y = yi
            try {
                wm.updateViewLayout(br, bp)
            } catch (e: Exception) {
                // janela já removida
            }
        }
    }

    // ---------- tocar e arrastar ----------

    private fun attachCharTouch(v: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var moved = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            toggleWake()
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    downX = e.rawX
                    downY = e.rawY
                    startX = curX
                    startY = curY
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
                        dragging = true
                        main.removeCallbacks(longPress)
                        figure?.setPose(Move.FLY)
                    }
                    if (moved) {
                        val env = ensureEnv()
                        curX = env.clampX(startX + dx)
                        curY = env.clampY(startY + dy)
                        applyCharPos()
                        positionBubble()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    touching = false
                    if (moved) {
                        dragging = false
                        prefs.charX = curX.toInt()
                        prefs.charY = curY.toInt()
                        resetActs()
                    } else if (!longFired) {
                        setExpanded(!expanded)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    touching = false
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun clampInput(x: Int, y: Int, s: IntArray): IntArray {
        val minY = dp(28)
        val maxY = maxOf(minY, (s[1] * 0.5f).toInt() - inputH)
        return intArrayOf(x.coerceIn(0, maxOf(0, s[0] - inputW)), y.coerceIn(minY, maxY))
    }

    private fun attachInputDrag(v: View) {
        var downX = 0f
        var downY = 0f
        var sx = 0
        var sy = 0
        v.setOnTouchListener { _, e ->
            val ip = inputParams
            if (ip == null) {
                false
            } else {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        sx = ip.x
                        sy = ip.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val p = clampInput(sx + (e.rawX - downX).toInt(), sy + (e.rawY - downY).toInt(), screenSize())
                        ip.x = p[0]
                        ip.y = p[1]
                        val ir = inputRoot
                        if (ir != null && inputAdded) {
                            try {
                                wm.updateViewLayout(ir, ip)
                            } catch (ex: Exception) {
                                // janela já removida
                            }
                        }
                        syncInputRect()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.inputX = ip.x
                        prefs.inputY = ip.y
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /** A figura e o balão ficam por cima da caixa de escrever: assim ela senta e anda na frente dela. */
    private fun bringFigureToFront() {
        val cr = charRoot
        val cp = charParams
        if (cr != null && cp != null && charAttached) {
            try {
                wm.removeViewImmediate(cr)
                wm.addView(cr, cp)
            } catch (e: Exception) {
                try {
                    wm.addView(cr, cp)
                } catch (e2: Exception) {
                    charAttached = false
                }
            }
        }
        val br = bubbleRoot
        val bp = bubbleParams
        if (br != null && bp != null && bubbleAttached) {
            try {
                wm.removeViewImmediate(br)
                wm.addView(br, bp)
            } catch (e: Exception) {
                try {
                    wm.addView(br, bp)
                } catch (e2: Exception) {
                    bubbleAttached = false
                }
            }
        }
    }

    private fun closeInput() {
        if (!expanded) return
        setExpanded(false)
        if (prefs.moves) queueGesture(Move.WAVE, 2400L)
    }

    private fun setExpanded(on: Boolean) {
        if (on == expanded) return
        val ir = inputRoot ?: return
        val ip = inputParams ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (on) {
            val s = screenSize()
            inputW = minOf(dp(300), s[0] - dp(16))
            ip.width = inputW
            ir.measure(
                View.MeasureSpec.makeMeasureSpec(inputW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(s[1], View.MeasureSpec.AT_MOST)
            )
            inputH = ir.measuredHeight
            var x = prefs.inputX
            var y = prefs.inputY
            if (x < 0 || y < 0) {
                x = (s[0] - inputW) / 2
                y = (s[1] * 0.30f).toInt()
            }
            val p = clampInput(x, y, s)
            ip.x = p[0]
            ip.y = p[1]
            try {
                wm.addView(ir, ip)
            } catch (e: Exception) {
                toast("Não consegui abrir a caixa de escrever.")
                return
            }
            expanded = true
            inputAdded = true
            bringFigureToFront()
            bubbleText?.maxHeight = dp(120)
            bubbleDirty = true
            envDirty = true
            resetActs()
            input?.requestFocus()
            main.postDelayed({ imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 200)
        } else {
            imm.hideSoftInputFromWindow(ir.windowToken, 0)
            try {
                wm.removeView(ir)
            } catch (e: Exception) {
                // já removida
            }
            expanded = false
            inputAdded = false
            env?.clearInput()
            bubbleText?.maxHeight = dp(200)
            bubbleDirty = true
            envDirty = true
            resetActs()
            scheduleAutoHide()
        }
    }

    // ---------- aparecer e sumir ----------

    private fun setShown(on: Boolean) {
        charRoot?.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) {
            hideBubbleNow()
            removeGhost()
        }
        shown = on
        refreshTile()
    }

    private fun wakeUp() {
        val h = hideTask
        if (h != null) main.removeCallbacks(h)
        userHidden = false
        val wasHidden = !shown
        setShown(true)
        if (wasHidden && prefs.glitch) figure?.glitchBurst(0.85f, 650L)
        if (wasHidden && prefs.moves) queueGesture(Move.WAVE, 1800L)
    }

    /** Esconde o boneco na hora, sem esperar. Ela continua ouvindo "Ok Capri". */
    private fun hideNow() {
        if (expanded) setExpanded(false)
        val h = hideTask
        if (h != null) main.removeCallbacks(h)
        hideBubbleNow()
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
                hideBubbleNow()
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
        if (bubbleText == null) return
        showBubble(text)
        val old = hideBubble
        if (old != null) main.removeCallbacks(old)
        if (!sticky) {
            val h = Runnable {
                if (!expanded && state != State.COMMAND && state != State.THINKING) hideBubbleNow()
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
        lastTyped = t
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
            if (expanded && NEEDS_FOCUS.contains(cmd.kind)) {
                // devolve o foco ao app de baixo antes de mexer nele
                setExpanded(false)
                state = State.THINKING
                setDot(AMBER)
                main.postDelayed({
                    if (!dead) finishTurn(Actions(this).run(cmd), true)
                }, 450)
            } else {
                finishTurn(Actions(this).run(cmd), true)
            }
            return
        }
        state = State.THINKING
        setDot(AMBER)
        val shown = if (q.length > 160) q.take(160) + "..." else q
        say("Você: " + shown + "\n…", true)
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
        val myTurn = ++turnId
        io.execute {
            val res: Pair<Boolean, String> = try {
                Pair(true, Ai.ask(key, model, system, snapshot) { msg ->
                    main.post {
                        if (!dead && myTurn == turnId && state == State.THINKING) say(msg, true)
                    }
                })
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
            "http_429" -> "Limite de uso atingido por agora. Espere um minuto e mande de novo."
            "http_529", "http_503", "http_500", "http_502", "http_504" ->
                "Tentei algumas vezes e o servidor da IA continua ocupado. Sua mensagem voltou para a caixa: é só enviar de novo daqui a pouco."
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
            // devolve o que foi digitado para a caixa, para não precisar escrever tudo de novo
            val et = input
            if (et != null && expanded && lastTyped.isNotEmpty() && et.text.isEmpty()) {
                et.setText(lastTyped)
                et.setSelection(et.text.length)
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
            val acts = parts.second.take(3)
            if (expanded && acts.any { NEEDS_FOCUS.contains(it.kind) }) {
                setExpanded(false)
                val base = out
                main.postDelayed({
                    if (!dead) finishTurn(runAll(acts, base), true)
                }, 450)
                return
            }
            out = runAll(acts, out)
        }
        finishTurn(out, ok)
    }

    private fun runAll(acts: List<Action>, base: String): String {
        var out = base
        for (a in acts) {
            val r = Actions(this).run(a)
            if (Actions.failed(r)) out = out + " " + r
        }
        return out
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
        // mensagens muito longas ficam só com o começo, para o pedido não pesar
        for (i in 0 until history.size - 1) {
            val m = history[i]
            if (m.second.length > 1200) history[i] = Pair(m.first, m.second.take(1200) + "...")
        }
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

        /** Ações que mexem em outro app e precisam que o foco saia da caixa de texto da Capri. */
        private val NEEDS_FOCUS = setOf("CLICK", "TYPE", "SCROLL", "GLOBAL")

        private val PANEL = 0xFF141229.toInt()
        private val INK = 0xFFEBE9FF.toInt()
        private val DIM = 0xFF9692C8.toInt()
        private val TEAL = 0xFF46F0D4.toInt()
        private val ON_TEAL = 0xFF04211C.toInt()
        private val HOT = 0xFFFF4F9D.toInt()
        private val AMBER = 0xFFFFC145.toInt()
    }
}

/** A pontinha do balão de fala, que aponta para a cabeça dela. */
class TailView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF141229.toInt()
    }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFF46F0D4.toInt()
    }
    private val edge = Path()
    private val area = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        edge.reset()
        edge.moveTo(0f, 0f)
        edge.lineTo(w / 2f, h)
        edge.lineTo(w, 0f)
        area.reset()
        area.addPath(edge)
        area.close()
        canvas.drawPath(area, fillP)
        canvas.drawPath(edge, lineP)
    }
}
