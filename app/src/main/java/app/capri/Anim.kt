package app.capri

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Os movimentos que a Capri sabe fazer. */
enum class Move {
    IDLE, WAVE, DANCE, DANCE2, HOP, SPIN, STRETCH, LEAN, WALK, TALK, THINK, LISTEN, SIT, FLY, SHRUG, CHEER;

    companion object {
        /** Gestos soltos que ela escolhe quando está sem fazer nada. */
        val GESTURES = listOf(WAVE, DANCE, DANCE2, HOP, SPIN, STRETCH, LEAN, SHRUG, CHEER)
    }
}

/** Calcula a pose do esqueleto para cada movimento, em função do tempo t (segundos desde o início). */
object Anim {

    /** Movimentos que valem para sempre (andar, voar, sentar) em vez de acabar sozinhos. */
    fun loops(m: Move): Boolean = when (m) {
        Move.IDLE, Move.WALK, Move.TALK, Move.THINK, Move.LISTEN, Move.SIT, Move.FLY, Move.DANCE,
        Move.DANCE2 -> true
        else -> false
    }

    /** Sobe de 0 a 1 devagar no começo e no fim (sem quina). */
    fun sstep(x: Float): Float {
        val k = min(1f, max(0f, x))
        return k * k * (3f - 2f * k)
    }

    /** Parte positiva de x (de -1 a 1), suave: começa e termina sem quina. */
    fun bump(x: Float): Float = if (x <= 0f) 0f else sstep(x)

    fun pose(m: Move, t: Float, p: RigPose) {
        p.reset()
        val pi = PI.toFloat()
        when (m) {
            Move.IDLE -> {
                // respira e passa o peso de um pé para o outro, sem ficar parada de verdade
                val br = sin(t * 1.7f)
                val sw = sin(t * 0.6f)
                p.dy = br * 0.003f
                p.dx = sw * 0.006f
                p.rot = -sw * 0.012f
                p.tor = sin(t * 0.9f) * 0.025f + sw * 0.01f
                p.head = sin(t * 0.8f - 0.7f) * 0.045f
                p.ua[0] = 0.14f + br * 0.03f - sw * 0.02f; p.ua[1] = 0.14f - br * 0.03f + sw * 0.02f
                p.fa[0] = 0.06f + sin(t * 1.7f - 0.8f) * 0.02f; p.fa[1] = 0.06f + sin(t * 1.7f - 1.1f) * 0.02f
                p.th[0] = 0.03f + sw * 0.015f; p.th[1] = 0.03f - sw * 0.015f
            }
            Move.WAVE -> {
                val e = sstep(t * 2.6f)
                p.dy = sin(t * 3f) * 0.002f * e
                p.tor = -0.04f * e
                p.head = (0.12f + sin(t * 2f) * 0.03f) * e
                p.ua[0] = 0.14f
                p.ua[1] = 0.14f + (2.31f + sin(t * 3f) * 0.08f) * e
                p.fa[1] = sin(t * 9f) * 0.6f * e
                p.ha[1] = sin(t * 9f + 0.7f) * 0.32f * e
                p.th[0] = 0.03f; p.th[1] = 0.03f
            }
            Move.DANCE -> {
                // passos de lado, levantando um pé de cada vez
                val w = sin(t * 6f)
                val l = bump(w)
                val r = bump(-w)
                p.dy = -abs(w) * 0.025f
                p.rot = w * 0.05f
                p.tor = -w * 0.08f
                p.head = -w * 0.14f
                p.ua[0] = 1.1f + w * 0.5f; p.ua[1] = 1.1f - w * 0.5f
                p.fa[0] = 0.5f * sin(t * 12f); p.fa[1] = 0.5f * sin(t * 12f + 1f)
                p.th[0] = 0.06f + 0.12f * l; p.th[1] = 0.06f + 0.12f * r
                p.thS[0] = 1f - 0.3f * l; p.thS[1] = 1f - 0.3f * r
                p.shS[0] = 1f - 0.45f * l; p.shS[1] = 1f - 0.45f * r
            }
            Move.DANCE2 -> {
                // braços para cima e requebrado
                val w = sin(t * 5f)
                val l = bump(w)
                val r = bump(-w)
                p.dy = -abs(w) * 0.02f
                p.rot = w * 0.08f
                p.tor = -w * 0.1f
                p.head = w * 0.18f
                p.ua[0] = 2.5f + w * 0.3f; p.ua[1] = 2.5f - w * 0.3f
                p.fa[0] = sin(t * 10f) * 0.35f; p.fa[1] = -sin(t * 10f) * 0.35f
                p.th[0] = 0.1f + 0.14f * l; p.th[1] = 0.1f + 0.14f * r
                p.thS[0] = 1f - 0.25f * l; p.thS[1] = 1f - 0.25f * r
                p.shS[0] = 1f - 0.4f * l; p.shS[1] = 1f - 0.4f * r
                p.ft[0] = w * 0.2f; p.ft[1] = -w * 0.2f
            }
            Move.HOP -> {
                val ph = (t * 1.6f) % 1f
                val up = sin(ph * pi)
                p.dy = -up * 0.12f
                // agacha um pouco antes de pular e ao pousar
                val crouch = max(sstep(1f - ph / 0.18f), sstep((ph - 0.82f) / 0.18f))
                p.sy = 1f - 0.07f * crouch
                p.sx = 1f + 0.05f * crouch
                p.ua[0] = 0.3f + 1.2f * up; p.ua[1] = 0.3f + 1.2f * up
                p.fa[0] = 0.2f; p.fa[1] = 0.2f
                p.th[0] = 0.08f + 0.1f * up; p.th[1] = 0.08f + 0.1f * up
                p.thS[0] = 1f - 0.3f * up; p.thS[1] = 1f - 0.3f * up
                p.shS[0] = 1f - 0.4f * up; p.shS[1] = 1f - 0.4f * up
                p.head = sin(t * 3f) * 0.05f
            }
            Move.SPIN -> {
                val c = kotlin.math.cos(t * 9f)
                p.sx = if (abs(c) < 0.06f) (if (c < 0f) -0.06f else 0.06f) else c
                p.dy = -abs(sin(t * 9f)) * 0.02f
                p.ua[0] = 0.9f; p.ua[1] = 0.9f
                p.fa[0] = 0.2f; p.fa[1] = 0.2f
                p.th[0] = 0.12f; p.th[1] = 0.12f
            }
            Move.STRETCH -> {
                val ph = sstep(t * 2f)
                p.ua[0] = 0.14f + 2.7f * ph; p.ua[1] = 0.14f + 2.7f * ph
                p.fa[0] = 0.1f * ph; p.fa[1] = 0.1f * ph
                p.sy = 1f + 0.04f * ph
                p.tor = sin(t * 5f) * 0.02f * ph
                p.head = -0.15f * ph
            }
            Move.LEAN -> {
                val ph = sstep(t * 2.4f)
                p.tor = 0.18f * ph
                p.dx = 0.02f * ph
                p.ua[0] = 0.14f; p.ua[1] = 0.14f + 0.9f * ph
                p.fa[1] = -1.2f * ph
                p.head = 0.1f * ph
                p.th[0] = 0.05f + 0.06f * ph; p.th[1] = 0.03f
            }
            Move.WALK -> {
                // andar de frente: um pé sai do chão de cada vez, o quadril passa para o pé de apoio,
                // os braços balançam ao contrário e a cabeça fica firme. t aqui é a "fase do passo".
                val c = t * 6.5f
                val w = sin(c)
                val l = bump(w)
                val r = bump(-w)
                p.dy = -w * w * 0.016f
                p.dx = w * 0.008f
                p.rot = -w * 0.03f
                p.tor = w * 0.05f
                p.head = -w * 0.045f
                p.th[0] = 0.05f + 0.11f * l; p.th[1] = 0.05f + 0.11f * r
                p.thS[0] = 1f - 0.26f * l; p.thS[1] = 1f - 0.26f * r
                p.shS[0] = 1f - 0.42f * l; p.shS[1] = 1f - 0.42f * r
                p.ft[0] = -0.14f * l; p.ft[1] = -0.14f * r
                p.ua[0] = 0.17f - w * 0.16f; p.ua[1] = 0.17f + w * 0.16f
                p.fa[0] = 0.1f + 0.14f * bump(-w); p.fa[1] = 0.1f + 0.14f * bump(w)
                p.ha[0] = -w * 0.1f; p.ha[1] = w * 0.1f
            }
            Move.TALK -> {
                p.dy = -abs(sin(t * 9f)) * 0.004f
                p.tor = sin(t * 3f) * 0.04f
                p.head = sin(t * 4.5f) * 0.08f
                p.ua[0] = 0.4f + sin(t * 3f) * 0.25f
                p.fa[0] = 0.5f
                p.ua[1] = 0.9f + sin(t * 4.5f) * 0.35f
                p.fa[1] = 0.8f + sin(t * 4.5f + 1f) * 0.3f
                p.th[0] = 0.03f; p.th[1] = 0.03f
            }
            Move.THINK -> {
                p.head = 0.15f + sin(t * 1.5f) * 0.02f
                p.tor = sin(t * 1.5f) * 0.02f
                p.ua[0] = 0.14f
                p.ua[1] = 0.5f
                p.fa[1] = -2.2f
                p.ha[1] = -0.2f
                p.dy = sin(t * 2f) * 0.002f
            }
            Move.LISTEN -> {
                p.head = 0.16f + sin(t * 2f) * 0.03f
                p.tor = 0.06f
                p.ua[0] = 0.14f
                p.ua[1] = 1.0f
                p.fa[1] = -1.6f
                p.dy = sin(t * 3f) * 0.002f
            }
            Move.SIT -> {
                // sentada na beirada: as coxas vêm para a frente (encurtadas) e as pernas balançam
                val k = sin(t * 2.4f)
                p.dy = sin(t * 1.5f) * 0.002f
                p.tor = sin(t * 1.2f) * 0.03f
                p.head = sin(t * 1.0f) * 0.06f
                p.ua[0] = 0.3f; p.ua[1] = 0.3f
                p.fa[0] = 0.4f; p.fa[1] = 0.4f
                p.th[0] = 0.14f; p.th[1] = 0.14f
                p.thS[0] = 0.35f; p.thS[1] = 0.35f
                p.sh[0] = 0.12f + k * 0.14f; p.sh[1] = 0.12f - k * 0.14f
                p.ft[0] = k * 0.2f; p.ft[1] = -k * 0.2f
            }
            Move.FLY -> {
                // flutuando: sobe e desce devagar e cada parte do corpo chega um pouquinho depois da outra
                val f = 1.9f
                val b = sin(t * f)
                val b1 = sin(t * f - 0.8f)
                val b2 = sin(t * f - 1.5f)
                val b3 = sin(t * f - 2.2f)
                p.dy = b * 0.03f
                p.dx = sin(t * 0.8f) * 0.008f
                p.rot = sin(t * 1.1f) * 0.045f
                p.tor = b1 * 0.035f
                p.head = sin(t * 1.3f - 0.8f) * 0.06f
                p.ua[0] = 0.62f + b1 * 0.17f; p.ua[1] = 0.62f + b2 * 0.17f
                p.fa[0] = 0.3f + b2 * 0.13f; p.fa[1] = 0.3f + b3 * 0.13f
                p.ha[0] = b3 * 0.2f; p.ha[1] = sin(t * f - 2.8f) * 0.2f
                p.th[0] = 0.06f + b2 * 0.07f; p.th[1] = 0.06f - b3 * 0.06f
                p.sh[0] = 0.16f + b3 * 0.1f; p.sh[1] = 0.16f - b2 * 0.1f
                p.thS[0] = 0.92f; p.thS[1] = 0.92f
                p.shS[0] = 0.84f; p.shS[1] = 0.84f
                p.ft[0] = 0.36f + b3 * 0.1f; p.ft[1] = 0.36f - b3 * 0.1f
            }
            Move.SHRUG -> {
                val ph = sstep(t * 3.2f) * (1f - sstep((t - 1.4f) * 2.4f))
                p.dy = -0.01f * ph
                p.head = 0.1f * ph
                p.ua[0] = 0.14f + 0.5f * ph; p.ua[1] = 0.14f + 0.5f * ph
                p.fa[0] = 1.3f * ph; p.fa[1] = 1.3f * ph
                p.ha[0] = 0.4f * ph; p.ha[1] = 0.4f * ph
            }
            Move.CHEER -> {
                val ph = (t * 2.4f) % 1f
                val up = sin(ph * pi)
                p.dy = -up * 0.06f
                p.ua[0] = 2.6f + sin(t * 9f) * 0.12f; p.ua[1] = 2.6f - sin(t * 9f) * 0.12f
                p.fa[0] = sin(t * 9f) * 0.2f; p.fa[1] = -sin(t * 9f) * 0.2f
                p.th[0] = 0.08f + 0.1f * up; p.th[1] = 0.08f + 0.1f * up
                p.thS[0] = 1f - 0.25f * up; p.thS[1] = 1f - 0.25f * up
                p.shS[0] = 1f - 0.35f * up; p.shS[1] = 1f - 0.35f * up
                p.head = -0.12f
            }
        }
    }
}

/**
 * Faz a pose mostrada "seguir" a pose calculada com molas. O corpo começa e para suave, as pontas
 * (mãos, cabeça) chegam um pouco depois do resto e passam um tiquinho do ponto, como algo com peso.
 * Trocar de movimento também fica suave sozinho, sem emenda.
 */
class PoseSmoother {
    private val x = FloatArray(N)
    private val v = FloatArray(N)
    private val tg = FloatArray(N)
    private var ready = false

    fun reset() {
        ready = false
    }

    private fun flat(p: RigPose, o: FloatArray) {
        o[0] = p.dx; o[1] = p.dy; o[2] = p.rot; o[3] = p.sx; o[4] = p.sy; o[5] = p.tor; o[6] = p.head
        for (k in 0..1) {
            o[7 + k] = p.ua[k]; o[9 + k] = p.fa[k]; o[11 + k] = p.ha[k]
            o[13 + k] = p.th[k]; o[15 + k] = p.sh[k]; o[17 + k] = p.ft[k]
            o[19 + k] = p.thS[k]; o[21 + k] = p.shS[k]
        }
    }

    private fun unflat(o: FloatArray, p: RigPose) {
        p.dx = o[0]; p.dy = o[1]; p.rot = o[2]; p.sx = o[3]; p.sy = o[4]; p.tor = o[5]; p.head = o[6]
        for (k in 0..1) {
            p.ua[k] = o[7 + k]; p.fa[k] = o[9 + k]; p.ha[k] = o[11 + k]
            p.th[k] = o[13 + k]; p.sh[k] = o[15 + k]; p.ft[k] = o[17 + k]
            p.thS[k] = o[19 + k]; p.shS[k] = o[21 + k]
        }
    }

    /** Avança dt segundos em direção a target e escreve o resultado em out. */
    fun step(target: RigPose, dt: Float, out: RigPose) {
        flat(target, tg)
        if (!ready) {
            for (i in 0 until N) {
                x[i] = tg[i]
                v[i] = 0f
            }
            ready = true
        } else {
            val total = min(0.05f, max(0f, dt))
            val n = max(1, kotlin.math.ceil(total / 0.008f).toInt())
            val h = total / n
            for (s in 0 until n) {
                for (i in 0 until N) {
                    val w = W[i]
                    val a = w * w * (tg[i] - x[i]) - 2f * Z[i] * w * v[i]
                    v[i] += a * h
                    x[i] += v[i] * h
                }
            }
        }
        unflat(x, out)
    }

    companion object {
        const val N = 23

        // frequência (rad/s) e amortecimento de cada valor da pose: quanto mais longe do tronco, mais atrasado e mais solto
        private val W = floatArrayOf(
            22f, 28f, 16f, 34f, 30f, 14f, 11f,
            15f, 15f, 13f, 13f, 11f, 11f,
            22f, 22f, 20f, 20f, 17f, 17f,
            26f, 26f, 24f, 24f
        )
        private val Z = floatArrayOf(
            1f, 0.9f, 1f, 1f, 0.85f, 0.9f, 0.7f,
            0.85f, 0.85f, 0.7f, 0.7f, 0.6f, 0.6f,
            1f, 1f, 0.9f, 0.9f, 0.75f, 0.75f,
            1f, 1f, 1f, 1f
        )
    }
}
