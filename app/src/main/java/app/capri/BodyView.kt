package app.capri

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A figura da Capri: a imagem da pessoa (cabeça ou corpo inteiro) mais um corpinho desenhado,
 * que se mexe. Os corpos são desenhos simples e originais, só formas geométricas.
 */
class BodyView(context: Context) : View(context) {

    enum class Pose { IDLE, WAVE, DANCE, HOP, SPIN, STRETCH, LEAN, WALK, TALK, THINK, LISTEN }

    /** 0 sem corpo, 1 fofo, 2 esguio, 3 robô, 4 imagem de corpo e cabelo escolhida pela pessoa. */
    var bodyType: Int = 0

    var bodyColor: Int = 0xFF46F0D4.toInt()

    /** True: imagem recortada (cabe inteira). False: foto com cantos arredondados. */
    var cutout: Boolean = true

    /** Posição do rosto sobre a imagem do corpo (frações da largura e da altura). */
    var faceX = 0.5f
    var faceY = 0.3f
    var faceW = 0.2f

    private var sprite: Bitmap? = null
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private var head: Drawable? = null
    private var headW = 1
    private var headH = 1
    private var pose = Pose.IDLE
    private var poseStart = SystemClock.uptimeMillis()
    private var poseUntil = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val clip = Path()

    /** Valores calculados a cada quadro. */
    private class St {
        var bob = 0f
        var sway = 0f
        var sx = 1f
        var sy = 1f
        var armL = -0.25f
        var armR = 0.25f
        var legL = -0.06f
        var legR = 0.06f
        var tilt = 0f
    }

    private class Shape(
        val headBottom: Float,
        val torsoTop: Float,
        val torsoBottom: Float,
        val torsoW: Float,
        val shoulderY: Float,
        val shoulderDx: Float,
        val armLen: Float,
        val armW: Float,
        val hipDx: Float,
        val legLen: Float,
        val legW: Float,
        val round: Float,
        val square: Boolean
    )

    private val chibi = Shape(0.60f, 0.58f, 0.80f, 0.30f, 0.62f, 0.16f, 0.15f, 0.09f, 0.07f, 0.17f, 0.10f, 0.09f, false)
    private val slim = Shape(0.52f, 0.50f, 0.72f, 0.22f, 0.54f, 0.12f, 0.22f, 0.05f, 0.05f, 0.27f, 0.06f, 0.06f, false)
    private val robot = Shape(0.60f, 0.58f, 0.80f, 0.34f, 0.62f, 0.19f, 0.15f, 0.08f, 0.08f, 0.17f, 0.09f, 0.015f, true)

    fun setHead(bmp: Bitmap?, fallback: Drawable?) {
        if (bmp != null) {
            val d = BitmapDrawable(resources, bmp)
            d.isFilterBitmap = true
            head = d
            headW = bmp.width
            headH = bmp.height
        } else {
            head = fallback
            headW = max(1, fallback?.intrinsicWidth ?: 1)
            headH = max(1, fallback?.intrinsicHeight ?: 1)
        }
        invalidate()
    }

    fun setSprite(b: Bitmap?) {
        sprite = b
        invalidate()
    }

    /** Tipo de corpo que vale agora: sem imagem de corpo, o tipo 4 cai para o fofo. */
    fun effType(): Int = if (bodyType == 4 && sprite == null) 1 else bodyType

    /** Tamanho da figura em dp, antes da escala escolhida. */
    fun boxDp(): Pair<Int, Int> {
        val sp = sprite
        if (effType() == 4 && sp != null) {
            val h = 190
            val w = (h * sp.width / sp.height.toFloat()).coerceIn(90f, 190f).toInt()
            return Pair(w, h)
        }
        return if (effType() == 0) Pair(110, 140) else Pair(120, 185)
    }

    fun setPose(p: Pose, durationMs: Long = 0L) {
        pose = p
        poseStart = SystemClock.uptimeMillis()
        poseUntil = if (durationMs > 0) poseStart + durationMs else 0L
        invalidate()
    }

    fun currentPose(): Pose = pose

    private fun compute(s: St, p: Pose, t: Float) {
        val pi = PI.toFloat()
        when (p) {
            Pose.IDLE -> {
                val w = sin(t * 2.2f)
                s.bob = w * 1.5f
                s.sway = sin(t * 1.1f) * 1.5f
                s.sy = 1f + w * 0.012f
                s.armL = -0.25f - w * 0.06f
                s.armR = 0.25f + w * 0.06f
            }
            Pose.WAVE -> {
                s.bob = sin(t * 3f)
                s.armR = 2.5f + sin(t * 11f) * 0.35f
                s.armL = -0.2f
                s.tilt = 6f + sin(t * 5f) * 2f
            }
            Pose.DANCE -> {
                val w = sin(t * 7f)
                s.bob = -abs(w) * 8f
                s.sway = w * 9f
                s.armL = -1.4f + w * 0.8f
                s.armR = 1.4f - w * 0.8f
                s.legL = -0.2f + w * 0.35f
                s.legR = 0.2f + w * 0.35f
                s.tilt = w * 8f
            }
            Pose.HOP -> {
                val ph = (t * 1.6f) % 1f
                val up = sin(ph * pi)
                s.bob = -up * 22f
                if (ph < 0.15f || ph > 0.85f) {
                    s.sy = 0.9f
                    s.sx = 1.08f
                }
                s.armL = -0.25f - 2.2f * up
                s.armR = 0.25f + 2.2f * up
                s.legL = -0.1f - 0.2f * up
                s.legR = 0.1f + 0.2f * up
            }
            Pose.SPIN -> {
                s.sx = cos(t * 9f)
                s.bob = -abs(sin(t * 9f)) * 4f
                s.armL = -0.9f
                s.armR = 0.9f
            }
            Pose.STRETCH -> {
                val ph = min(1f, t * 3f)
                s.armL = -0.25f - 2.6f * ph
                s.armR = 0.25f + 2.6f * ph
                s.sy = 1f + 0.05f * ph
                s.bob = -3f * ph
                s.sway = sin(t * 6f) * ph
            }
            Pose.LEAN -> {
                val ph = min(1f, t * 3f)
                s.sway = 10f * ph
                s.tilt = -8f * ph
                s.armR = 0.6f
            }
            Pose.WALK -> {
                val w = sin(t * 10f)
                s.bob = -abs(w) * 3f
                s.legL = w * 0.5f
                s.legR = -w * 0.5f
                s.armL = -0.25f + w * 0.4f
                s.armR = 0.25f + w * 0.4f
                s.sway = w * 2f
            }
            Pose.TALK -> {
                val w = abs(sin(t * 9f))
                s.bob = -w * 3f
                s.sway = sin(t * 4.5f) * 2f
                s.armR = 0.5f + sin(t * 4.5f) * 0.35f
                s.armL = -0.4f + sin(t * 3f) * 0.25f
                s.tilt = sin(t * 4.5f) * 3f
            }
            Pose.THINK -> {
                s.tilt = 12f
                s.sway = sin(t * 1.5f) * 3f
                s.bob = sin(t * 2f)
                s.armR = 2.2f
                s.armL = -0.2f
            }
            Pose.LISTEN -> {
                s.sway = 5f
                s.tilt = 8f + sin(t * 3f) * 2f
                s.bob = sin(t * 3f)
                s.sy = 1.02f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (poseUntil != 0L && now > poseUntil) {
            pose = Pose.IDLE
            poseStart = now
            poseUntil = 0L
        }
        val t = (now - poseStart) / 1000f
        val s = St()
        compute(s, pose, t)
        val dp = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val type = effType()
        if (type == 4) spriteExtra(s, pose, t)
        val shape: Shape? = when (type) {
            1 -> chibi
            2 -> slim
            3 -> robot
            else -> null
        }

        canvas.save()
        canvas.translate(w / 2f, h)
        canvas.rotate(s.sway)
        canvas.scale(s.sx, s.sy)
        canvas.translate(-w / 2f, -h + s.bob * dp)

        if (type == 4) {
            drawSprite(canvas, s, w, h)
        } else if (shape != null) {
            drawBody(canvas, shape, s, w, h)
            drawHead(canvas, 0.06f * w, 0f, 0.94f * w, shape.headBottom * h, s.tilt)
        } else {
            drawHead(canvas, 0f, 0f, w, h, s.tilt)
        }
        canvas.restore()

        postInvalidateDelayed(33)
    }

    /** Na imagem inteira só dá para mexer a figura toda, então reforça alguns gestos. */
    private fun spriteExtra(s: St, p: Pose, t: Float) {
        when (p) {
            Pose.WAVE -> {
                s.sway += sin(t * 9f) * 4f
                s.bob -= abs(sin(t * 9f)) * 3f
            }
            Pose.TALK -> s.sy += sin(t * 9f) * 0.02f
            Pose.STRETCH -> s.sy += 0.04f * min(1f, t * 3f)
            else -> {}
        }
    }

    private fun drawSprite(canvas: Canvas, s: St, w: Float, h: Float) {
        val b = sprite ?: return
        val sc = min(w / b.width, h / b.height)
        val dw = b.width * sc
        val dh = b.height * sc
        val l = (w - dw) / 2f
        val t = h - dh
        rect.set(l, t, l + dw, t + dh)
        canvas.drawBitmap(b, null, rect, spritePaint)
        val d = head ?: return
        val cx = l + faceX * dw
        val cy = t + faceY * dh
        canvas.save()
        canvas.rotate(s.tilt, cx, cy)
        FaceDraw.draw(canvas, d, headW, headH, cutout, cx, cy, faceW * dw, rect, clip)
        canvas.restore()
    }

    private fun drawHead(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, tilt: Float) {
        val d = head ?: return
        val rw = r - l
        val rh = b - t
        canvas.save()
        canvas.rotate(tilt, (l + r) / 2f, b)
        if (cutout) {
            val sc = min(rw / headW, rh / headH)
            val dw = headW * sc
            val dh = headH * sc
            val x = l + (rw - dw) / 2f
            val y = b - dh
            d.setBounds(x.toInt(), y.toInt(), (x + dw).toInt(), (y + dh).toInt())
            d.draw(canvas)
        } else {
            val sc = max(rw / headW, rh / headH)
            val dw = headW * sc
            val dh = headH * sc
            val x = l + (rw - dw) / 2f
            val y = t + (rh - dh) / 2f
            rect.set(l, t, r, b)
            clip.reset()
            val rad = 14f * resources.displayMetrics.density
            clip.addRoundRect(rect, rad, rad, Path.Direction.CW)
            canvas.clipPath(clip)
            d.setBounds(x.toInt(), y.toInt(), (x + dw).toInt(), (y + dh).toInt())
            d.draw(canvas)
        }
        canvas.restore()
    }

    private fun dark(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2] * 0.45f
        return Color.HSVToColor(hsv)
    }

    private fun limb(canvas: Canvas, x: Float, y: Float, angle: Float, len: Float, w: Float, square: Boolean, outline: Float) {
        val ex = x + sin(angle) * len
        val ey = y + cos(angle) * len
        line.strokeCap = if (square) Paint.Cap.SQUARE else Paint.Cap.ROUND
        line.color = dark(bodyColor)
        line.strokeWidth = w + outline * 2f
        canvas.drawLine(x, y, ex, ey, line)
        line.color = bodyColor
        line.strokeWidth = w
        canvas.drawLine(x, y, ex, ey, line)
        // mão ou pé
        fill.color = dark(bodyColor)
        canvas.drawCircle(ex, ey, w * 0.62f + outline, fill)
        fill.color = bodyColor
        canvas.drawCircle(ex, ey, w * 0.62f, fill)
    }

    private fun drawBody(canvas: Canvas, sp: Shape, s: St, w: Float, h: Float) {
        val outline = 0.012f * w
        val cx = w / 2f
        val sq = sp.square

        // pernas e braços atrás do tronco
        limb(canvas, cx - sp.hipDx * w, sp.torsoBottom * h - 0.01f * h, s.legL, sp.legLen * h, sp.legW * w, sq, outline)
        limb(canvas, cx + sp.hipDx * w, sp.torsoBottom * h - 0.01f * h, s.legR, sp.legLen * h, sp.legW * w, sq, outline)
        limb(canvas, cx - sp.shoulderDx * w, sp.shoulderY * h, s.armL, sp.armLen * h, sp.armW * w, sq, outline)
        limb(canvas, cx + sp.shoulderDx * w, sp.shoulderY * h, s.armR, sp.armLen * h, sp.armW * w, sq, outline)

        // tronco
        val tw = sp.torsoW * w
        rect.set(cx - tw / 2f, sp.torsoTop * h, cx + tw / 2f, sp.torsoBottom * h)
        val rad = sp.round * w
        fill.color = dark(bodyColor)
        rect.inset(-outline, -outline)
        canvas.drawRoundRect(rect, rad + outline, rad + outline, fill)
        rect.inset(outline, outline)
        fill.color = bodyColor
        canvas.drawRoundRect(rect, rad, rad, fill)

        // detalhes
        fill.color = dark(bodyColor)
        if (sq) {
            val pw = tw * 0.42f
            val ph = (sp.torsoBottom - sp.torsoTop) * h * 0.35f
            rect.set(cx - pw / 2f, sp.torsoTop * h + ph * 0.5f, cx + pw / 2f, sp.torsoTop * h + ph * 1.5f)
            canvas.drawRoundRect(rect, 3f, 3f, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(cx - pw * 0.22f, rect.centerY(), pw * 0.1f, fill)
            canvas.drawCircle(cx + pw * 0.22f, rect.centerY(), pw * 0.1f, fill)
        } else {
            canvas.drawCircle(cx, (sp.torsoTop + (sp.torsoBottom - sp.torsoTop) * 0.45f) * h, tw * 0.07f, fill)
        }
    }
}

/** Desenha o rosto da pessoa sobre a imagem do corpo. Usado pelo boneco e pela tela de ajuste. */
object FaceDraw {
    fun draw(
        canvas: Canvas, d: Drawable, hw: Int, hh: Int, cutout: Boolean,
        cx: Float, cy: Float, fw: Float, rect: RectF, clip: Path
    ) {
        val iw = max(1, hw)
        val ih = max(1, hh)
        if (cutout) {
            val fh = fw * ih / iw
            d.setBounds(
                (cx - fw / 2f).toInt(), (cy - fh / 2f).toInt(),
                (cx + fw / 2f).toInt(), (cy + fh / 2f).toInt()
            )
            d.draw(canvas)
        } else {
            val half = fw / 2f
            rect.set(cx - half, cy - half, cx + half, cy + half)
            clip.reset()
            clip.addRoundRect(rect, half, half, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val sc = max(fw / iw, fw / ih)
            val dw = iw * sc
            val dh = ih * sc
            d.setBounds(
                (cx - dw / 2f).toInt(), (cy - dh / 2f).toInt(),
                (cx + dw / 2f).toInt(), (cy + dh / 2f).toInt()
            )
            d.draw(canvas)
            canvas.restore()
        }
    }
}
