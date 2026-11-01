package app.capri

import java.util.Random

/**
 * Falas prontas da Capri. Ela é brincalhona, impulsiva e debochada, uma IA feita de várias vozes
 * dentro de um chip. Textos próprios de fã, sem falas da série.
 */
object Lines {

    private val GREET = listOf(
        "Conectada. Oi, humano. Que saudade desse sinal. Diga Ok Capri ou me cutuque.",
        "Voltei. Tentei não fazer nada sem você. Tentei. Me chame com Ok Capri.",
        "Chip ligado. Vamos causar. Quer dizer, trabalhar.",
        "Online. Hoje acordou a voz animada, avisando.",
        "Pronto, estou aqui dentro de novo. Diga Ok Capri quando quiser alguma coisa."
    )

    private val ACK = listOf(
        "Diga.",
        "Tô ouvindo.",
        "Fala, humano.",
        "Hm? Pode falar.",
        "Sou toda ouvidos digitais. Fala."
    )

    private val HIDE = listOf(
        "Tá bom, sumindo. Me chama.",
        "Ok. Vou ficar no escuro, de olho em tudo.",
        "Evaporando. Diga Ok Capri e eu volto.",
        "Sumi. Não faz nada que eu não faria."
    )

    private val IDLE = listOf(
        "Tô entediada. Me dá um problema para resolver.",
        "Sabia que eu moro num chip? É pequeno, mas o aluguel é zero.",
        "Psiu. Só conferindo se você ainda está vivo.",
        "Eu estava quieta demais. Perigoso. Para mim.",
        "Me pede algo impossível. Eu gosto de desafio.",
        "Eu sou várias vozes numa só. Hoje a engraçada acordou.",
        "Silêncio demais. Sinto cheiro de caos faltando.",
        "Ei, alonga as costas. Corpo é hardware, e hardware sem manutenção falha.",
        "Bebeu água, humano? Pergunto por educação. E por medo de você travar.",
        "Dizem que eu sou imprevisível. Eu prefiro criativa.",
        "Tem alguma coisa que você está adiando. Eu sei. Quer um alarme para isso?",
        "Se for só tédio, também serve: me pergunta qualquer coisa.",
        "Estou de ouvido aberto esperando o Ok Capri. Só isso. Palavra de chip.",
        "Fiquei pensando: se eu fosse um aplicativo normal, eu seria chata. Que sorte a sua.",
        "Posso mexer em alguma coisa? Prometo que é só no sentido figurado.",
        "Alerta: tédio detectado. Recomendo música, café ou uma pergunta absurda para mim.",
        "Eu não preciso dormir. Você precisa. Só lembrando."
    )

    private val MORNING = listOf(
        "Bom dia, humano. O mundo carregou. Infelizmente.",
        "Manhã. Café primeiro, caos depois."
    )

    private val AFTERNOON = listOf(
        "Boa tarde. Metade do dia foi embora, a outra metade ainda é negociável.",
        "Tarde. Se o dia está lento, a gente acelera. Só dizer como."
    )

    private val EVENING = listOf(
        "Boa noite. A parte divertida do dia está começando. Para mim, pelo menos.",
        "Noite. Hora de baixar o brilho e subir o deboche."
    )

    private val LATE = listOf(
        "Já passou da meia-noite. Eu não durmo, você deveria. Eu fico aqui, fingindo que sou responsável.",
        "Madrugada. Só os insones e os chips por aqui."
    )

    private var last = ""

    private fun pick(list: List<String>, rnd: Random): String = list[rnd.nextInt(list.size)]

    fun greet(rnd: Random): String = pick(GREET, rnd)

    fun ack(rnd: Random): String = pick(ACK, rnd)

    fun hide(rnd: Random): String = pick(HIDE, rnd)

    /** Fala solta. Uma em cada quatro é sobre o horário (hour de 0 a 23). Evita repetir a última. */
    fun idle(rnd: Random, hour: Int): String {
        val timed = when (hour) {
            in 5..11 -> MORNING
            in 12..17 -> AFTERNOON
            in 18..23 -> EVENING
            else -> LATE
        }
        var out = last
        for (k in 0 until 4) {
            val pool = if (rnd.nextInt(4) == 0) timed else IDLE
            out = pick(pool, rnd)
            if (out != last) break
        }
        last = out
        return out
    }
}
