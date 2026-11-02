package app.capri

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Desenha o corpo articulado: o cabelo (camada de trás, gira junto com a cabeça),
 * o corpo (imagem dobrada pelos ossos) e o rosto (preso ao pescoço).
 * As camadas vêm da mesma imagem, então dividem as mesmas coordenadas.
 */
class RigRenderer {

    private var hair: Bitmap? = null
    private var hairX = 0
    private var hairY = 0
    private var body: Bitmap? = null
    private var bodyX = 0
    private var bodyY = 0

    var srcW = 1
        private set
    var srcH = 1
        private set

    /** Juntas em pixels da imagem. */
    private var joints = FloatArray(Rig.JOINTS * 2)

    // quadro visível: caixa que contém cabelo e corpo, com folga para os movimentos
    private var fx0 = 0f
    private var fy0 = 0f
    private var fx1 = 1f
    private var fy1 = 1f
    private var feetY = 0f

    private var mw = 1
    private var mh = 1
    private var nv = 0
    private var rx = FloatArray(0)
    private var ry = FloatArray(0)
    private var idx = IntArray(0)
    private var wt = FloatArray(0)
    private var out = FloatArray(0)

    private val bones = FloatArray(Rig.BONES * 6)
    private val headM = FloatArray(6)
    private val hairM = FloatArray(6)
    private val tmp = FloatArray(6)
    private val matrix = Matrix()
    private val m9 = FloatArray(9)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Largura dividida pela altura do quadro visível. */
    fun frameAspect(): Float = (fx1 - fx0) / max(1f, fy1 - fy0)

    /** Quanto da altura do quadro fica abaixo da sola dos pés (0 a 1). */
    fun feetFrac(): Float = ((fy1 - feetY) / max(1f, fy1 - fy0)).coerceIn(0f, 0.5f)

    /** Quanto da altura do quadro fica abaixo do quadril (0 a 1). */
    fun pelvisFrac(): Float = ((fy1 - joints[Rig.PELVIS * 2 + 1]) / max(1f, fy1 - fy0)).coerceIn(0f, 0.9f)

    fun jointsFrac(): FloatArray {
        val o = FloatArray(Rig.JOINTS * 2)
        for (i in 0 until Rig.JOINTS) {
            o[i * 2] = joints[i * 2] / srcW
            o[i * 2 + 1] = joints[i * 2 + 1] / srcH
        }
        return o
    }

    /** Caixa onde há pixels visíveis: x0, y0, x1, y1 (pixels). Null se a imagem está vazia. */
    private fun alphaBox(b: Bitmap): IntArray? {
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
        return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }

    private fun crop(b: Bitmap, box: IntArray): Bitmap =
        Bitmap.createBitmap(b, box[0], box[1], box[2] - box[0], box[3] - box[1])

    /**
     * Prepara as camadas. jointsFrac: 36 números (frações da imagem) ou null para a posição padrão.
     * Devolve false se o corpo está vazio.
     */
    fun set(hairFull: Bitmap?, bodyFull: Bitmap, jointsFrac: FloatArray?): Boolean {
        srcW = bodyFull.width
        srcH = bodyFull.height
        val bb = alphaBox(bodyFull) ?: return false
        body = crop(bodyFull, bb)
        bodyX = bb[0]
        bodyY = bb[1]
        var ux0 = bb[0]
        var uy0 = bb[1]
        var ux1 = bb[2]
        var uy1 = bb[3]
        hair = null
        if (hairFull != null) {
            val hb = alphaBox(hairFull)
            if (hb != null) {
                hair = crop(hairFull, hb)
                hairX = hb[0]
                hairY = hb[1]
                ux0 = min(ux0, hb[0]); uy0 = min(uy0, hb[1]); ux1 = max(ux1, hb[2]); uy1 = max(uy1, hb[3])
            }
        }
        val uw = (ux1 - ux0).toFloat()
        val uh = (uy1 - uy0).toFloat()
        fx0 = ux0 - 0.14f * uw
        fx1 = ux1 + 0.14f * uw
        fy0 = uy0 - 0.12f * uh
        fy1 = uy1 + 0.04f * uh

        joints = FloatArray(Rig.JOINTS * 2)
        val jf = jointsFrac ?: Rig.defaults(
            bb[0].toFloat() / srcW, bb[1].toFloat() / srcH, bb[2].toFloat() / srcW, bb[3].toFloat() / srcH
        )
        for (i in 0 until Rig.JOINTS) {
            joints[i * 2] = jf[i * 2] * srcW
            joints[i * 2 + 1] = jf[i * 2 + 1] * srcH
        }
        feetY = bb[3].toFloat()

        // malha do corpo
        val bw = bb[2] - bb[0]
        val bh = bb[3] - bb[1]
        mw = (bw / 7).coerceIn(6, 64)
        mh = (bh / 7).coerceIn(6, 80)
        nv = (mw + 1) * (mh + 1)
        rx = FloatArray(nv)
        ry = FloatArray(nv)
        idx = IntArray(nv * 3)
        wt = FloatArray(nv * 3)
        out = FloatArray(nv * 2)
        var k = 0
        for (j in 0..mh) {
            for (i in 0..mw) {
                rx[k] = bodyX + bw * i / mw.toFloat()
                ry[k] = bodyY + bh * j / mh.toFloat()
                k++
            }
        }
        Rig.skin(rx, ry, nv, joints, hypot(srcW.toFloat(), srcH.toFloat()), idx, wt)
        return true
    }

    /**
     * Desenha na área w x h, encostado embaixo. hairAng é o balanço extra do cabelo.
     * O rosto (d) fica preso ao pescoço, na posição faceX, faceY e largura faceW (frações da imagem).
     */
    fun draw(
        canvas: Canvas, w: Float, h: Float, pose: RigPose, hairAng: Float,
        d: Drawable?, hw: Int, hh: Int, cutout: Boolean,
        faceX: Float, faceY: Float, faceW: Float, rect: RectF, clip: Path
    ) {
        val b = body ?: return
        val fw = fx1 - fx0
        val fh = fy1 - fy0
        val sc = min(w / fw, h / fh)
        canvas.save()
        canvas.translate((w - fw * sc) / 2f - fx0 * sc, h - fh * sc - fy0 * sc)
        canvas.scale(sc, sc)

        Rig.matrices(joints, pose, fh, bones, headM)
        // o cabelo acompanha a cabeça e balança em volta do pescoço
        Rig.rotAbout(hairAng, joints[Rig.NECK * 2], joints[Rig.NECK * 2 + 1], tmp)
        Rig.mul(headM, tmp, hairM)

        val hr = hair
        if (hr != null) {
            canvas.save()
            applyMatrix(canvas, hairM)
            canvas.drawBitmap(hr, hairX.toFloat(), hairY.toFloat(), paint)
            canvas.restore()
        }

        Rig.deform(rx, ry, nv, idx, wt, bones, out)
        canvas.drawBitmapMesh(b, mw, mh, out, 0, null, 0, paint)

        if (d != null) {
            canvas.save()
            applyMatrix(canvas, headM)
            FaceDraw.draw(
                canvas, d, hw, hh, cutout,
                faceX * srcW, faceY * srcH, faceW * srcW, rect, clip
            )
            canvas.restore()
        }
        canvas.restore()
    }

    private fun applyMatrix(canvas: Canvas, m: FloatArray) {
        m9[0] = m[0]; m9[1] = m[1]; m9[2] = m[2]
        m9[3] = m[3]; m9[4] = m[4]; m9[5] = m[5]
        m9[6] = 0f; m9[7] = 0f; m9[8] = 1f
        matrix.setValues(m9)
        canvas.concat(matrix)
    }
}
