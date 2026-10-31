package app.capri

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Tela de recorte: a varinha apaga o fundo, o pincel apaga à mão. */
class CutoutView(context: Context) : View(context) {

    enum class Tool { WAND, BRUSH }

    var tool: Tool = Tool.WAND

    /** 5 a 90. */
    var tolerance: Int = 30

    var bitmap: Bitmap? = null
        private set

    private var bitmapCanvas: Canvas? = null
    private val undo = ArrayList<IntArray>()
    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val checkA = Paint().apply { color = Color.parseColor("#1B1838") }
    private val checkB = Paint().apply { color = Color.parseColor("#26224A") }
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var brushing = false
    private var lastX = 0f
    private var lastY = 0f

    fun setImage(src: Bitmap) {
        val m = src.copy(Bitmap.Config.ARGB_8888, true)
        bitmap = m
        bitmapCanvas = Canvas(m)
        undo.clear()
        invalidate()
    }

    fun undoLast() {
        val b = bitmap ?: return
        if (undo.isEmpty()) return
        val px = undo.removeAt(undo.size - 1)
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
        invalidate()
    }

    /** Aplica a limpeza final e devolve o boneco cortado. Null se não sobrou nada. */
    fun finish(): Bitmap? {
        val b = bitmap ?: return null
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        ImageOps.clean(px, w, h)
        return ImageOps.trim(px, w, h)
    }

    private fun pushUndo() {
        val b = bitmap ?: return
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        undo.add(px)
        if (undo.size > 8) undo.removeAt(0)
    }

    private fun wand(bx: Int, by: Int): Boolean {
        val b = bitmap ?: return false
        val w = b.width
        val h = b.height
        val x = bx.coerceIn(0, w - 1)
        val y = by.coerceIn(0, h - 1)
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val seed = px[y * w + x]
        if ((seed ushr 24) < 10) return false
        val r0 = (seed shr 16) and 0xFF
        val g0 = (seed shr 8) and 0xFF
        val b0 = seed and 0xFF
        val tol = tolerance * 2.4f
        val tol2 = (tol * tol).toInt()
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        var removed = 0
        stack[sp++] = y * w + x
        seen[y * w + x] = true
        while (sp > 0) {
            val p = stack[--sp]
            val c = px[p]
            if ((c ushr 24) < 10) continue
            val dr = ((c shr 16) and 0xFF) - r0
            val dg = ((c shr 8) and 0xFF) - g0
            val db = (c and 0xFF) - b0
            if (dr * dr + dg * dg + db * db > tol2) continue
            px[p] = 0
            removed++
            val cx = p % w
            if (cx > 0 && !seen[p - 1]) {
                seen[p - 1] = true
                stack[sp++] = p - 1
            }
            if (cx < w - 1 && !seen[p + 1]) {
                seen[p + 1] = true
                stack[sp++] = p + 1
            }
            if (p >= w && !seen[p - w]) {
                seen[p - w] = true
                stack[sp++] = p - w
            }
            if (p < w * (h - 1) && !seen[p + w]) {
                seen[p + w] = true
                stack[sp++] = p + w
            }
        }
        if (removed == 0) return false
        b.setPixels(px, 0, w, 0, 0, w, h)
        return true
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float) {
        val c = bitmapCanvas ?: return
        val b = bitmap ?: return
        erasePaint.strokeWidth = maxOf(8f, b.width * 0.05f)
        c.drawLine(x1, y1, x2, y2, erasePaint)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val s = minOf(vw / b.width, vh / b.height)
        val dw = b.width * s
        val dh = b.height * s
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
                if ((row + col) % 2 == 1) {
                    canvas.drawRect(xx, yy, xx + cell, yy + cell, checkB)
                }
                xx += cell
                col++
            }
            yy += cell
            row++
        }
        canvas.restore()
        canvas.drawBitmap(b, null, dst, bitmapPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bitmap ?: return false
        if (dst.width() <= 0f || dst.height() <= 0f) return true
        val bx = ((e.x - dst.left) * b.width / dst.width()).toInt()
        val by = ((e.y - dst.top) * b.height / dst.height()).toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inside = e.x >= dst.left && e.x <= dst.right && e.y >= dst.top && e.y <= dst.bottom
                if (inside) {
                    pushUndo()
                    if (tool == Tool.WAND) {
                        if (!wand(bx, by)) undo.removeAt(undo.size - 1)
                    } else {
                        brushing = true
                        lastX = bx.toFloat()
                        lastY = by.toFloat()
                        stroke(lastX, lastY, lastX, lastY)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (brushing) {
                    val nx = bx.toFloat()
                    val ny = by.toFloat()
                    stroke(lastX, lastY, nx, ny)
                    lastX = nx
                    lastY = ny
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                brushing = false
            }
        }
        return true
    }
}
