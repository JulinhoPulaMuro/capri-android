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

    fun pose(m: Move, t: Float, p: RigPose) {
        p.reset()
        val pi = PI.toFloat()
        when (m) {
            Move.IDLE -> {
                val w = sin(t * 2.2f)
                p.dy = w * 0.003f
                p.tor = sin(t * 1.1f) * 0.02f
                p.head = sin(t * 0.9f) * 0.04f
                p.ua[0] = 0.14f + w * 0.03f; p.ua[1] = 0.14f - w * 0.03f
                p.fa[0] = 0.06f; p.fa[1] = 0.06f
                p.th[0] = 0.03f; p.th[1] = 0.03f
            }
            Move.WAVE -> {
                p.dy = sin(t * 3f) * 0.002f
                p.tor = -0.04f
                p.head = 0.12f + sin(t * 2f) * 0.03f
                p.ua[0] = 0.14f
                p.ua[1] = 2.45f + sin(t * 3f) * 0.08f
                p.fa[1] = sin(t * 11f) * 0.55f
                p.ha[1] = sin(t * 11f + 0.6f) * 0.3f
                p.th[0] = 0.03f; p.th[1] = 0.03f
            }
            Move.DANCE -> {
                // passos de lado, levantando um pé de cada vez
                val w = sin(t * 6f)
                val l = max(0f, w)
                val r = max(0f, -w)
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
                val l = max(0f, w)
                val r = max(0f, -w)
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
                if (ph < 0.15f || ph > 0.85f) {
                    p.sy = 0.93f
                    p.sx = 1.05f
                }
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
                val ph = min(1f, t * 2.5f)
                p.ua[0] = 0.14f + 2.7f * ph; p.ua[1] = 0.14f + 2.7f * ph
                p.fa[0] = 0.1f * ph; p.fa[1] = 0.1f * ph
                p.sy = 1f + 0.04f * ph
                p.tor = sin(t * 5f) * 0.02f * ph
                p.head = -0.15f * ph
            }
            Move.LEAN -> {
                val ph = min(1f, t * 3f)
                p.tor = 0.18f * ph
                p.dx = 0.02f * ph
                p.ua[0] = 0.14f; p.ua[1] = 0.14f + 0.9f * ph
                p.fa[1] = -1.2f * ph
                p.head = 0.1f * ph
                p.th[0] = 0.05f + 0.06f * ph; p.th[1] = 0.03f
            }
            Move.WALK -> {
                // andar de frente: levanta um pé de cada vez e balança o corpo para o lado do pé que fica
                val w = sin(t * 6.5f)
                val l = max(0f, w)
                val r = max(0f, -w)
                p.dy = -abs(w) * 0.012f
                p.rot = -w * 0.035f
                p.tor = w * 0.04f
                p.head = -w * 0.05f
                p.th[0] = 0.05f + 0.1f * l; p.th[1] = 0.05f + 0.1f * r
                p.thS[0] = 1f - 0.25f * l; p.thS[1] = 1f - 0.25f * r
                p.shS[0] = 1f - 0.4f * l; p.shS[1] = 1f - 0.4f * r
                p.ft[0] = -0.12f * l; p.ft[1] = -0.12f * r
                p.ua[0] = 0.16f - w * 0.14f; p.ua[1] = 0.16f + w * 0.14f
                p.fa[0] = 0.1f; p.fa[1] = 0.1f
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
                // flutuando: bem leve, braços um pouco abertos, pés caídos
                p.dy = sin(t * 2.4f) * 0.025f
                p.rot = sin(t * 1.3f) * 0.04f
                p.tor = sin(t * 1.9f) * 0.03f
                p.head = sin(t * 1.6f) * 0.06f
                p.ua[0] = 0.7f + sin(t * 3f) * 0.15f; p.ua[1] = 0.7f - sin(t * 3f) * 0.15f
                p.fa[0] = 0.25f; p.fa[1] = 0.25f
                p.th[0] = 0.05f + sin(t * 2.4f) * 0.05f; p.th[1] = 0.05f - sin(t * 2.4f) * 0.05f
                p.thS[0] = 0.9f; p.thS[1] = 0.9f
                p.shS[0] = 0.85f; p.shS[1] = 0.85f
                p.ft[0] = 0.3f; p.ft[1] = 0.3f
            }
            Move.SHRUG -> {
                val ph = min(1f, t * 4f) * (1f - min(1f, max(0f, t - 1.4f) * 3f))
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
