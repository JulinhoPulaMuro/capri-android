package app.capri

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/** Tela de preparo: permissões, chave da API, imagem da Capri e liga/desliga. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var grantBtn: Button
    private lateinit var toggleBtn: Button
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var preview: ImageView
    private lateinit var batteryStatus: TextView
    private lateinit var batteryBtn: Button
    private lateinit var voiceSpinner: Spinner
    private lateinit var engineSpinner: Spinner
    private lateinit var voiceHint: TextView
    private lateinit var markBtn: Button
    private lateinit var bodyPreview: BodyView
    private lateinit var extrasStatus: TextView
    private lateinit var a11yBtn: Button
    private lateinit var contactsBtn: Button
    private val swatches = ArrayList<Pair<View, Int>>()
    private lateinit var bodySpinner: Spinner
    private var poseIdx = 0
    private var rigChain = false
    private lateinit var rigStatus: TextView
    private var tts: TextToSpeech? = null
    private var voices: List<Voice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        startTts()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onPause() {
        saveFields()
        super.onPause()
    }

    private fun buildUi(): View {
        val pad = dp(20)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad + dp(16), pad, pad + dp(24))

        val title = TextView(this)
        title.text = "CAPRI CHIP"
        title.setTextColor(TEAL)
        title.textSize = 24f
        title.setTypeface(null, Typeface.BOLD)
        title.letterSpacing = 0.15f
        col.addView(title)

        col.addView(body("Assistente de voz que flutua sobre os outros apps. Projeto de fã, não oficial, inspirado na Capri de Cyberpunk: Edgerunners 2."))

        col.addView(heading("1. Permissões"))
        status = body("")
        col.addView(status)
        grantBtn = button("Conceder permissões") { nextPermission() }
        col.addView(grantBtn)

        col.addView(heading("2. Chave da API"))
        keyField = EditText(this)
        keyField.hint = "AIza... (Google, grátis)"
        keyField.setHintTextColor(DIM)
        keyField.setTextColor(INK)
        keyField.setSingleLine(true)
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyField.setText(prefs.apiKey)
        col.addView(keyField)
        col.addView(body("Chave grátis do Google: abra aistudio.google.com no navegador, entre com sua conta Google, toque em Get API key e copie a chave (começa com AIza). Não pede cartão. A chave fica guardada só neste celular e as mensagens vão direto dele para o Google. No plano grátis o Google pode usar as conversas para melhorar os produtos dele, então evite assuntos privados. Chaves da Anthropic (sk-ant-) também funcionam, mas são pagas."))

        col.addView(heading("Modelo (opcional)"))
        modelField = EditText(this)
        modelField.setTextColor(INK)
        modelField.hint = "Deixe em branco para o padrão"
        modelField.setHintTextColor(DIM)
        modelField.setSingleLine(true)
        modelField.setText(prefs.model)
        col.addView(modelField)

        col.addView(heading("3. Imagem da Capri"))
        preview = ImageView(this)
        preview.adjustViewBounds = true
        preview.setBackgroundColor(PANEL)
        preview.setPadding(dp(8), dp(8), dp(8), dp(8))
        col.addView(preview, LinearLayout.LayoutParams(dp(160), dp(200)))
        col.addView(button("Escolher imagem") { pickImage() })

        addBodySection(col)

        col.addView(heading("4. Opções"))
        val wake = Switch(this)
        wake.text = "Escutar \"Ok Capri\""
        wake.setTextColor(INK)
        wake.isChecked = prefs.listenWake
        wake.setOnCheckedChangeListener { _, checked ->
            prefs.listenWake = checked
            if (OverlayService.running) {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_PREFS))
            }
        }
        col.addView(wake)
        val speak = Switch(this)
        speak.text = "Responder em voz alta"
        speak.setTextColor(INK)
        speak.isChecked = prefs.speak
        speak.setOnCheckedChangeListener { _, checked -> prefs.speak = checked }
        col.addView(speak)
        val hide = Switch(this)
        hide.text = "Esconder o boneco até eu dizer \"Ok Capri\""
        hide.setTextColor(INK)
        hide.isChecked = prefs.autoHide
        hide.setOnCheckedChangeListener { _, checked ->
            prefs.autoHide = checked
            if (OverlayService.running) {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_PREFS))
            }
        }
        col.addView(hide)
        val chat = Switch(this)
        chat.text = "A Capri fala coisas sozinha de vez em quando"
        chat.setTextColor(INK)
        chat.isChecked = prefs.chatter
        chat.setOnCheckedChangeListener { _, checked ->
            prefs.chatter = checked
            if (OverlayService.running) {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_PREFS))
            }
        }
        col.addView(chat)

        col.addView(heading("Comandos"))
        col.addView(body("Diga, por exemplo: \"Ok Capri, abrir WhatsApp\", \"pesquisar receita de bolo\", \"tocar Legião Urbana\", \"como chegar no aeroporto\", \"timer de 10 minutos\", \"alarme às 7 e meia\", \"ligar a lanterna\", \"que horas são\". Esses comandos funcionam sem gastar a IA. Pedidos em outras palavras a IA tenta entender."))
        col.addView(body("Também: \"abrir um jogo aleatório\", \"aumenta o volume\", \"volume em 40\", \"pausa a música\", \"próxima música\", \"liga pra mãe\", \"manda zap pro João dizendo já cheguei\", \"abrir configurações de bateria\". Com a Acessibilidade ligada: \"voltar\", \"apps recentes\", \"abre as notificações\", \"tira um print\", \"bloqueia o celular\", \"rola pra baixo\", \"clica em Enviar\", \"digita olá\"."))

        addExtrasSection(col)

        addVoiceSection(col)

        col.addView(heading("Manter ligada"))
        batteryStatus = body("")
        col.addView(batteryStatus)
        batteryBtn = button("Liberar uso de bateria") { askBattery() }
        col.addView(batteryBtn)
        col.addView(body("Para chamar a Capri sem abrir o app: puxe a barra de cima do celular, toque no lápis de editar e arraste o botão \"Capri\" para os atalhos. O botão liga a Capri e, com ela ligada, mostra a caixa de conversa. A notificação também tem o botão Chamar. O Android não deixa o microfone ligar sozinho depois de reiniciar o celular, então nessa hora a Capri deixa uma notificação para você tocar e ligar."))

        toggleBtn = button("Ativar Capri") { toggle() }
        col.addView(toggleBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
            topMargin = dp(20)
        })

        col.addView(body("Ao ativar, a Capri fica ouvindo em segundo plano pelo reconhecimento de voz do Google. Isso gasta bateria, mostra o ícone de microfone do Android e pode enviar o áudio aos servidores do Google se o pacote de voz offline do português não estiver instalado. Para parar, toque em Desativar Capri ou na notificação."))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG)
        scroll.addView(col)
        return scroll
    }

    private fun addBodySection(col: LinearLayout) {
        col.addView(heading("Corpo da Capri"))
        bodyPreview = BodyView(this)
        bodyPreview.setBackgroundColor(PANEL)
        bodyPreview.setOnClickListener { nextPose() }
        col.addView(bodyPreview, LinearLayout.LayoutParams(dp(120), dp(185)))
        col.addView(body("Toque na figura para ver os movimentos dela."))

        val sp = Spinner(this)
        val names = listOf(
            "Sem corpo (só a imagem)",
            "Fofa (cabeção e corpinho pequeno)",
            "Esguia (corpo alto e fino)",
            "Robô (quadradinho)",
            "Minha imagem de corpo e cabelo",
            "Esqueleto (meu corpo articulado)"
        )
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        bodySpinner = sp
        sp.setSelection(prefs.bodyType.coerceIn(0, 5))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos != prefs.bodyType) {
                    prefs.bodyType = pos
                    applyBodyPreview()
                    pushVoice()
                    if (pos == 4 && !File(filesDir, "capri_body.png").exists()) pickBody()
                    if (pos == 5 && !File(filesDir, "rig_body.png").exists()) {
                        Toast.makeText(this@MainActivity, "Primeiro faça o corpo articulado, nos botões logo abaixo.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        col.addView(sp)

        col.addView(button("Escolher imagem do corpo e do cabelo") { pickBody() })
        col.addView(button("Posicionar o rosto no corpo") { openFit() })
        col.addView(body("Para ter o corpo e o cabelo que você quer: escolha uma imagem sua do corpo inteiro com cabelo (o app não traz nenhuma pronta), recorte o fundo, e depois posicione o rosto que você escolheu em \"3. Imagem da Capri\". Nessa opção a figura se mexe inteira, de uma vez: não tem braços e pernas separados."))

        addRigSection(col)

        col.addView(body("Tamanho da figura"))
        col.addView(seek(((prefs.figureScale - 0.6f) * 100f).toInt().coerceIn(0, 100)) { p ->
            prefs.figureScale = 0.6f + p / 100f
            applyBodyPreview()
        })

        col.addView(body("Cor do corpo (formas desenhadas)"))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val colors = intArrayOf(
            0xFFFF4F9D.toInt(), 0xFF46F0D4.toInt(), 0xFF9B6BFF.toInt(), 0xFFFFC145.toInt(),
            0xFF7CFF6B.toInt(), 0xFFFF4D4D.toInt(), 0xFFEBE9FF.toInt(), 0xFF1B1B26.toInt()
        )
        swatches.clear()
        for (c in colors) {
            val v = View(this)
            v.setOnClickListener {
                prefs.bodyColor = c
                paintSwatches()
                applyBodyPreview()
                pushVoice()
            }
            row.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(8) })
            swatches.add(Pair(v, c))
        }
        col.addView(row)
        paintSwatches()

        val moves = Switch(this)
        moves.text = "Ela se mexe sozinha (poses e danças)"
        moves.setTextColor(INK)
        moves.isChecked = prefs.moves
        moves.setOnCheckedChangeListener { _, checked ->
            prefs.moves = checked
            pushVoice()
        }
        col.addView(moves)
        val wander = Switch(this)
        wander.text = "Ela passeia pela tela de vez em quando"
        wander.setTextColor(INK)
        wander.isChecked = prefs.wander
        wander.setOnCheckedChangeListener { _, checked ->
            prefs.wander = checked
            pushVoice()
        }
        col.addView(wander)
        val glitch = Switch(this)
        glitch.text = "Efeito glitch: ela some e reaparece em outro lugar"
        glitch.setTextColor(INK)
        glitch.isChecked = prefs.glitch
        glitch.setOnCheckedChangeListener { _, checked ->
            prefs.glitch = checked
            pushVoice()
        }
        col.addView(glitch)
        col.addView(body("O corpo é um desenho simples feito de formas, para combinar com a imagem que você escolheu como rosto. Se a sua imagem já é o corpo inteiro, escolha \"Sem corpo\"."))
    }

    private fun addRigSection(col: LinearLayout) {
        col.addView(heading("Esqueleto (corpo articulado)"))
        rigStatus = body("")
        col.addView(rigStatus)
        col.addView(body("Com isto a Capri ganha braços, pernas, mãos e pés que dobram de verdade: andar, sentar, voar, dançar, dar tchau. Você escolhe uma foto com o corpo inteiro, recorta só o cabelo, recorta só o corpo, coloca o rosto e marca onde ficam as juntas."))
        col.addView(button("Fazer o corpo articulado (passo a passo)") {
            rigChain = true
            pickRigPhoto()
        })
        col.addView(body("Ou refazer só um passo:"))
        col.addView(button("1. Escolher a foto do corpo inteiro") {
            rigChain = false
            pickRigPhoto()
        })
        col.addView(button("2. Recortar só o cabelo") {
            rigChain = false
            openRigCut("rig_hair", REQ_RIG_HAIR)
        })
        col.addView(button("3. Recortar só o corpo") {
            rigChain = false
            openRigCut("rig_body", REQ_RIG_BODY)
        })
        col.addView(button("4. Posicionar o rosto") {
            rigChain = false
            openRigFace()
        })
        col.addView(button("5. Colocar o esqueleto (juntas) e testar") {
            rigChain = false
            openRigEdit()
        })
        col.addView(body("O app não traz nenhum corpo pronto: a figura é feita da sua foto. Se a foto for de frente, com os braços e as pernas um pouco afastados do corpo, o resultado fica bem melhor."))
    }

    private fun rigStatusText(): String {
        fun m(ok: Boolean) = if (ok) "[ok] " else "[  ] "
        val src = File(filesDir, "rig_src.png").exists()
        val hair = File(filesDir, "rig_hair.png").exists()
        val bodyOk = File(filesDir, "rig_body.png").exists()
        val joints = Rig.decode(prefs.rigJoints) != null
        return m(src) + "1. Foto\n" + m(hair) + "2. Cabelo\n" + m(bodyOk) + "3. Corpo\n" +
            "[..] 4. Rosto (usa a imagem do rosto da Capri)\n" + m(joints) + "5. Esqueleto"
    }

    @Suppress("DEPRECATION")
    private fun pickRigPhoto() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(i, REQ_RIG_PICK)
    }

    @Suppress("DEPRECATION")
    private fun openRigCut(target: String, req: Int) {
        if (!File(filesDir, "rig_src.png").exists()) {
            pickRigPhoto()
            return
        }
        val i = Intent(this, CutoutActivity::class.java)
        i.putExtra("target", target)
        startActivityForResult(i, req)
    }

    @Suppress("DEPRECATION")
    private fun openRigFace() {
        if (!File(filesDir, "rig_body.png").exists()) {
            Toast.makeText(this, "Faça antes o recorte do corpo (passo 3).", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(Intent(this, FaceFitActivity::class.java).putExtra("rig", true), REQ_RIG_FACE)
    }

    @Suppress("DEPRECATION")
    private fun openRigEdit() {
        if (!File(filesDir, "rig_body.png").exists()) {
            Toast.makeText(this, "Faça antes o recorte do corpo (passo 3).", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(Intent(this, RigEditActivity::class.java), REQ_RIG_EDIT)
    }

    private fun paintSwatches() {
        for (pr in swatches) {
            val on = pr.second == prefs.bodyColor
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(pr.second)
            g.setStroke(dp(if (on) 3 else 1), if (on) 0xFFFFFFFF.toInt() else 0xFF555577.toInt())
            pr.first.background = g
        }
    }

    private fun applyBodyPreview() {
        bodyPreview.applyPrefs(prefs)
        val box = bodyPreview.boxDp()
        val k = prefs.figureScale.coerceIn(0.6f, 1.6f)
        val lp = bodyPreview.layoutParams
        lp.width = dp((box.first * k).toInt())
        lp.height = dp((box.second * k).toInt())
        bodyPreview.layoutParams = lp
        bodyPreview.invalidate()
    }

    @Suppress("DEPRECATION")
    private fun pickBody() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(i, REQ_BODY)
    }

    @Suppress("DEPRECATION")
    private fun openFit() {
        if (!File(filesDir, "capri_body.png").exists()) {
            pickBody()
            return
        }
        startActivityForResult(Intent(this, FaceFitActivity::class.java), REQ_FIT)
    }

    private fun nextPose() {
        val all = arrayOf(
            Move.WAVE, Move.DANCE, Move.DANCE2, Move.HOP, Move.SPIN, Move.STRETCH, Move.LEAN,
            Move.WALK, Move.SIT, Move.FLY, Move.CHEER, Move.SHRUG, Move.THINK, Move.LISTEN, Move.TALK,
            Move.SASS, Move.LOOK, Move.GLITCH
        )
        poseIdx = (poseIdx + 1) % all.size
        bodyPreview.setPose(all[poseIdx], 3000L)
    }

    private fun addExtrasSection(col: LinearLayout) {
        col.addView(heading("Poderes extras"))
        extrasStatus = body("")
        col.addView(extrasStatus)
        contactsBtn = button("Liberar contatos (ligar e mandar mensagem pelo nome)") {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        }
        col.addView(contactsBtn)
        a11yBtn = button("Ligar a Acessibilidade da Capri") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(a11yBtn)
        col.addView(button("Informações do app (liberar configurações restritas)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(body("A Acessibilidade é o que deixa a Capri apertar Voltar, abrir as notificações, tirar print, bloquear a tela, rolar, tocar em botões pelo nome e digitar. Ela só age quando você pede e não lê nem guarda o que aparece na tela. No Android 13 ou mais novo, apps instalados fora da Play Store vêm com isso bloqueado: se a opção aparecer em cinza, abra as informações do app, toque nos três pontinhos no canto de cima, escolha \"Permitir configurações restritas\" e depois ligue a Capri em Acessibilidade. Limites: nenhum app consegue \"qualquer coisa\". Apps de banco e telas protegidas bloqueiam esse acesso, o Android não deixa ligar Wi-Fi, dados, modo avião ou localização sozinho, e a Capri nunca aperta botões de pagar, comprar, transferir ou apagar conta. Mensagens ficam prontas para você tocar em enviar, e ligações abrem o discador."))
    }

    private fun addVoiceSection(col: LinearLayout) {
        col.addView(heading("Voz da Capri"))

        col.addView(body("Mecanismo de voz"))
        engineSpinner = Spinner(this)
        col.addView(engineSpinner)
        col.addView(body("Para usar vozes de outro app, instale o app, ative-o em Configurações do Android, Saída de texto para voz, e depois escolha o mecanismo dele aqui."))

        val fem = Switch(this)
        fem.text = "Mostrar só vozes femininas"
        fem.setTextColor(INK)
        fem.isChecked = prefs.onlyFemale
        fem.setOnCheckedChangeListener { _, checked ->
            prefs.onlyFemale = checked
            refreshVoiceList()
        }
        col.addView(fem)

        voiceSpinner = Spinner(this)
        col.addView(voiceSpinner)
        voiceHint = body("")
        col.addView(voiceHint)
        markBtn = button("Marcar esta voz como feminina") { toggleMark() }
        col.addView(markBtn)

        col.addView(body("Tom"))
        col.addView(seek(((prefs.pitch - 0.5f) * 100f).toInt().coerceIn(0, 100)) { p -> prefs.pitch = 0.5f + p / 100f })
        col.addView(body("Velocidade"))
        col.addView(seek(((prefs.rate - 0.6f) * 100f).toInt().coerceIn(0, 100)) { p -> prefs.rate = 0.6f + p / 100f })
        col.addView(button("Testar voz") { speakTest() })
    }

    private fun seek(progress: Int, onChange: (Int) -> Unit): SeekBar {
        val s = SeekBar(this)
        s.max = 100
        s.progress = progress
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                pushVoice()
            }
        })
        return s
    }

    private fun pushReload() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RELOAD))
        }
    }

    private fun pushVoice() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_PREFS))
        }
    }

    private fun startTts() {
        tts?.stop()
        tts?.shutdown()
        val e = prefs.ttsEngine
        val engine: String? = if (e.isEmpty()) null else e
        tts = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                fillEngines()
                fillVoices()
            } else if (prefs.ttsEngine.isNotEmpty()) {
                prefs.ttsEngine = ""
                startTts()
            }
        }, engine)
    }

    private fun currentEngine(): String {
        val e = prefs.ttsEngine
        return if (e.isNotEmpty()) e else (tts?.defaultEngine ?: "")
    }

    private fun fillEngines() {
        val list: List<TextToSpeech.EngineInfo> = try {
            tts?.engines ?: emptyList<TextToSpeech.EngineInfo>()
        } catch (e: Exception) {
            emptyList<TextToSpeech.EngineInfo>()
        }
        val labels = list.map { it.label ?: it.name }
        engineSpinner.onItemSelectedListener = null
        engineSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = list.indexOfFirst { it.name == currentEngine() }
        if (idx >= 0) engineSpinner.setSelection(idx)
        engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val pkg = list[pos].name
                if (pkg != currentEngine()) {
                    prefs.ttsEngine = pkg
                    prefs.voiceName = ""
                    pushVoice()
                    startTts()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun isFemale(v: Voice): Boolean {
        val n = v.name.lowercase()
        return prefs.femaleVoices.contains(v.name) || n.contains("female") || n.contains("feminin")
    }

    private fun fillVoices() {
        val all: Set<Voice>? = try {
            tts?.voices
        } catch (e: Exception) {
            null
        }
        voices = (all ?: emptySet<Voice>()).filter { it.locale.language == "pt" }.sortedBy { it.name }
        refreshVoiceList()
    }

    private fun refreshVoiceList() {
        val fem = voices.filter { isFemale(it) }
        val filterOn = prefs.onlyFemale && fem.isNotEmpty()
        val list = if (filterOn) fem else voices
        val offset = if (filterOn) 0 else 1

        voiceHint.text = if (voices.isEmpty()) {
            "Este mecanismo não tem vozes em português."
        } else if (prefs.onlyFemale && fem.isEmpty()) {
            "Nenhuma voz está marcada como feminina ainda. Escolha uma voz na lista (a frase de teste toca sozinha) e, se for feminina, toque em Marcar. Depois só as marcadas aparecem."
        } else {
            ""
        }

        // mantém a escolha coerente com o filtro, sem tocar a frase de teste
        var cur = prefs.voiceName
        if (filterOn && list.none { it.name == cur }) {
            cur = list[0].name
        } else if (!filterOn && cur.isNotEmpty() && list.none { it.name == cur }) {
            cur = ""
        }
        if (cur != prefs.voiceName) {
            prefs.voiceName = cur
            pushVoice()
        }

        val labels = ArrayList<String>()
        if (!filterOn) labels.add("Voz padrão do celular")
        var n = 1
        for (v in list) {
            val tag = if (isFemale(v)) " (feminina)" else ""
            labels.add("Voz " + n + tag + " · " + v.name)
            n++
        }
        voiceSpinner.onItemSelectedListener = null
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val at = list.indexOfFirst { it.name == cur }
        voiceSpinner.setSelection(if (at >= 0) at + offset else 0)
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val name = if (pos - offset < 0) "" else list[pos - offset].name
                if (name != prefs.voiceName) {
                    prefs.voiceName = name
                    pushVoice()
                    speakTest()
                    updateMarkBtn()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updateMarkBtn()
    }

    private fun updateMarkBtn() {
        val v = voices.firstOrNull { it.name == prefs.voiceName }
        markBtn.text = if (v == null) {
            "Marcar a voz escolhida como feminina"
        } else if (prefs.femaleVoices.contains(v.name)) {
            "Desmarcar: esta voz NÃO é feminina"
        } else {
            "Marcar esta voz como feminina"
        }
    }

    private fun toggleMark() {
        val v = voices.firstOrNull { it.name == prefs.voiceName } ?: return
        val set = HashSet<String>(prefs.femaleVoices)
        if (!set.remove(v.name)) set.add(v.name)
        prefs.femaleVoices = set
        refreshVoiceList()
    }

    private fun speakTest() {
        val t = tts ?: return
        t.setPitch(prefs.pitch)
        t.setSpeechRate(prefs.rate)
        val v = voices.firstOrNull { it.name == prefs.voiceName }
        if (v != null) t.setVoice(v) else t.setLanguage(Locale("pt", "BR"))
        t.speak("Oi, eu sou a Capri. Esta é a minha voz.", TextToSpeech.QUEUE_FLUSH, null, "teste")
    }

    private fun heading(text: String): TextView {
        val t = TextView(this)
        t.text = text.uppercase()
        t.setTextColor(INK)
        t.textSize = 13f
        t.setTypeface(null, Typeface.BOLD)
        t.letterSpacing = 0.1f
        t.setPadding(0, dp(22), 0, dp(6))
        return t
    }

    private fun body(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(DIM)
        t.textSize = 14f
        t.setPadding(0, dp(6), 0, dp(6))
        return t
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { onClick() }
        return b
    }

    private fun saveFields() {
        prefs.apiKey = keyField.text.toString()
        prefs.model = modelField.text.toString()
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun nextPermission() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM)
            return
        }
        if (!hasNotif()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM)
            return
        }
        if (!canOverlay()) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // segue para a próxima permissão que faltar
        if (requestCode == REQ_PERM && (!hasMic() || !hasNotif() || !canOverlay())) {
            if (hasMic()) nextPermission()
        }
    }

    private fun toggle() {
        saveFields()
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
        } else {
            if (!hasMic() || !canOverlay()) {
                nextPermission()
                return
            }
            startForegroundService(Intent(this, OverlayService::class.java))
        }
        toggleBtn.postDelayed({ refresh() }, 500)
    }

    @Suppress("DEPRECATION")
    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(i, REQ_IMG)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMG && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                val i = Intent(this, CutoutActivity::class.java)
                i.data = uri
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivityForResult(i, REQ_CUT)
            }
        } else if (requestCode == REQ_BODY && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                val i = Intent(this, CutoutActivity::class.java)
                i.data = uri
                i.putExtra("target", "body")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivityForResult(i, REQ_BODYCUT)
            }
        } else if (requestCode == REQ_BODYCUT && resultCode == RESULT_OK) {
            prefs.bodyType = 4
            bodySpinner.setSelection(4)
            refresh()
            pushReload()
            startActivityForResult(Intent(this, FaceFitActivity::class.java), REQ_FIT)
        } else if (requestCode == REQ_FIT && resultCode == RESULT_OK) {
            refresh()
            pushReload()
        } else if (requestCode == REQ_RIG_PICK && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                val i = Intent(this, CutoutActivity::class.java)
                i.data = uri
                i.putExtra("target", "rig_src")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivityForResult(i, REQ_RIG_SRC)
            }
        } else if (requestCode == REQ_RIG_SRC) {
            refresh()
            if (resultCode == RESULT_OK && rigChain) openRigCut("rig_hair", REQ_RIG_HAIR) else rigChain = false
        } else if (requestCode == REQ_RIG_HAIR) {
            refresh()
            if (rigChain) openRigCut("rig_body", REQ_RIG_BODY)
        } else if (requestCode == REQ_RIG_BODY) {
            refresh()
            if (resultCode == RESULT_OK && rigChain) openRigFace() else rigChain = false
        } else if (requestCode == REQ_RIG_FACE) {
            refresh()
            pushReload()
            if (resultCode == RESULT_OK && rigChain) openRigEdit() else rigChain = false
        } else if (requestCode == REQ_RIG_EDIT) {
            rigChain = false
            if (resultCode == RESULT_OK) {
                bodySpinner.setSelection(5)
            }
            refresh()
            pushReload()
        } else if (requestCode == REQ_CUT && resultCode == RESULT_OK) {
            refresh()
            if (OverlayService.running) {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RELOAD))
            }
        }
    }

    private fun askBattery() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun refresh() {
        val mic = hasMic()
        val ov = canOverlay()
        val key = prefs.apiKey.isNotEmpty()
        status.text = "Microfone: " + mark(mic) +
            "\nSobrepor outros apps: " + mark(ov) +
            "\nNotificações: " + mark(hasNotif()) +
            "\nChave da API: " + mark(key)
        grantBtn.visibility = if (mic && ov && hasNotif()) View.GONE else View.VISIBLE
        toggleBtn.text = if (OverlayService.running) "Desativar Capri" else "Ativar Capri"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val free = pm.isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = "Sem limite de bateria: " + mark(free)
        batteryBtn.visibility = if (free) View.GONE else View.VISIBLE

        val f = File(filesDir, "capri.png")
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        if (bmp != null) preview.setImageBitmap(bmp) else preview.setImageResource(R.drawable.ic_chip)
        if (bmp != null) {
            bodyPreview.setHead(bmp, null)
        } else {
            bodyPreview.setHead(null, resources.getDrawable(R.drawable.ic_chip, theme))
        }
        bodyPreview.cutout = prefs.cutout || bmp == null
        val bf = File(filesDir, "capri_body.png")
        bodyPreview.setSprite(if (bf.exists()) BitmapFactory.decodeFile(bf.absolutePath) else null)
        bodyPreview.loadRigFiles(prefs)
        rigStatus.text = rigStatusText()
        applyBodyPreview()

        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val a11y = A11yService.enabled(this)
        extrasStatus.text = "Contatos: " + mark(contacts) + "\nAcessibilidade: " + (if (a11y) "ligada" else "desligada")
        contactsBtn.visibility = if (contacts) View.GONE else View.VISIBLE
        a11yBtn.text = if (a11y) "Acessibilidade ligada (toque para ajustar)" else "Ligar a Acessibilidade da Capri"
    }

    private fun mark(ok: Boolean): String = if (ok) "ok" else "falta"

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_IMG = 2
        private const val REQ_CUT = 3
        private const val REQ_CONTACTS = 4
        private const val REQ_BODY = 5
        private const val REQ_BODYCUT = 6
        private const val REQ_FIT = 7
        private const val REQ_RIG_PICK = 8
        private const val REQ_RIG_SRC = 9
        private const val REQ_RIG_HAIR = 10
        private const val REQ_RIG_BODY = 11
        private const val REQ_RIG_FACE = 12
        private const val REQ_RIG_EDIT = 13
        private val BG = 0xFF0B0A15.toInt()
        private val PANEL = 0xFF141229.toInt()
        private val INK = 0xFFEBE9FF.toInt()
        private val DIM = 0xFF9692C8.toInt()
        private val TEAL = 0xFF46F0D4.toInt()
    }
}
