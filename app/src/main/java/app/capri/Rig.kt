package app.capri

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Esqueleto 2D: 19 juntas colocadas pela pessoa sobre a imagem do corpo, 14 ossos,
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
    const val WAIST = 18
    const val JOINTS = 19

    val NAMES = arrayOf(
        "Pescoço (onde o rosto se apoia)", "Quadril (centro do corpo)",
        "Ombro E", "Cotovelo E", "Pulso E", "Ponta da mão E",
        "Ombro D", "Cotovelo D", "Pulso D", "Ponta da mão D",
        "Quadril E (onde a perna começa)", "Joelho E", "Tornozelo E", "Ponta do pé E",
        "Quadril D (onde a perna começa)", "Joelho D", "Tornozelo D", "Ponta do pé D",
        "Cintura (meio da coluna, onde o corpo dobra)"
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
    const val B_CHEST = 13
    const val BONES = 14

    /** Cada osso vai da junta A até a junta B. */
    val BONE_A = intArrayOf(PELVIS, SHO_L, ELB_L, WRI_L, SHO_R, ELB_R, WRI_R, HIP_L, KNEE_L, ANK_L, HIP_R, KNEE_R, ANK_R, WAIST)
    val BONE_B = intArrayOf(WAIST, ELB_L, WRI_L, HAND_L, ELB_R, WRI_R, HAND_R, KNEE_L, ANK_L, TOE_L, KNEE_R, ANK_R, TOE_R, NECK)

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
        put(WAIST, 0f, 0.30f)
        return j
    }

    fun encode(j: FloatArray): String = j.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }

    fun decode(s: String): FloatArray? {
        if (s.isBlank()) return null
        val parts = s.split(",")
        // versões antigas guardavam 18 juntas (36 números): a cintura nova vai no meio entre quadril e pescoço
        if (parts.size != JOINTS * 2 && parts.size != (JOINTS - 1) * 2) return null
        val out = FloatArray(JOINTS * 2)
        for (i in parts.indices) out[i] = parts[i].trim().toFloatOrNull() ?: return null
        if (parts.size == (JOINTS - 1) * 2) {
            out[WAIST * 2] = out[PELVIS * 2] + 0.5f * (out[NECK * 2] - out[PELVIS * 2])
            out[WAIST * 2 + 1] = out[PELVIS * 2 + 1] + 0.5f * (out[NECK * 2 + 1] - out[PELVIS * 2 + 1])
        }
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

    /** Estica ou encurta k vezes ao longo da direção (ux, uy), mantendo o ponto (px, py) parado. */
    fun scaleAlong(ux: Float, uy: Float, k: Float, px: Float, py: Float, out: FloatArray) {
        val a = 1f + (k - 1f) * ux * ux
        val b = (k - 1f) * ux * uy
        val d = 1f + (k - 1f) * uy * uy
        out[0] = a; out[1] = b; out[2] = px - (a * px + b * py)
        out[3] = b; out[4] = d; out[5] = py - (b * px + d * py)
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
        val armBase = FloatArray(6)
        fun jx(i: Int) = j[i * 2]
        fun jy(i: Int) = j[i * 2 + 1]

        // raiz: desloca, gira em volta do quadril e amassa com os pés no chão
        val footY = max(max(jy(TOE_L), jy(TOE_R)), max(jy(ANK_L), jy(ANK_R)))
        scaleAbout(p.sx, p.sy, jx(PELVIS), footY, t1)
        rotAbout(p.rot, jx(PELVIS), jy(PELVIS), t2)
        mul(t2, t1, root)
        translate(p.dx * frameH, p.dy * frameH, t1)
        mul(t1, root, root)

        // parte de baixo do tronco: gira em volta do quadril
        rotAbout(p.tor, jx(PELVIS), jy(PELVIS), t1)
        mul(root, t1, torso)
        System.arraycopy(torso, 0, bones, B_TORSO * 6, 6)

        // peito: dobra na cintura em cima do resto do tronco (ombros, braços e cabeça vão com ele)
        val chest = FloatArray(6)
        rotAbout(p.chest, jx(WAIST), jy(WAIST), t1)
        mul(torso, t1, chest)
        System.arraycopy(chest, 0, bones, B_CHEST * 6, 6)

        // cabeça (rosto e cabelo)
        rotAbout(p.head, jx(NECK), jy(NECK), t1)
        mul(chest, t1, head)

        val sgn = floatArrayOf(1f, -1f)
        for (s in 0..1) {
            val sho = if (s == 0) SHO_L else SHO_R
            val elb = if (s == 0) ELB_L else ELB_R
            val wri = if (s == 0) WRI_L else WRI_R
            val bu = if (s == 0) B_UA_L else B_UA_R
            // ombro: pode subir (encolher) além de acompanhar o peito
            translate(0f, -p.shr[s] * frameH, t2)
            mul(chest, t2, armBase)
            rotAbout(sgn[s] * p.ua[s], jx(sho), jy(sho), t1)
            mul(armBase, t1, chain)
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
            val hx = jx(hip)
            val hy = jy(hip)
            val kx = jx(knee)
            val ky = jy(knee)
            val ax = jx(ank)
            val ay = jy(ank)

            // coxa: gira no quadril e pode encurtar (a perna vem para a frente, vista de frente)
            rotAbout(sgn[s] * p.th[s], hx, hy, t1)
            mul(root, t1, chain)
            val tl = max(1f, hypot(kx - hx, ky - hy))
            scaleAlong((kx - hx) / tl, (ky - hy) / tl, p.thS[s], hx, hy, t2)
            mul(chain, t2, chain2)
            System.arraycopy(chain2, 0, bones, bt * 6, 6)

            // o joelho acompanha a coxa encurtada
            translate(-(1f - p.thS[s]) * (kx - hx), -(1f - p.thS[s]) * (ky - hy), t2)
            mul(chain, t2, chain)
            rotAbout(sgn[s] * p.sh[s], kx, ky, t1)
            mul(chain, t1, chain2)
            val sl = max(1f, hypot(ax - kx, ay - ky))
            scaleAlong((ax - kx) / sl, (ay - ky) / sl, p.shS[s], kx, ky, t2)
            mul(chain2, t2, chain)
            System.arraycopy(chain, 0, bones, (bt + 1) * 6, 6)

            // o tornozelo acompanha a canela encurtada
            translate(-(1f - p.shS[s]) * (ax - kx), -(1f - p.shS[s]) * (ay - ky), t2)
            mul(chain2, t2, chain2)
            rotAbout(sgn[s] * p.ft[s], ax, ay, t1)
            mul(chain2, t1, chain)
            System.arraycopy(chain, 0, bones, (bt + 2) * 6, 6)
        }
    }

    // ---------- peças: cada osso leva a sua parte da imagem, sem esticar ----------

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        var t = if (len2 < 1e-6f) 0f else ((px - ax) * vx + (py - ay) * vy) / len2
        t = min(1f, max(0f, t))
        return hypot(px - (ax + t * vx), py - (ay + t * vy))
    }

    /** O osso de onde cada osso se pendura (o torso não tem pai). */
    val PARENT = intArrayOf(-1, B_CHEST, B_UA_L, B_FA_L, B_CHEST, B_UA_R, B_FA_R, B_TORSO, B_TH_L, B_SH_L, B_TORSO, B_TH_R, B_SH_R, B_TORSO)

    /** Ordem de desenho: de trás para a frente (pernas, torso, braços). */
    val DRAW_ORDER = intArrayOf(
        B_TH_L, B_SH_L, B_FT_L, B_TH_R, B_SH_R, B_FT_R, B_TORSO, B_CHEST,
        B_UA_L, B_FA_L, B_HA_L, B_UA_R, B_FA_R, B_HA_R
    )

    private fun alphaAt(a: IntArray, w: Int, h: Int, x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= w || y >= h) 0 else (a[y * w + x] ushr 24)

    /**
     * Espessura de cada osso medida na própria imagem: meia largura do corpo ao redor do osso.
     * px: pixels ARGB da imagem; j: juntas em pixels.
     */
    fun radiiFromMask(px: IntArray, w: Int, h: Int, j: FloatArray): FloatArray {
        var y0 = h
        var y1 = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((px[y * w + x] ushr 24) > 10) {
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        val bh = max(20f, (y1 - y0).toFloat())
        fun d(a: Int, b: Int) = hypot(j[a * 2] - j[b * 2], j[a * 2 + 1] - j[b * 2 + 1])
        val shoulders = d(SHO_L, SHO_R)
        val r = FloatArray(BONES)
        for (b in 0 until BONES) {
            val ax = j[BONE_A[b] * 2]
            val ay = j[BONE_A[b] * 2 + 1]
            val bx = j[BONE_B[b] * 2]
            val by = j[BONE_B[b] * 2 + 1]
            val len = hypot(bx - ax, by - ay)
            val minR = 0.012f * bh
            if (len < 1f) {
                r[b] = minR * 2f
                continue
            }
            val nx = -(by - ay) / len
            val ny = (bx - ax) / len
            val torsoLike = b == B_TORSO || b == B_CHEST
            val maxD = if (torsoLike) 0.5f * shoulders + 0.05f * bh else min(0.5f * len, 0.2f * bh)
            val vals = ArrayList<Float>()
            for (k in 0..10) {
                val t = 0.1f + 0.08f * k
                val cx = ax + (bx - ax) * t
                val cy = ay + (by - ay) * t
                if (alphaAt(px, w, h, cx.toInt(), cy.toInt()) <= 10) continue
                var pos = 0f
                while (pos < maxD && alphaAt(px, w, h, (cx + nx * pos).toInt(), (cy + ny * pos).toInt()) > 10) pos += 0.5f
                var neg = 0f
                while (neg < maxD && alphaAt(px, w, h, (cx - nx * neg).toInt(), (cy - ny * neg).toInt()) > 10) neg += 0.5f
                vals.add((pos + neg) / 2f)
            }
            vals.sort()
            var rr = if (vals.isEmpty()) 0.05f * bh else vals[vals.size / 2]
            val hi = if (torsoLike) 0.65f * shoulders else 0.4f * len
            rr = min(rr, max(minR, hi))
            r[b] = max(rr, minR)
        }
        return r
    }

    /** Qual osso leva cada ponto: o que está mais "dentro" dele, medido na espessura do próprio osso. -1 = vazio. */
    fun assignBones(px: IntArray, w: Int, h: Int, j: FloatArray, r: FloatArray): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if ((px[i] ushr 24) <= 10) {
                    out[i] = -1
                    continue
                }
                var best = 0
                var bv = Float.MAX_VALUE
                for (b in 0 until BONES) {
                    val a = BONE_A[b]
                    val c = BONE_B[b]
                    val d = segDist(x + 0.5f, y + 0.5f, j[a * 2], j[a * 2 + 1], j[c * 2], j[c * 2 + 1]) / r[b]
                    if (d < bv) {
                        bv = d
                        best = b
                    }
                }
                out[i] = best.toByte()
            }
        }
        return out
    }

    /** Uma parte da imagem: canto (x, y), tamanho e pixels ARGB. */
    class Part(val x: Int, val y: Int, val w: Int, val h: Int, val px: IntArray)

    /**
     * Corta a imagem em uma parte por osso. Cada parte leva também uma "tampa" macia dos pontos do osso
     * pai perto da junta, para não abrir fresta quando o membro dobra.
     * Devolve BONES partes (null quando o osso não leva nenhum ponto).
     */
    fun buildParts(px: IntArray, w: Int, h: Int, j: FloatArray, r: FloatArray, owner: ByteArray): Array<Part?> {
        val pad = 2
        val minX = IntArray(BONES) { w }
        val minY = IntArray(BONES) { h }
        val maxX = IntArray(BONES) { -1 }
        val maxY = IntArray(BONES) { -1 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = owner[y * w + x].toInt()
                if (o < 0) continue
                if (x < minX[o]) minX[o] = x
                if (x > maxX[o]) maxX[o] = x
                if (y < minY[o]) minY[o] = y
                if (y > maxY[o]) maxY[o] = y
            }
        }
        val out = arrayOfNulls<Part>(BONES)
        for (b in 0 until BONES) {
            var x0 = minX[b]
            var y0 = minY[b]
            var x1 = maxX[b]
            var y1 = maxY[b]
            val par = PARENT[b]
            val hx = j[BONE_A[b] * 2]
            val hy = j[BONE_A[b] * 2 + 1]
            val cap = max(3f, 1.25f * r[b])
            if (par >= 0) {
                // a tampa pode estender a caixa da parte
                x0 = min(x0, (hx - cap).toInt() - 1)
                y0 = min(y0, (hy - cap).toInt() - 1)
                x1 = max(x1, (hx + cap).toInt() + 1)
                y1 = max(y1, (hy + cap).toInt() + 1)
            }
            if (maxX[b] < 0 && par < 0) continue
            x0 = max(0, x0 - pad)
            y0 = max(0, y0 - pad)
            x1 = min(w - 1, x1 + pad)
            y1 = min(h - 1, y1 + pad)
            if (x1 < x0 || y1 < y0) continue
            val pw = x1 - x0 + 1
            val ph = y1 - y0 + 1
            val arr = IntArray(pw * ph)
            var any = false
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val i = y * w + x
                    val o = owner[i].toInt()
                    if (o < 0) continue
                    var c = 0
                    if (o == b) {
                        c = px[i]
                    } else if (o == par) {
                        val d = hypot(x + 0.5f - hx, y + 0.5f - hy)
                        if (d < cap) {
                            // macia: inteira no miolo, some perto da borda da tampa
                            val k = if (d < 0.6f * cap) 1f else (cap - d) / (0.4f * cap)
                            val a = ((px[i] ushr 24) * k).toInt().coerceIn(0, 255)
                            c = (a shl 24) or (px[i] and 0xFFFFFF)
                        }
                    }
                    if (c != 0) {
                        arr[(y - y0) * pw + (x - x0)] = c
                        any = true
                    }
                }
            }
            if (any) out[b] = Part(x0, y0, pw, ph, arr)
        }
        return out
    }

    // ---------- palpite das juntas a partir da silhueta ----------

    /**
     * Adivinha onde ficam as 18 juntas olhando só a silhueta do corpo (sem cabeça): pernas separadas,
     * pescoço no topo, braços ao lado. px: pixels ARGB. Devolve frações da imagem (36 números).
     * Quando não acha algo, usa a posição padrão. O usuário ajusta no editor.
     */
    fun autoFit(px: IntArray, w: Int, h: Int): FloatArray {
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((px[y * w + x] ushr 24) > 16) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return defaults(0.25f, 0.1f, 0.75f, 0.95f)
        x1 += 1
        y1 += 1
        val bw = (x1 - x0).toFloat()
        val bh = (y1 - y0).toFloat()
        val def = defaults(x0.toFloat() / w, y0.toFloat() / h, x1.toFloat() / w, y1.toFloat() / h)
        val j = FloatArray(JOINTS * 2)
        for (i in 0 until JOINTS) {
            j[i * 2] = def[i * 2] * w
            j[i * 2 + 1] = def[i * 2 + 1] * h
        }
        fun put(i: Int, x: Float, y: Float) {
            j[i * 2] = x
            j[i * 2 + 1] = y
        }

        // trechos opacos de uma linha entre xa e xb (juntando buracos de até 2 pontos)
        fun runs(y: Int, xa: Int, xb: Int, minW: Int): List<IntArray> {
            val out = ArrayList<IntArray>()
            if (y < 0 || y >= h) return out
            var start = -1
            var last = -10
            for (x in max(0, xa)..min(w - 1, xb)) {
                if ((px[y * w + x] ushr 24) > 16) {
                    if (start < 0) start = x
                    last = x
                } else if (start >= 0 && x - last > 2) {
                    if (last - start + 1 >= minW) out.add(intArrayOf(start, last))
                    start = -1
                }
            }
            if (start >= 0 && last - start + 1 >= minW) out.add(intArrayOf(start, last))
            return out
        }
        val minRun = max(2, (0.012f * bw).toInt())
        val midX = (x0 + x1) / 2

        // ---- pernas: de baixo para cima, até onde elas se juntam (a virilha)
        var crotch = -1
        run {
            var y = y1 - 1 - (0.04f * bh).toInt()
            val top = y0 + (0.35f * bh).toInt()
            while (y > top) {
                val rr = runs(y, x0, x1, minRun)
                if (rr.size < 2) {
                    crotch = y
                    break
                }
                y--
            }
        }
        var haveLegs = crotch > 0 && crotch < y1 - 0.15f * bh
        if (!haveLegs) crotch = (y0 + 0.5f * bh).toInt()
        val hipY = crotch - 0.03f * bh
        val pelvisY = hipY - 0.03f * bh

        // centro de cada perna numa faixa de linhas
        fun legCenter(ya: Int, yb: Int, left: Boolean): Float {
            var sum = 0f
            var n = 0
            for (y in ya..yb) {
                val rr = runs(y, x0, x1, minRun)
                if (rr.size < 2) continue
                val pick = if (left) rr[0] else rr[rr.size - 1]
                sum += (pick[0] + pick[1] + 1) / 2f
                n++
            }
            return if (n == 0) Float.NaN else sum / n
        }
        if (haveLegs) {
            val kneeRow = (hipY + 0.5f * (y1 - 0.06f * bh - hipY)).toInt()
            val ankRow = (y1 - 0.10f * bh).toInt()
            val hipRow = min(y1 - 2, crotch + (0.03f * bh).toInt())
            val lh = legCenter(hipRow, hipRow + 2, true)
            val rh = legCenter(hipRow, hipRow + 2, false)
            val lk = legCenter(kneeRow - 1, kneeRow + 1, true)
            val rk = legCenter(kneeRow - 1, kneeRow + 1, false)
            val la = legCenter(ankRow - 1, ankRow + 1, true)
            val ra = legCenter(ankRow - 1, ankRow + 1, false)
            if (!lh.isNaN() && !rh.isNaN() && !lk.isNaN() && !rk.isNaN() && !la.isNaN() && !ra.isNaN()) {
                // ponta do pé: ponto mais baixo de cada metade
                val splitX = ((la + ra) / 2f).toInt()
                var toeLY = -1
                var toeRY = -1
                for (y in y1 - 1 downTo y0) {
                    var l = false
                    var r = false
                    for (x in x0 until x1) {
                        if ((px[y * w + x] ushr 24) > 16) {
                            if (x < splitX) l = true else r = true
                        }
                    }
                    if (l && toeLY < 0) toeLY = y
                    if (r && toeRY < 0) toeRY = y
                    if (toeLY >= 0 && toeRY >= 0) break
                }
                if (toeLY < 0) toeLY = y1 - 1
                if (toeRY < 0) toeRY = y1 - 1
                fun toeX(left: Boolean, ty: Int): Float {
                    var sum = 0f
                    var n = 0
                    for (y in max(y0, ty - 2)..ty) {
                        for (x in x0 until x1) {
                            if ((px[y * w + x] ushr 24) > 16 && (x < splitX) == left) {
                                sum += x + 0.5f
                                n++
                            }
                        }
                    }
                    return if (n == 0) (if (left) la else ra) else sum / n
                }
                val tl = toeX(true, toeLY)
                val tr = toeX(false, toeRY)
                val hy = hipY
                put(HIP_L, lh, hy)
                put(HIP_R, rh, hy)
                put(ANK_L, la, toeLY - 0.05f * bh)
                put(ANK_R, ra, toeRY - 0.05f * bh)
                put(TOE_L, tl, toeLY.toFloat())
                put(TOE_R, tr, toeRY.toFloat())
                put(KNEE_L, lk, hy + 0.5f * (toeLY - 0.05f * bh - hy))
                put(KNEE_R, rk, hy + 0.5f * (toeRY - 0.05f * bh - hy))
            } else {
                haveLegs = false
            }
        }
        if (!haveLegs) {
            // pernas juntas: usa o padrão, mas com a virilha onde achamos
            put(HIP_L, j[HIP_L * 2], hipY)
            put(HIP_R, j[HIP_R * 2], hipY)
        }

        // ---- pescoço: topo do corpo, no trecho do meio
        val topRows = max(2, (0.03f * bh).toInt())
        var nsum = 0f
        var nn = 0
        for (y in y0 until min(y1, y0 + topRows)) {
            val rr = runs(y, x0, x1, minRun)
            if (rr.isEmpty()) continue
            var best = rr[0]
            var bd = Float.MAX_VALUE
            for (q in rr) {
                val d = Math.abs((q[0] + q[1]) / 2f - midX)
                if (d < bd) {
                    bd = d
                    best = q
                }
            }
            nsum += (best[0] + best[1] + 1) / 2f
            nn++
        }
        val neckX = if (nn > 0) nsum / nn else midX.toFloat()
        put(NECK, neckX, y0 + 0.02f * bh)
        put(PELVIS, (j[HIP_L * 2] + j[HIP_R * 2]) / 2f, pelvisY)

        // ---- cintura: a linha mais fina do tronco entre o peito e o quadril
        run {
            val ya = (y0 + 0.28f * bh).toInt()
            val yb = (pelvisY - 0.06f * bh).toInt()
            var bestY = -1
            var bestW = Int.MAX_VALUE
            var bestX = 0f
            val px0 = j[PELVIS * 2]
            for (y in ya..yb) {
                val rr = runs(y, x0, x1, minRun)
                var pick: IntArray? = null
                var bd = Float.MAX_VALUE
                for (q in rr) {
                    val c = (q[0] + q[1]) / 2f
                    val d = Math.abs(c - (neckX + px0) / 2f)
                    if (d < bd) {
                        bd = d
                        pick = q
                    }
                }
                if (pick != null) {
                    val wd = pick[1] - pick[0]
                    if (wd < bestW) {
                        bestW = wd
                        bestY = y
                        bestX = (pick[0] + pick[1] + 1) / 2f
                    }
                }
            }
            if (bestY >= 0) {
                put(WAIST, (bestX + (neckX + px0) / 2f) / 2f, bestY.toFloat())
            } else {
                put(WAIST, (neckX + px0) / 2f, (y0 + 0.02f * bh + pelvisY) / 2f)
            }
        }

        // ---- braços: a ponta de cada um é o ponto mais baixo nas colunas de fora, acima da virilha
        val shY = y0 + 0.08f * bh
        for (side in 0..1) {
            val left = side == 0
            val sho = if (left) SHO_L else SHO_R
            val elb = if (left) ELB_L else ELB_R
            val wri = if (left) WRI_L else WRI_R
            val han = if (left) HAND_L else HAND_R
            val colA = if (left) x0 else (x1 - (0.30f * bw).toInt())
            val colB = if (left) (x0 + (0.30f * bw).toInt()) else x1 - 1
            var tipY = -1
            var tipX = 0f
            val yTop = (y0 + 0.12f * bh).toInt()
            val yBot = min(y1 - 1, if (haveLegs) crotch else (y0 + 0.62f * bh).toInt())
            for (y in yBot downTo yTop) {
                var sum = 0f
                var n = 0
                for (x in colA..colB) {
                    if ((px[y * w + x] ushr 24) > 16) {
                        sum += x + 0.5f
                        n++
                    }
                }
                if (n > 0) {
                    tipY = y
                    tipX = sum / n
                    break
                }
            }
            if (tipY < 0 || tipY < y0 + 0.30f * bh) continue
            // o centro do braço em cada altura: o trecho que fica na coluna de fora
            fun armCenter(y: Int, fallback: Float): Float {
                val rr = runs(y, colA, colB, 1)
                if (rr.isEmpty()) return fallback
                val pick = if (left) rr[0] else rr[rr.size - 1]
                return (pick[0] + pick[1] + 1) / 2f
            }
            val sy = shY
            val sxx = armCenter(sy.toInt(), if (left) x0 + 0.12f * bw else x1 - 0.12f * bw)
            put(sho, sxx, sy)
            val ey = sy + 0.45f * (tipY - sy)
            val wy = sy + 0.85f * (tipY - sy)
            val lerpX = { t: Float -> sxx + (tipX - sxx) * t }
            put(elb, armCenter(ey.toInt(), lerpX(0.45f)), ey)
            put(wri, armCenter(wy.toInt(), lerpX(0.85f)), wy)
            put(han, tipX, tipY.toFloat())
        }

        val o = FloatArray(JOINTS * 2)
        for (i in 0 until JOINTS) {
            o[i * 2] = (j[i * 2] / w).coerceIn(0f, 1f)
            o[i * 2 + 1] = (j[i * 2 + 1] / h).coerceIn(0f, 1f)
        }
        return o
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

    /** Dobra do peito na cintura, em cima do giro do tronco (positivo = para a direita). */
    var chest = 0f

    /** Ombros subindo (encolher), em fração da altura do quadro. */
    val shr = FloatArray(2)

    /** Quanto a coxa e a canela aparecem encurtadas (1 = inteiras). Serve para levantar o pé e sentar. */
    val thS = floatArrayOf(1f, 1f)
    val shS = floatArrayOf(1f, 1f)

    fun reset() {
        dx = 0f; dy = 0f; rot = 0f; sx = 1f; sy = 1f; tor = 0f; head = 0f; chest = 0f
        for (a in arrayOf(ua, fa, ha, th, sh, ft, shr)) {
            a[0] = 0f
            a[1] = 0f
        }
        thS[0] = 1f; thS[1] = 1f
        shS[0] = 1f; shS[1] = 1f
    }

    fun copyFrom(o: RigPose) {
        dx = o.dx; dy = o.dy; rot = o.rot; sx = o.sx; sy = o.sy; tor = o.tor; head = o.head; chest = o.chest
        for (k in 0..1) {
            shr[k] = o.shr[k]
            ua[k] = o.ua[k]; fa[k] = o.fa[k]; ha[k] = o.ha[k]
            th[k] = o.th[k]; sh[k] = o.sh[k]; ft[k] = o.ft[k]
            thS[k] = o.thS[k]; shS[k] = o.shS[k]
        }
    }

    /** this = this + (o - this) * k */
    fun mixWith(o: RigPose, k: Float) {
        mix(this, o, k)
    }

    /** this = a + (b - a) * k */
    fun mix(a: RigPose, b: RigPose, k: Float) {
        fun l(x: Float, y: Float) = x + (y - x) * k
        dx = l(a.dx, b.dx); dy = l(a.dy, b.dy); rot = l(a.rot, b.rot)
        sx = l(a.sx, b.sx); sy = l(a.sy, b.sy); tor = l(a.tor, b.tor); head = l(a.head, b.head)
        chest = l(a.chest, b.chest)
        for (i in 0..1) {
            shr[i] = l(a.shr[i], b.shr[i])
            ua[i] = l(a.ua[i], b.ua[i]); fa[i] = l(a.fa[i], b.fa[i]); ha[i] = l(a.ha[i], b.ha[i])
            th[i] = l(a.th[i], b.th[i]); sh[i] = l(a.sh[i], b.sh[i]); ft[i] = l(a.ft[i], b.ft[i])
            thS[i] = l(a.thS[i], b.thS[i]); shS[i] = l(a.shS[i], b.shS[i])
        }
    }
}
