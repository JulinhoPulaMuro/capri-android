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
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Tela de recorte: a varinha apaga o fundo, o pincel e a caixa apagam à mão.
 * Dois dedos dão zoom e movem a imagem; a ferramenta Mão move com um dedo só.
 */
class CutoutView(context: Context) : View(context) {

    enum class Tool { WAND, BRUSH, BOX, PAN }

    var tool: Tool = Tool.WAND

    /** 5 a 90. */
    var tolerance: Int = 30

    /** Tamanho do pincel na tela, em dp (4 a 100). Com zoom, o pincel fica mais fino na imagem. */
    var brushDp: Int = 28

    /** Chamado quando o zoom muda (1.0 = imagem inteira). */
    var onZoomChanged: ((Float) -> Unit)? = null

    var bitmap: Bitmap? = null
        private set

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var scale = 1f
    private var multi = false
    private var active = false
    private var moved = false
    private var started = false
    private var downX = 0f
    private var downY = 0f
    private var panLastX = 0f
    private var panLastY = 0f
    private var curX = 0f
    private var curY = 0f
    private var showCursor = false
    private var previewUntil = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }
    private val cursorPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 4f * context.resources.displayMetrics.density
    }
    private var lastFx = 0f
    private var lastFy = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            lastFx = d.focusX
            lastFy = d.focusY
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            // dois dedos juntos: o ponto entre eles arrasta a imagem e a distância dá o zoom
            panX += d.focusX - lastFx
            panY += d.focusY - lastFy
            lastFx = d.focusX
            lastFy = d.focusY
            zoomAt(d.focusX, d.focusY, zoom * d.scaleFactor)
            return true
        }
    })

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
    private var boxing = false
    private var boxX0 = 0f
    private var boxY0 = 0f
    private var boxX1 = 0f
    private var boxY1 = 0f
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF4F9D")
        strokeWidth = 3f
    }
    private val boxFill = Paint().apply { color = Color.parseColor("#55FF4F9D") }
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
    fun finish(keepSmall: Boolean = false): Bitmap? {
        val b = bitmap ?: return null
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        ImageOps.clean(px, w, h, keepSmall)
        return ImageOps.trim(px, w, h)
    }

    /**
     * Para as camadas do esqueleto: aplica a limpeza mas mantém o tamanho da imagem,
     * para todas as camadas continuarem alinhadas. Null se não sobrou nada.
     */
    fun finishFrame(): Bitmap? {
        val b = bitmap ?: return null
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        ImageOps.clean(px, w, h, true)
        var any = false
        for (v in px) {
            if ((v ushr 24) > 10) {
                any = true
                break
            }
        }
        if (!any) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
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
        erasePaint.strokeWidth = maxOf(1.5f, brushDp * density / maxOf(0.01f, scale))
        c.drawLine(x1, y1, x2, y2, erasePaint)
    }

    // ---------- zoom e posição ----------

    private fun baseScale(b: Bitmap): Float = minOf(width.toFloat() / b.width, height.toFloat() / b.height)

    /** Calcula onde a imagem está na tela, com o zoom e o deslocamento de agora. */
    private fun updateDst() {
        val b = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        scale = baseScale(b) * zoom
        val dw = b.width * scale
        val dh = b.height * scale
        // a imagem sempre cobre a tela quando maior que ela; menor, fica no centro
        val hx = maxOf(0f, (dw - vw) / 2f)
        val hy = maxOf(0f, (dh - vh) / 2f)
        panX = panX.coerceIn(-hx, hx)
        panY = panY.coerceIn(-hy, hy)
        val left = (vw - dw) / 2f + panX
        val top = (vh - dh) / 2f + panY
        dst.set(left, top, left + dw, top + dh)
    }

    private fun zoomAt(fx: Float, fy: Float, target: Float) {
        val b = bitmap ?: return
        if (width <= 0 || height <= 0) return
        updateDst()
        val nz = target.coerceIn(1f, MAX_ZOOM)
        if (nz == zoom) {
            invalidate()
            return
        }
        // ponto da imagem que está debaixo do foco: continua debaixo dele depois do zoom
        val ix = (fx - dst.left) / scale
        val iy = (fy - dst.top) / scale
        zoom = nz
        val ns = baseScale(b) * zoom
        val dw = b.width * ns
        val dh = b.height * ns
        val newLeft = fx - ix * ns
        val newTop = fy - iy * ns
        panX = newLeft - (width - dw) / 2f
        panY = newTop - (height - dh) / 2f
        updateDst()
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    /** Aproxima ou afasta em torno do centro da tela. factor 1.5 = 50% mais perto. */
    fun zoomBy(factor: Float) {
        zoomAt(width / 2f, height / 2f, zoom * factor)
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        updateDst()
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    fun currentZoom(): Float = zoom

    /** Mostra por um instante um círculo do tamanho do pincel no meio da tela. */
    fun showBrushPreview() {
        previewUntil = android.os.SystemClock.uptimeMillis() + 900L
        invalidate()
        postInvalidateDelayed(950L)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        updateDst()
        val vw = width.toFloat()
        val vh = height.toFloat()

        // só desenha o xadrez na parte que aparece (com zoom a imagem é bem maior que a tela)
        val vl = maxOf(dst.left, 0f)
        val vt = maxOf(dst.top, 0f)
        val vr = minOf(dst.right, vw)
        val vb = minOf(dst.bottom, vh)
        canvas.save()
        canvas.clipRect(vl, vt, vr, vb)
        canvas.drawRect(vl, vt, vr, vb, checkA)
        val cell = 12f * density
        val r0 = floor((vt - dst.top) / cell).toInt()
        val c0 = floor((vl - dst.left) / cell).toInt()
        var row = r0
        var yy = dst.top + r0 * cell
        while (yy < vb) {
            var col = c0
            var xx = dst.left + c0 * cell
            while (xx < vr) {
                if (((row + col) and 1) == 1) {
                    canvas.drawRect(xx, yy, xx + cell, yy + cell, checkB)
                }
                xx += cell
                col++
            }
            yy += cell
            row++
        }
        // de perto, mostra os pixels de verdade em vez de borrar
        bitmapPaint.isFilterBitmap = scale < 3f
        canvas.drawBitmap(b, null, dst, bitmapPaint)
        canvas.restore()

        if (boxing) {
            val l = minOf(boxX0, boxX1) * scale + dst.left
            val r = maxOf(boxX0, boxX1) * scale + dst.left
            val t = minOf(boxY0, boxY1) * scale + dst.top
            val bt = maxOf(boxY0, boxY1) * scale + dst.top
            canvas.drawRect(l, t, r, bt, boxFill)
            canvas.drawRect(l, t, r, bt, boxPaint)
        }
        if (tool == Tool.BRUSH) {
            val rad = brushDp * density / 2f
            if (showCursor) {
                canvas.drawCircle(curX, curY, rad, cursorPaint2)
                canvas.drawCircle(curX, curY, rad, cursorPaint)
            } else if (android.os.SystemClock.uptimeMillis() < previewUntil) {
                canvas.drawCircle(vw / 2f, vh / 2f, rad, cursorPaint2)
                canvas.drawCircle(vw / 2f, vh / 2f, rad, cursorPaint)
            }
        }
    }

    private fun eraseBox() {
        val c = bitmapCanvas ?: return
        val b = bitmap ?: return
        val l = minOf(boxX0, boxX1).coerceIn(0f, b.width.toFloat())
        val r = maxOf(boxX0, boxX1).coerceIn(0f, b.width.toFloat())
        val t = minOf(boxY0, boxY1).coerceIn(0f, b.height.toFloat())
        val bt = maxOf(boxY0, boxY1).coerceIn(0f, b.height.toFloat())
        if (r - l < 2f || bt - t < 2f) return
        val p = Paint()
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.drawRect(l, t, r, bt, p)
    }

    /** Desfaz o que o primeiro dedo começou, quando um segundo dedo chega (é um zoom, não um risco). */
    private fun cancelGesture() {
        if (brushing) {
            brushing = false
            undoLast()
        } else if (boxing) {
            boxing = false
            if (undo.isNotEmpty()) undo.removeAt(undo.size - 1)
        }
        started = false
        active = false
        showCursor = false
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bitmap ?: return false
        if (width <= 0 || height <= 0) return true
        updateDst()
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!multi) cancelGesture()
                multi = true
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                multi = false
            }
        }
        if (multi || scaler.isInProgress) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                multi = false
                showCursor = false
                invalidate()
            }
            return true
        }
        val bx = (e.x - dst.left) / scale
        val by = (e.y - dst.top) / scale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = e.x >= dst.left && e.x <= dst.right && e.y >= dst.top && e.y <= dst.bottom
                moved = false
                started = false
                downX = e.x
                downY = e.y
                panLastX = e.x
                panLastY = e.y
                curX = e.x
                curY = e.y
                showCursor = tool == Tool.BRUSH
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                curX = e.x
                curY = e.y
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
                if (tool == Tool.PAN) {
                    panX += e.x - panLastX
                    panY += e.y - panLastY
                    panLastX = e.x
                    panLastY = e.y
                    updateDst()
                } else if (active && moved) {
                    if (tool == Tool.BRUSH) {
                        if (!started) {
                            started = true
                            pushUndo()
                            brushing = true
                            lastX = (downX - dst.left) / scale
                            lastY = (downY - dst.top) / scale
                            stroke(lastX, lastY, lastX, lastY)
                        }
                        stroke(lastX, lastY, bx, by)
                        lastX = bx
                        lastY = by
                    } else if (tool == Tool.BOX) {
                        if (!started) {
                            started = true
                            pushUndo()
                            boxing = true
                            boxX0 = ((downX - dst.left) / scale).coerceIn(0f, b.width.toFloat())
                            boxY0 = ((downY - dst.top) / scale).coerceIn(0f, b.height.toFloat())
                        }
                        boxX1 = bx
                        boxY1 = by
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (active && !moved) {
                    // toque simples
                    if (tool == Tool.WAND) {
                        pushUndo()
                        if (!wand(bx.toInt(), by.toInt())) undo.removeAt(undo.size - 1)
                    } else if (tool == Tool.BRUSH) {
                        pushUndo()
                        stroke(bx, by, bx, by)
                    }
                }
                brushing = false
                if (boxing) {
                    boxing = false
                    boxX1 = bx
                    boxY1 = by
                    eraseBox()
                }
                started = false
                active = false
                showCursor = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (brushing || boxing) cancelGesture()
                showCursor = false
                active = false
                invalidate()
            }
        }
        return true
    }

    companion object {
        private const val MAX_ZOOM = 10f
    }
}
