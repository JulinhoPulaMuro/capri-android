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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Onde a pessoa coloca as juntas do esqueleto sobre o corpo, e testa os movimentos. */
class RigEditActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var edit: EditView
    private lateinit var preview: BodyView
    private lateinit var label: TextView
    private lateinit var testBtn: Button
    private val main = Handler(Looper.getMainLooper())
    private var testing = false
    private var testIdx = 0
    private var joints = FloatArray(Rig.JOINTS * 2)
    private var hair: Bitmap? = null
    private var body: Bitmap? = null
    private var face: Bitmap? = null

    private val tests = listOf(
        Pair(Move.IDLE, "Parada"), Pair(Move.WALK, "Andando"), Pair(Move.WAVE, "Tchau"),
        Pair(Move.DANCE, "Dança 1"), Pair(Move.DANCE2, "Dança 2"), Pair(Move.HOP, "Pulo"),
        Pair(Move.CHEER, "Comemorando"), Pair(Move.SIT, "Sentada"), Pair(Move.FLY, "Voando"),
        Pair(Move.THINK, "Pensando"), Pair(Move.SHRUG, "Dando de ombros"), Pair(Move.STRETCH, "Espreguiçando")
    )

    private val cycle = object : Runnable {
        override fun run() {
            if (!testing) return
            val t = tests[testIdx % tests.size]
            testIdx++
            preview.setPose(t.first)
            label.text = "Testando: " + t.second
            main.postDelayed(this, 2600L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val bf = File(filesDir, "rig_body.png")
        body = if (bf.exists()) BitmapFactory.decodeFile(bf.absolutePath) else null
        val b = body
        if (b == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val hf = File(filesDir, "rig_hair.png")
        hair = if (hf.exists()) BitmapFactory.decodeFile(hf.absolutePath) else null
        val ff = File(filesDir, "capri.png")
        face = if (ff.exists()) BitmapFactory.decodeFile(ff.absolutePath) else null

        joints = Rig.decode(prefs.rigJoints) ?: defaultJoints(b)

        val pad = dp(16)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(pad, pad + dp(16), pad, pad)

        val title = TextView(this)
        title.text = "ESQUELETO"
        title.setTextColor(INK)
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        val hint = TextView(this)
        hint.text = "Cada bolinha é uma junta. Toque numa bolinha (ou use as setas) para escolher e arraste em qualquer lugar da tela para movê-la. E = esquerda da tela, D = direita. Azul é o lado E, rosa é o lado D. Depois toque em Testar para ver se os braços e as pernas dobram no lugar certo."
        hint.setTextColor(DIM)
        hint.textSize = 12f
        hint.setPadding(0, dp(4), 0, dp(6))
        root.addView(hint)

        label = TextView(this)
        label.setTextColor(TEAL)
        label.textSize = 14f
        label.setTypeface(null, Typeface.BOLD)
        label.setPadding(0, 0, 0, dp(6))
        root.addView(label)

        val stage = FrameLayout(this)
        val chip = resources.getDrawable(R.drawable.ic_chip, theme)
        edit = EditView(this, b, hair, face, chip, joints, prefs.rigFaceX, prefs.rigFaceY, prefs.rigFaceW, prefs.cutout || face == null)
        edit.onSelect = { i -> label.text = "Junta escolhida: " + Rig.NAMES[i] }
        stage.addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        preview = BodyView(this)
        preview.setBackgroundColor(PANEL)
        preview.visibility = View.GONE
        stage.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val prev = Button(this)
        prev.text = "◀"
        prev.setOnClickListener { edit.select((edit.sel + Rig.JOINTS - 1) % Rig.JOINTS) }
        val next = Button(this)
        next.text = "▶"
        next.setOnClickListener { edit.select((edit.sel + 1) % Rig.JOINTS) }
        val reset = Button(this)
        reset.text = "Padrão"
        reset.setOnClickListener {
            val d = defaultJoints(b)
            System.arraycopy(d, 0, joints, 0, d.size)
            edit.invalidate()
        }
        testBtn = Button(this)
        testBtn.text = "Testar"
        testBtn.setOnClickListener { setTesting(!testing) }
        row.addView(prev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(reset, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        row.addView(testBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
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
            prefs.rigJoints = Rig.encode(joints)
            prefs.bodyType = 5
            setResult(RESULT_OK)
            finish()
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        setContentView(root)
        edit.select(0)
    }

    override fun onPause() {
        setTesting(false)
        super.onPause()
    }

    private fun setTesting(on: Boolean) {
        if (on == testing) return
        testing = on
        if (on) {
            preview.bodyType = 5
            preview.faceX = prefs.rigFaceX
            preview.faceY = prefs.rigFaceY
            preview.faceW = prefs.rigFaceW
            preview.cutout = prefs.cutout || face == null
            preview.setHead(face, resources.getDrawable(R.drawable.ic_chip, theme))
            preview.setRig(hair, body, joints.copyOf())
            edit.visibility = View.GONE
            preview.visibility = View.VISIBLE
            testBtn.text = "Voltar a editar"
            testIdx = 0
            main.post(cycle)
        } else {
            main.removeCallbacks(cycle)
            edit.visibility = View.VISIBLE
            preview.visibility = View.GONE
            testBtn.text = "Testar"
            edit.select(edit.sel)
        }
    }

    /** Posição padrão a partir de onde está o corpo na imagem. */
    private fun defaultJoints(b: Bitmap): FloatArray {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((px[y * w + x] ushr 24) > 10) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return Rig.defaults(0.25f, 0.1f, 0.75f, 0.95f)
        return Rig.defaults(x0.toFloat() / w, y0.toFloat() / h, (x1 + 1f) / w, (y1 + 1f) / h)
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    /** Mostra cabelo, corpo e rosto, com as juntas por cima. */
    private class EditView(
        ctx: Context,
        private val body: Bitmap,
        private val hair: Bitmap?,
        face: Bitmap?,
        fallback: Drawable,
        private val j: FloatArray,
        private val faceX: Float,
        private val faceY: Float,
        private val faceW: Float,
        private val cutout: Boolean
    ) : View(ctx) {

        var sel = 0
            private set
        var onSelect: ((Int) -> Unit)? = null

        private val head: Drawable = if (face != null) BitmapDrawable(ctx.resources, face) else fallback
        private val hw = if (face != null) face.width else maxOf(1, fallback.intrinsicWidth)
        private val hh = if (face != null) face.height else maxOf(1, fallback.intrinsicHeight)
        private val dst = RectF()
        private val rect = RectF()
        private val clip = Path()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val checkA = Paint().apply { color = Color.parseColor("#1B1838") }
        private val checkB = Paint().apply { color = Color.parseColor("#26224A") }
        private val dens = ctx.resources.displayMetrics.density
        private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * dens
        }
        private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * dens
            color = Color.YELLOW
        }
        private var lastX = 0f
        private var lastY = 0f

        fun select(i: Int) {
            sel = i
            onSelect?.invoke(i)
            invalidate()
        }

        private fun colorOf(i: Int): Int = when {
            i < 2 -> Color.WHITE
            i in 2..5 || i in 10..13 -> Color.parseColor("#46C8FF")
            else -> Color.parseColor("#FF4F9D")
        }

        private fun sx(i: Int) = dst.left + j[i * 2] * dst.width()
        private fun sy(i: Int) = dst.top + j[i * 2 + 1] * dst.height()

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
            val cell = 12f * dens
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
            if (hair != null) canvas.drawBitmap(hair, null, dst, paint)
            canvas.drawBitmap(body, null, dst, paint)
            FaceDraw.draw(
                canvas, head, hw, hh, cutout,
                dst.left + faceX * dst.width(), dst.top + faceY * dst.height(), faceW * dst.width(), rect, clip
            )

            // ossos
            for (b in 0 until Rig.BONES) {
                val a = Rig.BONE_A[b]
                val c = Rig.BONE_B[b]
                lineP.color = if (b == Rig.B_TORSO) Color.WHITE else colorOf(c)
                canvas.drawLine(sx(a), sy(a), sx(c), sy(c), lineP)
            }
            // juntas
            for (i in 0 until Rig.JOINTS) {
                dotP.color = colorOf(i)
                canvas.drawCircle(sx(i), sy(i), 6f * dens, dotP)
            }
            canvas.drawCircle(sx(sel), sy(sel), 11f * dens, ringP)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                    var best = -1
                    var bd = 30f * dens
                    for (i in 0 until Rig.JOINTS) {
                        val dd = Math.hypot((sx(i) - e.x).toDouble(), (sy(i) - e.y).toDouble()).toFloat()
                        if (dd < bd) {
                            bd = dd
                            best = i
                        }
                    }
                    if (best >= 0) select(best)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dst.width() > 0f && dst.height() > 0f) {
                        val dx = (e.x - lastX) / dst.width()
                        val dy = (e.y - lastY) / dst.height()
                        lastX = e.x
                        lastY = e.y
                        j[sel * 2] = (j[sel * 2] + dx).coerceIn(0f, 1f)
                        j[sel * 2 + 1] = (j[sel * 2 + 1] + dy).coerceIn(0f, 1f)
                        invalidate()
                    }
                }
            }
            return true
        }
    }

    companion object {
        private val BG = 0xFF0B0A15.toInt()
        private val PANEL = 0xFF141229.toInt()
        private val INK = 0xFFEBE9FF.toInt()
        private val DIM = 0xFF9692C8.toInt()
        private val TEAL = 0xFF46F0D4.toInt()
    }
}
