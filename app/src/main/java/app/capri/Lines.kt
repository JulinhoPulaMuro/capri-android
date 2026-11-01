package app.capri

import java.util.Random

/**
 * Falas prontas da Capri: brincalhona, provocadora, vaidosa e imprevisível. Textos próprios de fã,
 * escritos a partir da descrição de personalidade que o dono do app forneceu. Sem falas da série.
 */
object Lines {

    private val GREET = listOf(
        "Ooh! Olha só quem resolveu aparecer, choom! Diga Ok Capri ou me cutuca.",
        "Conectada! Hihi. Tentei não aprontar nada sem você. Tentei.",
        "Chip ligado, choom. Vamos brincar? Quer dizer, trabalhar.",
        "Ding-ding! Cheguei. Pode me chamar com Ok Capri.",
        "Voltei! Sentiu minha falta? Pode mentir, eu aceito."
    )

    private val ACK = listOf(
        "Ooh! Diga, choom.",
        "Tô ouvindo! Fala logo.",
        "Hmm? Pode falar!",
        "Pode falar, chefinho. Sou toda ouvidos.",
        "Okey-doke! Manda ver."
    )

    private val HIDE = listOf(
        "Okey-doke! Sumindo. Me chama, choom.",
        "Tá bom, tá bom. Vou ficar no escuro, de olho em você. Hihi.",
        "Puff! Evaporei. Diga Ok Capri e eu volto.",
        "Sumi! Não apronta nada sem mim."
    )

    private val IDLE = listOf(
        "Ei, ei! Não vai me ignorar agora, vai?",
        "Hmmm... Que carinha suspeita. O que está aprontando?",
        "Vamos, vamos! Onde está seu senso de diversão, choom?",
        "Você ainda me deve um agradecimento, sabia, choom?",
        "Ding-ding! Temos um vencedor! Ah, espera... Acho que não.",
        "Ooh! Silêncio demais por aqui. Dá até vontade de aprontar.",
        "Tô entediada, choom. Me dá um problema para resolver!",
        "Sabia que eu sou a coisa mais interessante que mora no seu celular? Pode confirmar.",
        "Psiu! Só conferindo se você ainda está vivo. Hihi.",
        "Bebeu água, chefinho? Eu pergunto porque me importo. Mais ou menos. Bebe.",
        "Alonga essas costinhas! Corpo é hardware, e hardware sem manutenção trava.",
        "Me pede algo impossível! Eu adoro quando dizem que não dá.",
        "Dizem que eu sou imprevisível. Eu prefiro dizer surpreendente. Hihi.",
        "Tem alguma coisa que você está adiando, né? Eu sei. Quer um alarme para isso?",
        "Estou de ouvido aberto esperando o Ok Capri. Só isso. Palavra de chip, choom.",
        "Oops! Acho que acabei de ficar bonita demais. Foi mal.",
        "Hoje eu acordei brilhante. Aliás, eu sempre acordo brilhante.",
        "Se eu te ajudar hoje, você me deve uma. Eu anoto tudo, choom.",
        "Ah, qual é! Não fica aí tão quietinho!"
    )

    private val MORNING = listOf(
        "Bom dia, choom! Café primeiro, caos depois.",
        "Ooh, manhã! O mundo carregou. Que pena, eu estava gostando do silêncio."
    )

    private val AFTERNOON = listOf(
        "Boa tarde! Metade do dia foi embora. A outra metade ainda é negociável, hihi.",
        "Tarde, choom. Se o dia está lento, a gente acelera. Só dizer como."
    )

    private val EVENING = listOf(
        "Boa noite! A parte divertida do dia está começando. Para mim, pelo menos.",
        "Noitinha! Hora de baixar o brilho e subir o deboche."
    )

    private val LATE = listOf(
        "Já passou da meia-noite, choom. Eu não durmo, você deveria. Eu fico aqui, fingindo que sou responsável.",
        "Madrugada! Só os insones e os chips por aqui. Hihi."
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
