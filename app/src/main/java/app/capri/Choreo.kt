package app.capri

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Onde ela pode ficar e o que ela faz sozinha. Tudo em pixels da tela.
 * Posição = canto de cima e da esquerda da janela da figura.
 * Não depende do Android, para poder ser testado no computador.
 */
class Env(
    val screenW: Float,
    val screenH: Float,
    /** Menor y permitido (abaixo da barra de status). */
    val minY: Float,
    /** Maior y permitido para a base da figura. */
    val maxBottom: Float,
    val figW: Float,
    val figH: Float,
    /** Distância da base da janela até a sola dos pés. */
    val feetInset: Float,
    /** Distância da base da janela até o quadril. */
    val pelvisH: Float,
    val density: Float
) {
    var hasInput = false
    var inL = 0f
    var inT = 0f
    var inR = 0f
    var inB = 0f

    fun setInput(l: Float, t: Float, r: Float, b: Float) {
        hasInput = true
        inL = l; inT = t; inR = r; inB = b
    }

    fun clearInput() {
        hasInput = false
    }

    fun clampX(x: Float): Float = min(max(0f, x), max(0f, screenW - figW))
    fun clampY(y: Float): Float = min(max(minY, y), max(minY, maxBottom - figH))
}

sealed class Anchor {
    /** Posição fixa na tela. */
    class Abs(val x: Float, val y: Float) : Anchor()

    /** Na borda de cima da caixa de digitar; u vai de 0 (esquerda) a 1 (direita). */
    class Edge(val u: Float, val sit: Boolean) : Anchor()

    /** Num ponto da elipse em volta da caixa de digitar. */
    class Orbit(val theta: Float) : Anchor()
}

object Choreo {

    /** Canto da janela da figura para o ponto de ancoragem. Sempre dentro da tela. */
    fun resolve(a: Anchor, e: Env, o: FloatArray) {
        var x: Float
        var y: Float
        when (a) {
            is Anchor.Abs -> {
                x = a.x
                y = a.y
            }
            is Anchor.Edge -> {
                if (e.hasInput) {
                    val cx = e.inL + a.u * (e.inR - e.inL)
                    x = cx - e.figW / 2f
                    y = if (a.sit) {
                        e.inT + 3f * e.density - e.figH + e.pelvisH
                    } else {
                        e.inT + e.feetInset - e.figH
                    }
                } else {
                    x = e.screenW / 2f - e.figW / 2f
                    y = e.screenH * 0.6f
                }
            }
            is Anchor.Orbit -> {
                if (e.hasInput) {
                    val cx = (e.inL + e.inR) / 2f
                    val cy = (e.inT + e.inB) / 2f
                    val rx = (e.inR - e.inL) / 2f + 0.55f * e.figW
                    val ry = (e.inB - e.inT) / 2f + 0.45f * e.figH
                    x = cx + rx * cos(a.theta) - e.figW / 2f
                    y = cy + ry * sin(a.theta) - e.figH / 2f
                } else {
                    x = e.screenW / 2f - e.figW / 2f
                    y = e.screenH * 0.5f
                }
            }
        }
        o[0] = e.clampX(x)
        o[1] = e.clampY(y)
    }

    private fun smooth(k: Float): Float = k * k * (3f - 2f * k)

    /** Em que ponto (0 a 1) do teleporte ela some de um lugar e aparece no outro. */
    const val TELE_K = 0.42f

    /** Integral de uma rampa suave (0 a 1 em a) até x. */
    private fun ramp(x: Float, a: Float): Float {
        val y = min(1f, max(0f, x / a))
        return a * (y * y * y - 0.5f * y * y * y * y)
    }

    /**
     * Distorce o tempo de uma volta: começa devagar, anda em velocidade constante e termina devagar,
     * sem mudar o ponto final (0 vira 0 e 1 vira 1).
     */
    fun warp(k: Float): Float {
        val a = 0.15f
        val x = min(1f, max(0f, k))
        val w = if (x < a) ramp(x, a)
        else if (x <= 1f - a) 0.5f * a + (x - a)
        else (1f - 1.5f * a) + (0.5f * a - ramp(1f - x, a))
        return w / (1f - a)
    }

    /**
     * Um trecho da coreografia: faz [move] por [durMs] indo de [from] para [to].
     * Se loop for verdadeiro, em vez disso ela gira em volta de um centro.
     */
    class Act(
        val move: Move,
        val durMs: Long,
        val from: Anchor,
        val to: Anchor,
        val ease: Boolean = false,
        /** Voltas em volta da caixa de digitar (from e to devem ser Orbit). */
        val orbitTurns: Float = 0f,
        /** Voo em círculos livre: centro e raios. */
        val loopCx: Float = 0f,
        val loopCy: Float = 0f,
        val loopRx: Float = 0f,
        val loopRy: Float = 0f,
        /** Teleporte: fica em from até TELE_K e aparece em to de uma vez (com glitch). */
        val teleport: Boolean = false
    ) {
        val freeLoop: Boolean get() = loopRx > 0f

        fun pos(k0: Float, e: Env, o: FloatArray) {
            val k = min(1f, max(0f, k0))
            if (teleport) {
                resolve(if (k < TELE_K) from else to, e, o)
                return
            }
            if (freeLoop) {
                val th = (2.0 * PI * warp(k)).toFloat() - (PI / 2.0).toFloat()
                val x = loopCx + loopRx * cos(th) - e.figW / 2f
                val y = loopCy + loopRy * (1f + sin(th)) / 1f - e.figH / 2f - loopRy
                o[0] = e.clampX(x)
                o[1] = e.clampY(y)
                return
            }
            if (orbitTurns != 0f && from is Anchor.Orbit && e.hasInput) {
                val th = from.theta + (2.0 * PI * orbitTurns * warp(k)).toFloat()
                resolve(Anchor.Orbit(th), e, o)
                return
            }
            val kk = if (ease) smooth(k) else k
            val a = FloatArray(2)
            val b = FloatArray(2)
            resolve(from, e, a)
            resolve(to, e, b)
            o[0] = a[0] + (b[0] - a[0]) * kk
            o[1] = a[1] + (b[1] - a[1]) * kk
        }
    }

    private fun gestureMs(m: Move): Long = when (m) {
        Move.WAVE -> 2600L
        Move.DANCE, Move.DANCE2 -> 4200L
        Move.HOP -> 3200L
        Move.SPIN -> 1700L
        Move.STRETCH -> 2300L
        Move.LEAN -> 2400L
        Move.SHRUG -> 1900L
        Move.CHEER -> 2500L
        Move.SASS -> 3000L
        Move.LOOK -> 3300L
        Move.GLITCH -> 1100L
        Move.ROUTINE -> 7600L
        Move.CURTSY -> 2600L
        Move.PRESENT -> 3000L
        Move.SWAY -> 4200L
        else -> 2500L
    }

    /** Escolhe o que ela faz em seguida. */
    class Planner(private val rnd: Random) {
        private var lastGesture: Move? = null
        private var lastKind = -1
        private var glitchOn = true

        private fun gesture(): Move {
            var m: Move
            var tries = 0
            do {
                m = Move.GESTURES[rnd.nextInt(Move.GESTURES.size)]
                tries++
            } while (m == lastGesture && tries < 6)
            lastGesture = m
            return m
        }

        private fun pick(weights: IntArray): Int {
            var total = 0
            for (i in weights.indices) total += if (i == lastKind && weights[i] > 0) max(1, weights[i] / 3) else weights[i]
            if (total <= 0) return 0
            var r = rnd.nextInt(total)
            for (i in weights.indices) {
                val w = if (i == lastKind && weights[i] > 0) max(1, weights[i] / 3) else weights[i]
                if (r < w) {
                    lastKind = i
                    return i
                }
                r -= w
            }
            return 0
        }

        private fun dist(a: FloatArray, b: FloatArray): Float = hypot(a[0] - b[0], a[1] - b[1])

        /** Trecho para ir da posição atual até o ponto de partida do próximo movimento. */
        private fun transit(e: Env, cur: Anchor, to: Anchor, out: MutableList<Act>) {
            val a = FloatArray(2)
            val b = FloatArray(2)
            resolve(cur, e, a)
            resolve(to, e, b)
            val d = dist(a, b)
            if (d < 6f * e.density) return
            val dy = abs(a[1] - b[1])
            if (d <= e.figW * 1.6f && dy < 0.3f * e.figH) {
                val ms = max(500L, (d / (70f * e.density) * 1000f).toLong())
                out.add(Act(Move.WALK, ms, cur, to, ease = true))
            } else if (glitchOn && rnd.nextInt(3) == 0) {
                out.add(Act(Move.GLITCH, 1000L, cur, to, teleport = true))
            } else {
                val ms = max(800L, (d / (230f * e.density) * 1000f).toLong())
                out.add(Act(Move.FLY, ms, cur, to, ease = true))
            }
        }

        private fun hold(m: Move, ms: Long, at: Anchor) = Act(m, ms, at, at)

        fun plan(e: Env, curX: Float, curY: Float, moves: Boolean, wander: Boolean, glitch: Boolean = true): List<Act> {
            glitchOn = glitch
            val out = ArrayList<Act>()
            val cur: Anchor = Anchor.Abs(e.clampX(curX), e.clampY(curY))
            if (e.hasInput) {
                planInput(e, cur, moves, wander, out)
            } else {
                planFree(e, cur, moves, wander, out)
            }
            return out
        }

        private fun planFree(e: Env, cur: Anchor, moves: Boolean, wander: Boolean, out: MutableList<Act>) {
            val here = FloatArray(2)
            resolve(cur, e, here)
            val w = intArrayOf(
                3, if (moves) 4 else 0, if (wander) 3 else 0, if (wander) 2 else 0, if (wander && moves) 1 else 0,
                if (wander && glitchOn) 2 else 0, if (moves && glitchOn) 1 else 0
            )
            when (pick(w)) {
                0 -> out.add(hold(Move.IDLE, 2500L + rnd.nextInt(2500), cur))
                1 -> {
                    val g = gesture()
                    out.add(hold(g, gestureMs(g), cur))
                }
                2 -> {
                    // anda até outro ponto na mesma altura
                    val tx = e.clampX(rnd.nextFloat() * (e.screenW - e.figW))
                    val d = abs(tx - here[0])
                    if (d < 0.5f * e.figW) {
                        out.add(hold(Move.IDLE, 1500L, cur))
                    } else {
                        val ms = max(600L, (d / (70f * e.density) * 1000f).toLong())
                        out.add(Act(Move.WALK, ms, cur, Anchor.Abs(tx, here[1]), ease = true))
                        if (moves && rnd.nextBoolean()) {
                            val g = gesture()
                            out.add(hold(g, gestureMs(g), Anchor.Abs(tx, here[1])))
                        }
                    }
                }
                3 -> {
                    // voa até outro ponto qualquer
                    val tx = e.clampX(rnd.nextFloat() * (e.screenW - e.figW))
                    val ty = e.clampY(e.minY + rnd.nextFloat() * (e.maxBottom - e.minY - e.figH))
                    val d = hypot(tx - here[0], ty - here[1])
                    val ms = max(1200L, (d / (200f * e.density) * 1000f).toLong())
                    val to = Anchor.Abs(tx, ty)
                    out.add(Act(Move.FLY, ms, cur, to, ease = true))
                    out.add(hold(Move.IDLE, 1200L, to))
                }
                5 -> {
                    // some com glitch e aparece em outro lugar, deixando um rastro
                    val tx = e.clampX(rnd.nextFloat() * (e.screenW - e.figW))
                    val ty = e.clampY(e.minY + rnd.nextFloat() * (e.maxBottom - e.minY - e.figH))
                    val to = Anchor.Abs(tx, ty)
                    out.add(Act(Move.GLITCH, 1000L, cur, to, teleport = true))
                    out.add(hold(Move.IDLE, 1400L, to))
                }
                6 -> out.add(hold(Move.GLITCH, 1100L, cur))
                else -> {
                    // voa em círculos
                    val rx = max(50f * e.density, 0.7f * e.figW)
                    val ry = max(40f * e.density, 0.4f * e.figH)
                    out.add(
                        Act(
                            Move.FLY, 6500L, cur, cur, ease = false, orbitTurns = 0f,
                            loopCx = here[0] + e.figW / 2f, loopCy = here[1] + e.figH / 2f + ry,
                            loopRx = rx, loopRy = ry
                        )
                    )
                }
            }
        }

        private fun planInput(e: Env, cur: Anchor, moves: Boolean, wander: Boolean, out: MutableList<Act>) {
            val here = FloatArray(2)
            resolve(cur, e, here)
            val boxW = max(1f, e.inR - e.inL)
            // posição atual em fração da borda (para recomeçar de onde está)
            val u0 = min(0.95f, max(0.05f, (here[0] + e.figW / 2f - e.inL) / boxW))
            val w = intArrayOf(
                if (wander) 3 else 0,           // andar na borda
                if (wander) 3 else 2,           // sentar
                if (wander) 2 else 0,           // voar em volta
                if (moves) 3 else 0,            // gesto
                1,                              // parada
                if (wander && glitchOn) 2 else 0 // teleporte
            )
            when (pick(w)) {
                0 -> {
                    val u1 = 0.08f + rnd.nextFloat() * 0.84f
                    val s = Anchor.Edge(u0, false)
                    transit(e, cur, s, out)
                    val ms = max(700L, (abs(u1 - u0) * boxW / (70f * e.density) * 1000f).toLong())
                    out.add(Act(Move.WALK, ms, s, Anchor.Edge(u1, false), ease = true))
                }
                1 -> {
                    val u = if (wander) 0.1f + rnd.nextFloat() * 0.8f else u0
                    val s = Anchor.Edge(u, true)
                    transit(e, cur, s, out)
                    out.add(hold(Move.SIT, 5000L + rnd.nextInt(4500), s))
                    if (moves && rnd.nextInt(3) == 0) out.add(hold(Move.WAVE, 2400L, s))
                }
                2 -> {
                    val th0 = (rnd.nextFloat() * 2f * PI).toFloat()
                    val s = Anchor.Orbit(th0)
                    transit(e, cur, s, out)
                    val turns = if (rnd.nextBoolean()) 1f else -1f
                    out.add(Act(Move.FLY, 7000L, s, s, orbitTurns = turns))
                    val back = Anchor.Edge(0.1f + rnd.nextFloat() * 0.8f, false)
                    out.add(Act(Move.FLY, 1100L, Anchor.Orbit(th0 + turns * 2f * PI.toFloat()), back, ease = true))
                }
                3 -> {
                    val s = Anchor.Edge(u0, false)
                    transit(e, cur, s, out)
                    val g = gesture()
                    out.add(hold(g, gestureMs(g), s))
                }
                5 -> {
                    val s = Anchor.Edge(0.08f + rnd.nextFloat() * 0.84f, false)
                    out.add(Act(Move.GLITCH, 1000L, cur, s, teleport = true))
                    out.add(hold(Move.IDLE, 1200L, s))
                }
                else -> {
                    val s = Anchor.Edge(u0, false)
                    transit(e, cur, s, out)
                    out.add(hold(Move.IDLE, 2200L + rnd.nextInt(2000), s))
                }
            }
        }
    }
}
