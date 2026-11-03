package app.capri

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.SystemClock
import android.view.View
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * O efeito de falha de sinal: as cores se separam (magenta e ciano), faixas horizontais escorregam
 * para o lado e, de vez em quando, a imagem pisca. Tudo desenhado a partir de uma imagem pronta.
 */
object Glitch {

    private val magenta = PorterDuffColorFilter(0xFFFF2BD6.toInt(), PorterDuff.Mode.SRC_IN)
    private val cyan = PorterDuffColorFilter(0xFF2AF0FF.toInt(), PorterDuff.Mode.SRC_IN)

    /**
     * Desenha bmp com o canto de cima em (x, y). g vai de 0 a 1 (força da falha), seed muda o desenho
     * (use um valor que muda alguns poucos quadros por segundo para ficar travado, de vídeo ruim),
     * alpha de 0 a 1 deixa tudo mais transparente.
     */
    fun draw(c: Canvas, bmp: Bitmap, x: Float, y: Float, g: Float, seed: Long, alpha: Float, paint: Paint) {
        val rnd = Random(seed)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val a255 = (alpha * 255f).toInt().coerceIn(0, 255)
        if (a255 <= 0) return

        // as cores se separam
        val off = (0.012f + 0.05f * g) * w * (0.5f + rnd.nextFloat())
        paint.alpha = (a255 * 0.6f).toInt()
        paint.colorFilter = magenta
        c.drawBitmap(bmp, x - off, y + (rnd.nextFloat() - 0.5f) * 0.01f * h, paint)
        paint.colorFilter = cyan
        c.drawBitmap(bmp, x + off, y, paint)
        paint.colorFilter = null
        paint.alpha = a255

        // às vezes o quadro principal some (só ficam as cores)
        if (g > 0.35f && rnd.nextFloat() < 0.12f * g) return

        // faixas que escorregam para o lado
        var y0 = 0f
        while (y0 < h) {
            val y1 = min(h, y0 + h * (0.03f + rnd.nextFloat() * 0.13f))
            val moves = rnd.nextFloat() < 0.1f + 0.5f * g
            val shift = if (moves) (rnd.nextFloat() * 2f - 1f) * (0.03f + 0.12f * g) * w else 0f
            c.save()
            c.clipRect(x, y + y0, x + w, y + y1)
            c.drawBitmap(bmp, x + shift, y, paint)
            c.restore()
            y0 = y1
        }
    }
}

/**
 * Rastro do teleporte: algumas cópias da figura ao longo do caminho entre o lugar de onde ela sumiu
 * e o lugar onde apareceu, cada uma piscando e apagando. Fica numa janela só dele e some sozinho.
 */
class GhostView(
    context: Context,
    private val bmp: Bitmap,
    private val ax: Float,
    private val ay: Float,
    private val bx: Float,
    private val by: Float
) : View(context) {

    var onDone: (() -> Unit)? = null
    private val t0 = SystemClock.uptimeMillis()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var finished = false

    override fun onDraw(canvas: Canvas) {
        val age = SystemClock.uptimeMillis() - t0
        val n = 5
        var any = false
        for (i in 0 until n) {
            val a = age - i * 55L
            if (a < 0L) {
                any = true
                continue
            }
            if (a > LIFE) continue
            any = true
            val k = a.toFloat() / LIFE.toFloat()
            val f = i.toFloat() / max(1, n - 1).toFloat()
            val x = ax + (bx - ax) * f
            val y = ay + (by - ay) * f
            val seed = (a / 60L) * 131L + i * 17L
            Glitch.draw(canvas, bmp, x, y, 0.9f - 0.5f * k, seed, (1f - k) * (0.75f - 0.4f * f), paint)
        }
        if (any) {
            postInvalidateOnAnimation()
        } else if (!finished) {
            finished = true
            post { onDone?.invoke() }
        }
    }

    companion object {
        const val LIFE = 520L
    }
}
