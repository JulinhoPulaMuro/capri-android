package app.capri

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/** Mostra a imagem do corpo e deixa a pessoa arrastar o rosto para o lugar certo. */
class FaceFitActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var fit: FitView
    private lateinit var seek: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val bf = File(filesDir, "capri_body.png")
        val body = if (bf.exists()) BitmapFactory.decodeFile(bf.absolutePath) else null
        if (body == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val ff = File(filesDir, "capri.png")
        val face = if (ff.exists()) BitmapFactory.decodeFile(ff.absolutePath) else null

        val pad = dp(16)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(pad, pad + dp(16), pad, pad)

        val title = TextView(this)
        title.text = "POSICIONAR O ROSTO"
        title.setTextColor(INK)
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        val hint = TextView(this)
        hint.text = "Arraste o rosto até o lugar dele no corpo. Use o controle abaixo (ou o gesto de pinça) para mudar o tamanho."
        hint.setTextColor(DIM)
        hint.textSize = 13f
        hint.setPadding(0, dp(6), 0, dp(10))
        root.addView(hint)

        fit = FitView(this, body, face, prefs.cutout || face == null, resources.getDrawable(R.drawable.ic_chip, theme))
        fit.fx = prefs.faceX
        fit.fy = prefs.faceY
        fit.fw = prefs.faceW
        root.addView(fit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lbl = TextView(this)
        lbl.text = "Tamanho"
        lbl.setTextColor(DIM)
        lbl.textSize = 13f
        seek = SeekBar(this)
        seek.max = 70
        seek.progress = ((fit.fw * 100f).toInt() - 3).coerceIn(0, 70)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    fit.fw = (p + 3) / 100f
                    fit.invalidate()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        fit.onScaled = { w -> seek.progress = ((w * 100f).toInt() - 3).coerceIn(0, 70) }
        row.addView(lbl, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val cancel = Button(this)
        cancel.text = "Cancelar"
        cancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        val done = Button(this)
        done.text = "Pronto"
        done.setOnClickListener {
            prefs.faceX = fit.fx
            prefs.faceY = fit.fy
            prefs.faceW = fit.fw
            setResult(RESULT_OK)
            finish()
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        setContentView(root)
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    /** Desenha o corpo e o rosto por cima; arrastar move, pinça muda o tamanho. */
    private class FitView(
        ctx: Context,
        private val body: Bitmap,
        face: Bitmap?,
        private val cutout: Boolean,
        fallback: Drawable
    ) : View(ctx) {

        var fx = 0.5f
        var fy = 0.3f
        var fw = 0.2f
        var onScaled: ((Float) -> Unit)? = null

        private val head: Drawable = if (face != null) BitmapDrawable(ctx.resources, face) else fallback
        private val hw = if (face != null) face.width else maxOf(1, fallback.intrinsicWidth)
        private val hh = if (face != null) face.height else maxOf(1, fallback.intrinsicHeight)
        private val dst = RectF()
        private val rect = RectF()
        private val clip = Path()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val checkA = Paint().apply { color = Color.parseColor("#1B1838") }
        private val checkB = Paint().apply { color = Color.parseColor("#26224A") }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#46F0D4")
            strokeWidth = 2f * ctx.resources.displayMetrics.density
        }
        private var dragging = false
        private var lastX = 0f
        private var lastY = 0f
        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                fw = (fw * d.scaleFactor).coerceIn(0.03f, 0.8f)
                onScaled?.invoke(fw)
                invalidate()
                return true
            }
        })

        override fun onDraw(canvas: Canvas) {
            val vw = width.toFloat()
            val vh = height.toFloat()
            val s = minOf(vw / body.width, vh / body.height)
            val dw = body.width * s
            val dh = body.height * s
            val left = (vw - dw) / 2f
            val top = (vh - dh) / 2f
            dst.set(left, top, left + dw, top + dh)

            canvas.save()
            canvas.clipRect(dst)
            canvas.drawRect(dst, checkA)
            val cell = 12f * resources.displayMetrics.density
            var row = 0
            var yy = dst.top
            while (yy < dst.bottom) {
                var col = 0
                var xx = dst.left
                while (xx < dst.right) {
                    if ((row + col) % 2 == 1) canvas.drawRect(xx, yy, xx + cell, yy + cell, checkB)
                    xx += cell
                    col++
                }
                yy += cell
                row++
            }
            canvas.restore()
            canvas.drawBitmap(body, null, dst, paint)

            val cx = dst.left + fx * dst.width()
            val cy = dst.top + fy * dst.height()
            val fwPx = fw * dst.width()
            FaceDraw.draw(canvas, head, hw, hh, cutout, cx, cy, fwPx, rect, clip)
            canvas.drawCircle(cx, cy, fwPx / 2f, ring)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaler.onTouchEvent(e)
            if (scaler.isInProgress) {
                dragging = false
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    lastX = e.x
                    lastY = e.y
                    moveTo(e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging && e.pointerCount == 1) moveTo(e.x, e.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        private fun moveTo(x: Float, y: Float) {
            if (dst.width() <= 0f || dst.height() <= 0f) return
            fx = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
            fy = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
            invalidate()
        }
    }

    companion object {
        private val BG = 0xFF0B0A15.toInt()
        private val INK = 0xFFEBE9FF.toInt()
        private val DIM = 0xFF9692C8.toInt()
    }
}
