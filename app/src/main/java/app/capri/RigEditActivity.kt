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
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.math.floor
import kotlin.math.hypot

/** Onde a pessoa coloca as juntas do esqueleto sobre o corpo, com zoom, e testa os movimentos. */
class RigEditActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var edit: EditView
    private lateinit var preview: BodyView
    private lateinit var label: TextView
    private lateinit var testBtn: Button
    private lateinit var regionsBtn: Button
    private lateinit var handBtn: Button
    private lateinit var zoomBar: LinearLayout
    private lateinit var zoomLabel: TextView
    private val main = Handler(Looper.getMainLooper())
    private var testing = false
    private var testIdx = 0
    private var joints = FloatArray(Rig.JOINTS * 2)
    private var hair: Bitmap? = null
    private var body: Bitmap? = null
    private var face: Bitmap? = null
    private var small: Bitmap? = null
    private var smallPx = IntArray(0)
    private var showRegions = false

    private val tests = listOf(
        Pair(Move.IDLE, "Parada"), Pair(Move.WALK, "Andando"), Pair(Move.WAVE, "Tchau"),
        Pair(Move.DANCE, "Dança 1"), Pair(Move.DANCE2, "Dança 2"), Pair(Move.HOP, "Pulo"),
        Pair(Move.CHEER, "Comemorando"), Pair(Move.SIT, "Sentada"), Pair(Move.FLY, "Voando"),
        Pair(Move.THINK, "Pensando"), Pair(Move.SHRUG, "Dando de ombros"), Pair(Move.STRETCH, "Espreguiçando"),
        Pair(Move.SASS, "Mão na cintura"), Pair(Move.LOOK, "Olhando em volta"), Pair(Move.GLITCH, "Glitch")
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

        // versão pequena do corpo, só para mostrar as regiões depressa
        val sc = minOf(1f, 300f / maxOf(b.width, b.height))
        val sw = maxOf(1, (b.width * sc).toInt())
        val sh = maxOf(1, (b.height * sc).toInt())
        val sm = Bitmap.createScaledBitmap(b, sw, sh, true)
        small = sm
        smallPx = IntArray(sw * sh)
        sm.getPixels(smallPx, 0, sw, 0, 0, sw, sh)

        joints = Rig.decode(prefs.rigJoints) ?: autoJoints(b)

        val pad = dp(14)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(pad, pad + dp(14), pad, pad)

        val title = TextView(this)
        title.text = "ESQUELETO"
        title.setTextColor(INK)
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        val hint = TextView(this)
        hint.text = "Cada bolinha é uma junta (azul = lado E da tela, rosa = lado D). Toque numa bolinha, ou use as setas, e arraste em qualquer lugar da tela para mover. Dois dedos dão zoom e movem a imagem. \"Automático\" adivinha pela silhueta. \"Regiões\" pinta a parte do corpo que cada osso leva."
        hint.setTextColor(DIM)
        hint.textSize = 12f
        hint.setPadding(0, dp(4), 0, dp(4))
        root.addView(hint)

        label = TextView(this)
        label.setTextColor(TEAL)
        label.textSize = 14f
        label.setTypeface(null, Typeface.BOLD)
        label.setPadding(0, 0, 0, dp(4))
        root.addView(label)

        val stage = FrameLayout(this)
        val chip = resources.getDrawable(R.drawable.ic_chip, theme)
        edit = EditView(this, b, hair, face, chip, joints, prefs.rigFaceX, prefs.rigFaceY, prefs.rigFaceW, prefs.cutout || face == null)
        edit.onSelect = { i -> label.text = "Junta escolhida: " + Rig.NAMES[i] }
        edit.onChanged = { if (showRegions) refreshRegions() }
        edit.onZoom = { z -> zoomLabel.text = (z * 100f).toInt().toString() + "%" }
        stage.addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        preview = BodyView(this)
        preview.setBackgroundColor(PANEL)
        preview.visibility = View.GONE
        stage.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        zoomBar = LinearLayout(this)
        zoomBar.orientation = LinearLayout.HORIZONTAL
        zoomBar.gravity = Gravity.CENTER_VERTICAL
        val zm = zoomButton("−") { edit.zoomBy(1f / 1.5f) }
        zoomLabel = TextView(this)
        zoomLabel.text = "100%"
        zoomLabel.setTextColor(INK)
        zoomLabel.textSize = 12f
        zoomLabel.gravity = Gravity.CENTER
        zoomLabel.minWidth = dp(48)
        zoomLabel.background = zoomBg()
        zoomLabel.setOnClickListener { edit.resetZoom() }
        val zp = zoomButton("+") { edit.zoomBy(1.5f) }
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
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val prev = Button(this)
        prev.text = "◀"
        prev.setOnClickListener { selectJoint((edit.sel + Rig.JOINTS - 1) % Rig.JOINTS) }
        val next = Button(this)
        next.text = "▶"
        next.setOnClickListener { selectJoint((edit.sel + 1) % Rig.JOINTS) }
        handBtn = Button(this)
        handBtn.text = "Mão"
        handBtn.alpha = 0.5f
        handBtn.setOnClickListener {
            edit.handMode = !edit.handMode
            handBtn.alpha = if (edit.handMode) 1f else 0.5f
        }
        val auto = Button(this)
        auto.text = "Automático"
        auto.setOnClickListener {
            val a = autoJoints(b)
            System.arraycopy(a, 0, joints, 0, a.size)
            edit.invalidate()
            if (showRegions) refreshRegions()
            label.text = "Juntas ajustadas pela silhueta. Corrija o que precisar."
        }
        row.addView(prev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
        row.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
        row.addView(handBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(auto, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        root.addView(row)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        regionsBtn = Button(this)
        regionsBtn.text = "Regiões"
        regionsBtn.alpha = 0.5f
        regionsBtn.setOnClickListener {
            showRegions = !showRegions
            regionsBtn.alpha = if (showRegions) 1f else 0.5f
            if (showRegions) refreshRegions() else edit.setRegions(null)
        }
        testBtn = Button(this)
        testBtn.text = "Testar"
        testBtn.setOnClickListener { setTesting(!testing) }
        row2.addView(regionsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(testBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        root.addView(row2)

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
        selectJoint(0)
    }

    override fun onPause() {
        setTesting(false)
        super.onPause()
    }

    private fun selectJoint(i: Int) {
        edit.select(i)
        edit.centerOn(i)
    }

    /** Pinta, em cada ponto do corpo, a cor do osso que o leva. */
    private fun refreshRegions() {
        val sm = small ?: return
        val sw = sm.width
        val sh = sm.height
        val sj = FloatArray(Rig.JOINTS * 2)
        for (i in 0 until Rig.JOINTS) {
            sj[i * 2] = joints[i * 2] * sw
            sj[i * 2 + 1] = joints[i * 2 + 1] * sh
        }
        val r = Rig.radiiFromMask(smallPx, sw, sh, sj)
        val owner = Rig.assignBones(smallPx, sw, sh, sj, r)
        val out = IntArray(sw * sh)
        val pal = IntArray(Rig.BONES) {
            (0x99 shl 24) or (Color.HSVToColor(floatArrayOf(it * 360f / Rig.BONES, 0.85f, 1f)) and 0xFFFFFF)
        }
        for (i in out.indices) {
            val o = owner[i].toInt()
            if (o >= 0) out[i] = pal[o]
        }
        edit.setRegions(Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888))
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
            zoomBar.visibility = View.GONE
            preview.visibility = View.VISIBLE
            testBtn.text = "Voltar a editar"
            testIdx = 0
            main.post(cycle)
        } else {
            main.removeCallbacks(cycle)
            edit.visibility = View.VISIBLE
            zoomBar.visibility = View.VISIBLE
            preview.visibility = View.GONE
            testBtn.text = "Testar"
            edit.select(edit.sel)
        }
    }

    /** Posição das juntas adivinhada pela silhueta do corpo. */
    private fun autoJoints(b: Bitmap): FloatArray {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        return Rig.autoFit(px, w, h)
    }

    private fun zoomBg(): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(0xCC141229.toInt())
        g.setStroke(dp(1), 0xFF46F0D4.toInt())
        g.cornerRadius = dp(10).toFloat()
        return g
    }

    private fun zoomButton(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(INK)
        t.textSize = 20f
        t.gravity = Gravity.CENTER
        t.background = zoomBg()
        t.setOnClickListener { onClick() }
        return t
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    /** Mostra cabelo, corpo e rosto, com as juntas por cima. Zoom com pinça. */
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
        var onChanged: (() -> Unit)? = null
        var onZoom: ((Float) -> Unit)? = null

        /** True: um dedo move a imagem em vez de uma junta. */
        var handMode = false

        private var regions: Bitmap? = null
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var scale = 1f
        private var multi = false
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var lastFx = 0f
        private var lastFy = 0f

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
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
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
        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                lastFx = d.focusX
                lastFy = d.focusY
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                panX += d.focusX - lastFx
                panY += d.focusY - lastFy
                lastFx = d.focusX
                lastFy = d.focusY
                zoomAt(d.focusX, d.focusY, zoom * d.scaleFactor)
                return true
            }
        })

        fun setRegions(b: Bitmap?) {
            regions = b
            invalidate()
        }

        fun select(i: Int) {
            sel = i
            onSelect?.invoke(i)
            invalidate()
        }

        private fun baseScale(): Float = minOf(width.toFloat() / body.width, height.toFloat() / body.height)

        private fun updateDst() {
            if (width <= 0 || height <= 0) return
            val vw = width.toFloat()
            val vh = height.toFloat()
            scale = baseScale() * zoom
            val dw = body.width * scale
            val dh = body.height * scale
            val hx = maxOf(0f, (dw - vw) / 2f)
            val hy = maxOf(0f, (dh - vh) / 2f)
            panX = panX.coerceIn(-hx, hx)
            panY = panY.coerceIn(-hy, hy)
            val left = (vw - dw) / 2f + panX
            val top = (vh - dh) / 2f + panY
            dst.set(left, top, left + dw, top + dh)
        }

        private fun zoomAt(fx: Float, fy: Float, target: Float) {
            if (width <= 0 || height <= 0) return
            updateDst()
            val nz = target.coerceIn(1f, MAX_ZOOM)
            if (nz == zoom) {
                invalidate()
                return
            }
            val ix = (fx - dst.left) / scale
            val iy = (fy - dst.top) / scale
            zoom = nz
            val ns = baseScale() * zoom
            panX = (fx - ix * ns) - (width - body.width * ns) / 2f
            panY = (fy - iy * ns) - (height - body.height * ns) / 2f
            updateDst()
            onZoom?.invoke(zoom)
            invalidate()
        }

        fun zoomBy(f: Float) {
            zoomAt(width / 2f, height / 2f, zoom * f)
        }

        fun resetZoom() {
            zoom = 1f
            panX = 0f
            panY = 0f
            updateDst()
            onZoom?.invoke(zoom)
            invalidate()
        }

        /** Se estiver com zoom, leva a junta escolhida para o meio da tela. */
        fun centerOn(i: Int) {
            if (zoom <= 1f || width <= 0) return
            updateDst()
            val jx = dst.left + j[i * 2] * dst.width()
            val jy = dst.top + j[i * 2 + 1] * dst.height()
            panX += width / 2f - jx
            panY += height / 2f - jy
            updateDst()
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
            updateDst()
            val vw = width.toFloat()
            val vh = height.toFloat()

            // xadrez só na parte que aparece
            val vl = maxOf(dst.left, 0f)
            val vt = maxOf(dst.top, 0f)
            val vr = minOf(dst.right, vw)
            val vb = minOf(dst.bottom, vh)
            canvas.save()
            canvas.clipRect(vl, vt, vr, vb)
            canvas.drawRect(vl, vt, vr, vb, checkA)
            val cell = 12f * dens
            val r0 = floor((vt - dst.top) / cell).toInt()
            val c0 = floor((vl - dst.left) / cell).toInt()
            var row = r0
            var yy = dst.top + r0 * cell
            while (yy < vb) {
                var col = c0
                var xx = dst.left + c0 * cell
                while (xx < vr) {
                    if (((row + col) and 1) == 1) canvas.drawRect(xx, yy, xx + cell, yy + cell, checkB)
                    xx += cell
                    col++
                }
                yy += cell
                row++
            }
            paint.isFilterBitmap = scale < 3f
            if (hair != null) canvas.drawBitmap(hair, null, dst, paint)
            canvas.drawBitmap(body, null, dst, paint)
            val rg = regions
            if (rg != null) canvas.drawBitmap(rg, null, dst, paint)
            FaceDraw.draw(
                canvas, head, hw, hh, cutout,
                dst.left + faceX * dst.width(), dst.top + faceY * dst.height(), faceW * dst.width(), rect, clip
            )
            canvas.restore()

            // ossos
            for (b in 0 until Rig.BONES) {
                val a = Rig.BONE_A[b]
                val c = Rig.BONE_B[b]
                lineP.color = if (b == Rig.B_TORSO) Color.WHITE else colorOf(c)
                canvas.drawLine(sx(a), sy(a), sx(c), sy(c), lineP)
            }
            // juntas (o tamanho na tela não muda com o zoom)
            for (i in 0 until Rig.JOINTS) {
                dotP.color = colorOf(i)
                canvas.drawCircle(sx(i), sy(i), 5f * dens, dotP)
            }
            canvas.drawCircle(sx(sel), sy(sel), 10f * dens, ringP)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (width <= 0 || height <= 0) return true
            updateDst()
            scaler.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    return true
                }
                MotionEvent.ACTION_DOWN -> multi = false
            }
            if (multi || scaler.isInProgress) {
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) multi = false
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    lastX = e.x
                    lastY = e.y
                    moved = false
                    if (!handMode) {
                        var best = -1
                        var bd = 30f * dens
                        for (i in 0 until Rig.JOINTS) {
                            val dd = hypot(sx(i) - e.x, sy(i) - e.y)
                            if (dd < bd) {
                                bd = dd
                                best = i
                            }
                        }
                        if (best >= 0) select(best)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        lastX = e.x
                        lastY = e.y
                    }
                    if (moved) {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        lastX = e.x
                        lastY = e.y
                        if (handMode) {
                            panX += dx
                            panY += dy
                            updateDst()
                        } else if (dst.width() > 0f && dst.height() > 0f) {
                            // arrasta a junta em passos finos: o que importa é o quanto o dedo andou
                            j[sel * 2] = (j[sel * 2] + dx / dst.width()).coerceIn(0f, 1f)
                            j[sel * 2 + 1] = (j[sel * 2 + 1] + dy / dst.height()).coerceIn(0f, 1f)
                        }
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved && !handMode) onChanged?.invoke()
                }
            }
            return true
        }
    }

    companion object {
        private const val MAX_ZOOM = 10f
        private val BG = 0xFF0B0A15.toInt()
        private val PANEL = 0xFF141229.toInt()
        private val INK = 0xFFEBE9FF.toInt()
        private val DIM = 0xFF9692C8.toInt()
        private val TEAL = 0xFF46F0D4.toInt()
    }
}
