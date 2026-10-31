package app.capri

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
    private var tts: TextToSpeech? = null
    private var voices: List<Voice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) fillVoices()
        }
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

        col.addView(heading("Comandos"))
        col.addView(body("Diga, por exemplo: \"Ok Capri, abrir WhatsApp\", \"pesquisar receita de bolo\", \"tocar Legião Urbana\", \"como chegar no aeroporto\", \"timer de 10 minutos\", \"alarme às 7 e meia\", \"ligar a lanterna\", \"que horas são\". Esses comandos funcionam sem gastar a IA. Pedidos em outras palavras a IA tenta entender."))

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

    private fun addVoiceSection(col: LinearLayout) {
        col.addView(heading("Voz da Capri"))
        voiceSpinner = Spinner(this)
        col.addView(voiceSpinner)
        col.addView(body("Estas são as vozes em português que o seu celular tem. Para ter mais opções, abra as configurações do Android, procure \"Saída de texto para voz\" e baixe vozes do mecanismo do Google ou do fabricante."))
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

    private fun pushVoice() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_PREFS))
        }
    }

    private fun fillVoices() {
        val all: Set<Voice>? = try {
            tts?.voices
        } catch (e: Exception) {
            null
        }
        val pt = (all ?: emptySet<Voice>()).filter { it.locale.language == "pt" }.sortedBy { it.name }
        voices = pt
        val labels = ArrayList<String>()
        labels.add("Voz padrão do celular")
        var n = 1
        for (v in pt) {
            val where = if (v.isNetworkConnectionRequired) "online" else "no celular"
            labels.add("Voz " + n + " (" + v.locale.toString().replace('_', '-') + ", " + where + ")")
            n++
        }
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        voiceSpinner.setSelection(pt.indexOfFirst { it.name == prefs.voiceName } + 1)
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val name = if (pos == 0) "" else pt[pos - 1].name
                if (name != prefs.voiceName) {
                    prefs.voiceName = name
                    pushVoice()
                    speakTest()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
    }

    private fun mark(ok: Boolean): String = if (ok) "ok" else "falta"

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_IMG = 2
        private const val REQ_CUT = 3
        private val BG = 0xFF0B0A15.toInt()
        private val PANEL = 0xFF141229.toInt()
        private val INK = 0xFFEBE9FF.toInt()
        private val DIM = 0xFF9692C8.toInt()
        private val TEAL = 0xFF46F0D4.toInt()
    }
}
