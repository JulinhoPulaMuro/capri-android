package app.capri

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Escolhe o fundo a apagar e salva o boneco como PNG transparente. */
class CutoutActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var cutView: CutoutView
    private lateinit var hint: TextView
    private lateinit var tolLabel: TextView
    private lateinit var wandBtn: Button
    private lateinit var brushBtn: Button
    private var original: Bitmap? = null
    private lateinit var boxBtn: Button
    private lateinit var panBtn: Button
    private lateinit var tolRow: LinearLayout
    private lateinit var brushRow: LinearLayout
    private lateinit var brushLabel: TextView
    private lateinit var zoomLabel: TextView
    private var body = false
    private var rig = false
    private var target = "face"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        target = intent?.getStringExtra("target") ?: "face"
        body = target == "body"
        rig = target == "rig_hair" || target == "rig_body"
        val uri = intent?.data
        val bmp = if (rig) {
            val sf = File(filesDir, "rig_src.png")
            if (sf.exists()) BitmapFactory.decodeFile(sf.absolutePath) else null
        } else if (uri != null) {
            decode(uri)
        } else {
            null
        }
        if (bmp == null) {
            Toast.makeText(this, "Não consegui abrir essa imagem.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        if (target == "rig_src") {
            // guarda a foto de base do esqueleto; as camadas antigas deixam de valer
            File(filesDir, "rig_src.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(filesDir, "rig_hair.png").delete()
            File(filesDir, "rig_body.png").delete()
            setResult(RESULT_OK)
            finish()
            return
        }
        if (!rig && ImageOps.hasAlpha(bmp)) {
            // PNG que já tem fundo transparente: usa direto, só ajusta as bordas.
            save(ImageOps.trim(bmp) ?: bmp, true)
            setResult(RESULT_OK)
            finish()
            return
        }
        original = bmp
        buildUi(bmp)
    }

    private fun buildUi(bmp: Bitmap) {
        val pad = dp(16)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(pad, pad + dp(16), pad, pad)

        val title = TextView(this)
        title.text = when (target) {
            "rig_hair" -> "RECORTAR SÓ O CABELO"
            "rig_body" -> "RECORTAR SÓ O CORPO"
            "body" -> "RECORTAR O CORPO E O CABELO"
            else -> "RECORTAR O BONECO"
        }
        title.setTextColor(INK)
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        hint = TextView(this)
        hint.text = if (rig) {
            if (target == "rig_hair") {
                "Deixe só o cabelo. Apague o fundo com a Varinha e tudo o que não é cabelo (rosto, corpo, cantos) com a Caixa ou o Pincel. Depois toque em Pronto."
            } else {
                "Deixe só o corpo, sem cabelo e sem o rosto (o rosto você coloca depois). Apague o fundo com a Varinha e o resto com a Caixa ou o Pincel. Depois toque em Pronto."
            }
        } else if (body) {
            "Toque no fundo para apagar com a Varinha. Para tirar cantos, molduras ou outras figuras que não fazem parte, use Caixa (arraste um retângulo) ou Pincel. Se sobrar fundo, aumente a tolerância. Se apagar demais, diminua ou use Desfazer."
        } else {
            "Toque no fundo para apagar. Pontinhos soltos somem sozinhos ao tocar em Pronto. Se sobrar fundo, aumente a tolerância. Se apagar demais, diminua ou use Desfazer."
        }
        hint.text = hint.text.toString() + "\nZoom: use dois dedos (pinça) ou os botões + e − sobre a imagem. A ferramenta Mão move a imagem com um dedo."
        hint.setTextColor(DIM)
        hint.textSize = 12f
        hint.setPadding(0, dp(4), 0, dp(6))
        root.addView(hint)

        cutView = CutoutView(this)
        cutView.setImage(bmp)
        val stage = FrameLayout(this)
        stage.addView(cutView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val zoomBar = LinearLayout(this)
        zoomBar.orientation = LinearLayout.HORIZONTAL
        zoomBar.gravity = Gravity.CENTER_VERTICAL
        val zm = zoomButton("−") { cutView.zoomBy(1f / 1.5f) }
        zoomLabel = TextView(this)
        zoomLabel.text = "100%"
        zoomLabel.setTextColor(INK)
        zoomLabel.textSize = 12f
        zoomLabel.gravity = Gravity.CENTER
        zoomLabel.minWidth = dp(48)
        zoomLabel.background = zoomBg()
        zoomLabel.setOnClickListener { cutView.resetZoom() }
        val zp = zoomButton("+") { cutView.zoomBy(1.5f) }
        zoomBar.addView(zm, LinearLayout.LayoutParams(dp(44), dp(40)))
        zoomBar.addView(zoomLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })
        zoomBar.addView(zp, LinearLayout.LayoutParams(dp(44), dp(40)))
        stage.addView(zoomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(6)
            rightMargin = dp(6)
        })
        cutView.onZoomChanged = { z -> zoomLabel.text = (z * 100f).toInt().toString() + "%" }
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        wandBtn = Button(this)
        wandBtn.text = "Varinha"
        brushBtn = Button(this)
        brushBtn.text = "Pincel"
        boxBtn = Button(this)
        boxBtn.text = "Caixa"
        panBtn = Button(this)
        panBtn.text = "Mão"
        panBtn.setOnClickListener { setTool(CutoutView.Tool.PAN) }
        wandBtn.setOnClickListener { setTool(CutoutView.Tool.WAND) }
        brushBtn.setOnClickListener { setTool(CutoutView.Tool.BRUSH) }
        boxBtn.setOnClickListener { setTool(CutoutView.Tool.BOX) }
        tools.addView(wandBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(brushBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(boxBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(panBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tools)

        tolRow = LinearLayout(this)
        tolRow.orientation = LinearLayout.HORIZONTAL
        tolLabel = TextView(this)
        tolLabel.text = "Tolerância 30"
        tolLabel.setTextColor(DIM)
        tolLabel.textSize = 13f
        val seek = SeekBar(this)
        seek.max = 85
        seek.progress = 25
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                cutView.tolerance = progress + 5
                tolLabel.text = "Tolerância " + (progress + 5)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        tolRow.addView(tolLabel, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        tolRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tolRow)

        brushRow = LinearLayout(this)
        brushRow.orientation = LinearLayout.HORIZONTAL
        brushLabel = TextView(this)
        brushLabel.text = "Pincel " + cutView.brushDp
        brushLabel.setTextColor(DIM)
        brushLabel.textSize = 13f
        val bseek = SeekBar(this)
        bseek.max = 96
        bseek.progress = cutView.brushDp - 4
        bseek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                cutView.brushDp = progress + 4
                brushLabel.text = "Pincel " + (progress + 4)
                if (fromUser) cutView.showBrushPreview()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        brushRow.addView(brushLabel, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        brushRow.addView(bseek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(brushRow)
        setTool(CutoutView.Tool.WAND)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val undo = Button(this)
        undo.text = "Desfazer"
        undo.setOnClickListener { cutView.undoLast() }
        val skip = Button(this)
        skip.text = "Sem recorte"
        if (rig) skip.visibility = View.GONE
        skip.setOnClickListener {
            val o = original
            if (o != null) save(o, false)
            setResult(RESULT_OK)
            finish()
        }
        val done = Button(this)
        done.text = "Pronto"
        done.setOnClickListener {
            val out = if (rig) cutView.finishFrame() else cutView.finish(body)
            if (out == null) {
                hint.text = "A imagem ficou vazia. Toque em Desfazer para voltar."
            } else {
                save(out, true)
                setResult(RESULT_OK)
                finish()
            }
        }
        buttons.addView(undo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(skip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        setContentView(root)
    }

    private fun setTool(t: CutoutView.Tool) {
        cutView.tool = t
        wandBtn.alpha = if (t == CutoutView.Tool.WAND) 1f else 0.5f
        brushBtn.alpha = if (t == CutoutView.Tool.BRUSH) 1f else 0.5f
        boxBtn.alpha = if (t == CutoutView.Tool.BOX) 1f else 0.5f
        panBtn.alpha = if (t == CutoutView.Tool.PAN) 1f else 0.5f
        tolRow.visibility = if (t == CutoutView.Tool.WAND) View.VISIBLE else View.GONE
        brushRow.visibility = if (t == CutoutView.Tool.BRUSH) View.VISIBLE else View.GONE
        cutView.invalidate()
        if (t == CutoutView.Tool.BRUSH) cutView.showBrushPreview()
    }

    private fun zoomBg(): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(0xCC141229.toInt())
        g.setStroke(dp(1), 0xFF46F0D4.toInt())
        g.cornerRadius = dp(10).toFloat()
        return g
    }

    private fun zoomButton(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(INK)
        t.textSize = 20f
        t.gravity = Gravity.CENTER
        t.background = zoomBg()
        t.setOnClickListener { onClick() }
        return t
    }

    private fun save(bmp: Bitmap, cut: Boolean) {
        val name = when (target) {
            "body" -> "capri_body.png"
            "rig_hair" -> "rig_hair.png"
            "rig_body" -> "rig_body.png"
            else -> "capri.png"
        }
        val f = File(filesDir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (target == "face") prefs.cutout = cut
    }

    private fun decode(uri: Uri): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            var bmp: Bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                contentResolver.openInputStream(uri)?.use {
                    orientation = ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (e: Exception) {
                orientation = ExifInterface.ORIENTATION_NORMAL
            }
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val m = Matrix()
                m.postRotate(degrees)
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }

            val maxSide = if (target == "rig_src" || target == "body") 900f else 640f
            val scale = maxSide / maxOf(bmp.width, bmp.height)
            if (scale < 1f) {
                val nw = maxOf(1, (bmp.width * scale).toInt())
                val nh = maxOf(1, (bmp.height * scale).toInt())
                bmp = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            }
            return bmp
        } catch (e: Exception) {
            return null
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = 0xFF0B0A15.toInt()
        private val INK = 0xFFEBE9FF.toInt()
        private val DIM = 0xFF9692C8.toInt()
    }
}
