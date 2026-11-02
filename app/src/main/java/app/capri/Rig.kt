package app.capri

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Esqueleto 2D: 18 juntas colocadas pela pessoa sobre a imagem do corpo, 13 ossos,
 * pesos para dobrar a imagem junto com os ossos e a matemática de cada pose.
 * Não depende do Android, para poder ser testado no computador.
 *
 * Convenções: x para a direita, y para baixo. Ângulo positivo gira no sentido horário na tela.
 * Os ângulos dos membros são "para fora": positivo afasta o membro do corpo.
 * "E" é o lado esquerdo da tela e "D" o direito.
 */
object Rig {
    const val NECK = 0
    const val PELVIS = 1
    const val SHO_L = 2
    const val ELB_L = 3
    const val WRI_L = 4
    const val HAND_L = 5
    const val SHO_R = 6
    const val ELB_R = 7
    const val WRI_R = 8
    const val HAND_R = 9
    const val HIP_L = 10
    const val KNEE_L = 11
    const val ANK_L = 12
    const val TOE_L = 13
    const val HIP_R = 14
    const val KNEE_R = 15
    const val ANK_R = 16
    const val TOE_R = 17
    const val JOINTS = 18

    val NAMES = arrayOf(
        "Pescoço (onde o rosto se apoia)", "Quadril (centro do corpo)",
        "Ombro E", "Cotovelo E", "Pulso E", "Ponta da mão E",
        "Ombro D", "Cotovelo D", "Pulso D", "Ponta da mão D",
        "Quadril E (onde a perna começa)", "Joelho E", "Tornozelo E", "Ponta do pé E",
        "Quadril D (onde a perna começa)", "Joelho D", "Tornozelo D", "Ponta do pé D"
    )

    const val B_TORSO = 0
    const val B_UA_L = 1
    const val B_FA_L = 2
    const val B_HA_L = 3
    const val B_UA_R = 4
    const val B_FA_R = 5
    const val B_HA_R = 6
    const val B_TH_L = 7
    const val B_SH_L = 8
    const val B_FT_L = 9
    const val B_TH_R = 10
    const val B_SH_R = 11
    const val B_FT_R = 12
    const val BONES = 13

    /** Cada osso vai da junta A até a junta B. */
    val BONE_A = intArrayOf(PELVIS, SHO_L, ELB_L, WRI_L, SHO_R, ELB_R, WRI_R, HIP_L, KNEE_L, ANK_L, HIP_R, KNEE_R, ANK_R)
    val BONE_B = intArrayOf(NECK, ELB_L, WRI_L, HAND_L, ELB_R, WRI_R, HAND_R, KNEE_L, ANK_L, TOE_L, KNEE_R, ANK_R, TOE_R)

    /** Posições iniciais (frações de largura e altura da imagem) a partir da caixa onde está o corpo. */
    fun defaults(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val w = x1 - x0
        val h = y1 - y0
        val cx = (x0 + x1) / 2f
        val j = FloatArray(JOINTS * 2)
        fun put(i: Int, fx: Float, fy: Float) {
            j[i * 2] = cx + fx * w
            j[i * 2 + 1] = y0 + fy * h
        }
        put(NECK, 0f, 0.06f)
        put(PELVIS, 0f, 0.50f)
        put(SHO_L, -0.12f, 0.12f); put(ELB_L, -0.20f, 0.30f); put(WRI_L, -0.24f, 0.46f); put(HAND_L, -0.26f, 0.54f)
        put(SHO_R, 0.12f, 0.12f); put(ELB_R, 0.20f, 0.30f); put(WRI_R, 0.24f, 0.46f); put(HAND_R, 0.26f, 0.54f)
        put(HIP_L, -0.07f, 0.52f); put(KNEE_L, -0.08f, 0.74f); put(ANK_L, -0.09f, 0.93f); put(TOE_L, -0.10f, 0.99f)
        put(HIP_R, 0.07f, 0.52f); put(KNEE_R, 0.08f, 0.74f); put(ANK_R, 0.09f, 0.93f); put(TOE_R, 0.10f, 0.99f)
        return j
    }

    fun encode(j: FloatArray): String = j.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }

    fun decode(s: String): FloatArray? {
        if (s.isBlank()) return null
        val parts = s.split(",")
        if (parts.size != JOINTS * 2) return null
        val out = FloatArray(JOINTS * 2)
        for (i in parts.indices) out[i] = parts[i].trim().toFloatOrNull() ?: return null
        return out
    }

    // ---------- matrizes 2D: [a, b, tx, c, d, ty], x' = a x + b y + tx, y' = c x + d y + ty ----------

    fun identity(m: FloatArray) {
        m[0] = 1f; m[1] = 0f; m[2] = 0f
        m[3] = 0f; m[4] = 1f; m[5] = 0f
    }

    /** out = m1 depois m2 aplicado primeiro (out = m1 * m2). out pode ser m1. */
    fun mul(m1: FloatArray, m2: FloatArray, out: FloatArray) {
        val a = m1[0] * m2[0] + m1[1] * m2[3]
        val b = m1[0] * m2[1] + m1[1] * m2[4]
        val tx = m1[0] * m2[2] + m1[1] * m2[5] + m1[2]
        val c = m1[3] * m2[0] + m1[4] * m2[3]
        val d = m1[3] * m2[1] + m1[4] * m2[4]
        val ty = m1[3] * m2[2] + m1[4] * m2[5] + m1[5]
        out[0] = a; out[1] = b; out[2] = tx
        out[3] = c; out[4] = d; out[5] = ty
    }

    fun rotAbout(angle: Float, px: Float, py: Float, out: FloatArray) {
        val c = cos(angle)
        val s = sin(angle)
        out[0] = c; out[1] = -s; out[2] = px - c * px + s * py
        out[3] = s; out[4] = c; out[5] = py - s * px - c * py
    }

    fun scaleAbout(sx: Float, sy: Float, px: Float, py: Float, out: FloatArray) {
        out[0] = sx; out[1] = 0f; out[2] = px - sx * px
        out[3] = 0f; out[4] = sy; out[5] = py - sy * py
    }

    fun translate(dx: Float, dy: Float, out: FloatArray) {
        out[0] = 1f; out[1] = 0f; out[2] = dx
        out[3] = 0f; out[4] = 1f; out[5] = dy
    }

    /**
     * Calcula as matrizes de cada osso e da cabeça para a pose dada.
     * j: juntas em pixels da imagem (36 números). bones: BONES*6. head: 6.
     * frameH: altura do quadro em pixels (os deslocamentos da pose são frações dela).
     */
    fun matrices(j: FloatArray, p: RigPose, frameH: Float, bones: FloatArray, head: FloatArray) {
        val t1 = FloatArray(6)
        val t2 = FloatArray(6)
        val root = FloatArray(6)
        val torso = FloatArray(6)
        val chain = FloatArray(6)
        val chain2 = FloatArray(6)
        fun jx(i: Int) = j[i * 2]
        fun jy(i: Int) = j[i * 2 + 1]

        // raiz: desloca, gira em volta do quadril e amassa com os pés no chão
        val footY = max(max(jy(TOE_L), jy(TOE_R)), max(jy(ANK_L), jy(ANK_R)))
        scaleAbout(p.sx, p.sy, jx(PELVIS), footY, t1)
        rotAbout(p.rot, jx(PELVIS), jy(PELVIS), t2)
        mul(t2, t1, root)
        translate(p.dx * frameH, p.dy * frameH, t1)
        mul(t1, root, root)

        // tronco
        rotAbout(p.tor, jx(PELVIS), jy(PELVIS), t1)
        mul(root, t1, torso)
        System.arraycopy(torso, 0, bones, B_TORSO * 6, 6)

        // cabeça (rosto e cabelo)
        rotAbout(p.head, jx(NECK), jy(NECK), t1)
        mul(torso, t1, head)

        val sgn = floatArrayOf(1f, -1f)
        for (s in 0..1) {
            val sho = if (s == 0) SHO_L else SHO_R
            val elb = if (s == 0) ELB_L else ELB_R
            val wri = if (s == 0) WRI_L else WRI_R
            val bu = if (s == 0) B_UA_L else B_UA_R
            rotAbout(sgn[s] * p.ua[s], jx(sho), jy(sho), t1)
            mul(torso, t1, chain)
            System.arraycopy(chain, 0, bones, bu * 6, 6)
            rotAbout(sgn[s] * p.fa[s], jx(elb), jy(elb), t1)
            mul(chain, t1, chain2)
            System.arraycopy(chain2, 0, bones, (bu + 1) * 6, 6)
            rotAbout(sgn[s] * p.ha[s], jx(wri), jy(wri), t1)
            mul(chain2, t1, chain)
            System.arraycopy(chain, 0, bones, (bu + 2) * 6, 6)

            val hip = if (s == 0) HIP_L else HIP_R
            val knee = if (s == 0) KNEE_L else KNEE_R
            val ank = if (s == 0) ANK_L else ANK_R
            val bt = if (s == 0) B_TH_L else B_TH_R
            rotAbout(sgn[s] * p.th[s], jx(hip), jy(hip), t1)
            mul(root, t1, chain)
            System.arraycopy(chain, 0, bones, bt * 6, 6)
            rotAbout(sgn[s] * p.sh[s], jx(knee), jy(knee), t1)
            mul(chain, t1, chain2)
            System.arraycopy(chain2, 0, bones, (bt + 1) * 6, 6)
            rotAbout(sgn[s] * p.ft[s], jx(ank), jy(ank), t1)
            mul(chain2, t1, chain)
            System.arraycopy(chain, 0, bones, (bt + 2) * 6, 6)
        }
    }

    // ---------- pesos: quanto cada osso arrasta cada ponto da imagem ----------

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        var t = if (len2 < 1e-6f) 0f else ((px - ax) * vx + (py - ay) * vy) / len2
        t = min(1f, max(0f, t))
        return hypot(px - (ax + t * vx), py - (ay + t * vy))
    }

    /** Raio de influência de cada osso, em pixels. Dentro dele o osso manda sozinho. */
    fun radii(j: FloatArray, diag: Float): FloatArray {
        fun d(a: Int, b: Int) = hypot(j[a * 2] - j[b * 2], j[a * 2 + 1] - j[b * 2 + 1])
        val shoulders = d(SHO_L, SHO_R)
        val hips = d(HIP_L, HIP_R)
        val minR = 0.004f * diag
        val r = FloatArray(BONES)
        r[B_TORSO] = max(minR, 0.42f * shoulders)
        for (b in 1 until BONES) {
            val len = d(BONE_A[b], BONE_B[b])
            r[b] = max(minR, min(0.12f * len + 0.02f * diag, 0.09f * diag))
        }
        for (b in intArrayOf(B_TH_L, B_TH_R)) r[b] = max(r[b], 0.4f * hips)
        return r
    }

    /**
     * Para cada ponto (xs, ys) da imagem em repouso, escolhe até 3 ossos e seus pesos (somam 1).
     * idx e w têm 3 posições por ponto.
     */
    fun skin(xs: FloatArray, ys: FloatArray, n: Int, j: FloatArray, diag: Float, idx: IntArray, w: FloatArray) {
        val r = radii(j, diag)
        val eps = (0.012f * diag) * (0.012f * diag)
        val wt = FloatArray(BONES)
        for (i in 0 until n) {
            for (b in 0 until BONES) {
                val a = BONE_A[b]
                val c = BONE_B[b]
                val d = max(0f, segDist(xs[i], ys[i], j[a * 2], j[a * 2 + 1], j[c * 2], j[c * 2 + 1]) - r[b])
                val q = d * d + eps
                wt[b] = 1f / (q * q * q)
            }
            var b0 = 0
            var b1 = -1
            var b2 = -1
            for (b in 1 until BONES) if (wt[b] > wt[b0]) b0 = b
            for (b in 0 until BONES) if (b != b0 && (b1 < 0 || wt[b] > wt[b1])) b1 = b
            for (b in 0 until BONES) if (b != b0 && b != b1 && (b2 < 0 || wt[b] > wt[b2])) b2 = b
            val sum = wt[b0] + wt[b1] + wt[b2]
            idx[i * 3] = b0
            idx[i * 3 + 1] = b1
            idx[i * 3 + 2] = b2
            w[i * 3] = wt[b0] / sum
            w[i * 3 + 1] = wt[b1] / sum
            w[i * 3 + 2] = wt[b2] / sum
        }
    }

    /** Move os pontos de acordo com as matrizes dos ossos. out: x e y intercalados. */
    fun deform(xs: FloatArray, ys: FloatArray, n: Int, idx: IntArray, w: FloatArray, bones: FloatArray, out: FloatArray) {
        for (i in 0 until n) {
            var ox = 0f
            var oy = 0f
            val x = xs[i]
            val y = ys[i]
            for (k in 0..2) {
                val o = idx[i * 3 + k] * 6
                val wk = w[i * 3 + k]
                ox += wk * (bones[o] * x + bones[o + 1] * y + bones[o + 2])
                oy += wk * (bones[o + 3] * x + bones[o + 4] * y + bones[o + 5])
            }
            out[i * 2] = ox
            out[i * 2 + 1] = oy
        }
    }
}

/** Uma pose do esqueleto. Deslocamentos em frações da altura do quadro; ângulos em radianos. */
class RigPose {
    var dx = 0f
    var dy = 0f
    var rot = 0f
    var sx = 1f
    var sy = 1f
    var tor = 0f
    var head = 0f
    val ua = FloatArray(2)
    val fa = FloatArray(2)
    val ha = FloatArray(2)
    val th = FloatArray(2)
    val sh = FloatArray(2)
    val ft = FloatArray(2)

    fun reset() {
        dx = 0f; dy = 0f; rot = 0f; sx = 1f; sy = 1f; tor = 0f; head = 0f
        for (a in arrayOf(ua, fa, ha, th, sh, ft)) {
            a[0] = 0f
            a[1] = 0f
        }
    }

    fun copyFrom(o: RigPose) {
        dx = o.dx; dy = o.dy; rot = o.rot; sx = o.sx; sy = o.sy; tor = o.tor; head = o.head
        for (k in 0..1) {
            ua[k] = o.ua[k]; fa[k] = o.fa[k]; ha[k] = o.ha[k]
            th[k] = o.th[k]; sh[k] = o.sh[k]; ft[k] = o.ft[k]
        }
    }

    /** this = a + (b - a) * k */
    fun mix(a: RigPose, b: RigPose, k: Float) {
        fun l(x: Float, y: Float) = x + (y - x) * k
        dx = l(a.dx, b.dx); dy = l(a.dy, b.dy); rot = l(a.rot, b.rot)
        sx = l(a.sx, b.sx); sy = l(a.sy, b.sy); tor = l(a.tor, b.tor); head = l(a.head, b.head)
        for (i in 0..1) {
            ua[i] = l(a.ua[i], b.ua[i]); fa[i] = l(a.fa[i], b.fa[i]); ha[i] = l(a.ha[i], b.ha[i])
            th[i] = l(a.th[i], b.th[i]); sh[i] = l(a.sh[i], b.sh[i]); ft[i] = l(a.ft[i], b.ft[i])
        }
    }
}
