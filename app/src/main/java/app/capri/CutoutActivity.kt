package app.capri

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
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
    private var body = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        body = intent?.getStringExtra("target") == "body"
        val uri = intent?.data
        val bmp = if (uri != null) decode(uri) else null
        if (bmp == null) {
            Toast.makeText(this, "Não consegui abrir essa imagem.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        if (ImageOps.hasAlpha(bmp)) {
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
        title.text = if (body) "RECORTAR O CORPO E O CABELO" else "RECORTAR O BONECO"
        title.setTextColor(INK)
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        hint = TextView(this)
        hint.text = if (body) {
            "Toque no fundo para apagar com a Varinha. Para tirar cantos, molduras ou outras figuras que não fazem parte, use Caixa (arraste um retângulo) ou Pincel. Se sobrar fundo, aumente a tolerância. Se apagar demais, diminua ou use Desfazer."
        } else {
            "Toque no fundo para apagar. Pontinhos soltos somem sozinhos ao tocar em Pronto. Se sobrar fundo, aumente a tolerância. Se apagar demais, diminua ou use Desfazer."
        }
        hint.setTextColor(DIM)
        hint.textSize = 13f
        hint.setPadding(0, dp(6), 0, dp(10))
        root.addView(hint)

        cutView = CutoutView(this)
        cutView.setImage(bmp)
        root.addView(cutView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        wandBtn = Button(this)
        wandBtn.text = "Varinha"
        brushBtn = Button(this)
        brushBtn.text = "Pincel"
        boxBtn = Button(this)
        boxBtn.text = "Caixa"
        wandBtn.setOnClickListener { setTool(CutoutView.Tool.WAND) }
        brushBtn.setOnClickListener { setTool(CutoutView.Tool.BRUSH) }
        boxBtn.setOnClickListener { setTool(CutoutView.Tool.BOX) }
        tools.addView(wandBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(brushBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(boxBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tools)
        setTool(CutoutView.Tool.WAND)

        val tolRow = LinearLayout(this)
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

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val undo = Button(this)
        undo.text = "Desfazer"
        undo.setOnClickListener { cutView.undoLast() }
        val skip = Button(this)
        skip.text = "Sem recorte"
        skip.setOnClickListener {
            val o = original
            if (o != null) save(o, false)
            setResult(RESULT_OK)
            finish()
        }
        val done = Button(this)
        done.text = "Pronto"
        done.setOnClickListener {
            val out = cutView.finish(body)
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
    }

    private fun save(bmp: Bitmap, cut: Boolean) {
        val f = File(filesDir, if (body) "capri_body.png" else "capri.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!body) prefs.cutout = cut
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

            val scale = 640f / maxOf(bmp.width, bmp.height)
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
