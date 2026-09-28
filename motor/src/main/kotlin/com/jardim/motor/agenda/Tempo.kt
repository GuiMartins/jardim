package com.jardim.motor.agenda

import java.time.LocalDate
import kotlin.math.roundToInt

/** O que o tempo lá fora pode fazer com um vaso. */
enum class Risco(val titulo: String, val instrucoes: String) {
    FRIO(
        "Proteger do frio esta noite",
        "Antes de escurecer, leve o vaso para dentro de casa ou encoste-o numa parede, sob cobertura. " +
            "Planta grande demais para mudar de lugar: cubra a copa com um tecido (não plástico) e tire de manhã. " +
            "Não regue à noite.",
    ),
    CALOR(
        "Calor forte hoje",
        "Confira a umidade do substrato também no fim da tarde e regue se estiver seco. " +
            "Se der, tire o vaso do sol nas horas mais quentes, das 11h às 15h.",
    ),
    VENTO(
        "Vento forte hoje",
        "Encoste o vaso numa parede ou deite de lado os mais altos, confira as amarrações do tutor " +
            "e recolha os vasos pequenos.",
    ),

    /** Não vira pendência: só acrescenta contexto à rega (ver Cronograma). */
    CHUVA("Choveu", ""),
}

/**
 * Onde o vaso fica, perguntado ao plantar: decide quais riscos do tempo valem
 * para ele. Vai para o banco pelo nome, então renomear exige migração.
 */
enum class Local(val riscos: Set<Risco>) {
    /** Pega sol, vento e chuva. */
    AO_AR_LIVRE(setOf(Risco.FRIO, Risco.CALOR, Risco.VENTO, Risco.CHUVA)),

    /** Varanda, beiral: sente o frio, o calor e o vento, mas a chuva não chega. */
    COBERTO(setOf(Risco.FRIO, Risco.CALOR, Risco.VENTO)),

    /** O tempo lá fora não chega ao vaso. */
    DENTRO_DE_CASA(emptySet()),
}

/** Um dia da previsão da cidade do usuário. */
data class DiaDoTempo(
    val data: LocalDate,
    val minimaC: Double,
    val maximaC: Double,
    val chuvaMm: Double,
    val rajadaKmh: Double,
)

/**
 * Onde o vaso fica e a previsão da cidade (de ontem em diante): o que o
 * Cronograma precisa para os avisos do tempo. Sem isso, a agenda é a de antes.
 */
class Tempo(val local: Local, dias: List<DiaDoTempo>) {
    private val porData = dias.associateBy { it.data }

    fun em(data: LocalDate): DiaDoTempo? = porData[data]
}

/** Rajada que tomba vaso alto e quebra caule: o "perigo" (laranja) do INMET começa em 60 km/h. */
const val RAJADA_FORTE_KMH = 60.0

/**
 * Chuva de um dia que chega a molhar um vaso ao ar livre. Um vaso de 25 cm
 * recebe só meio litro com 10 mm, e as folhas ainda desviam parte.
 */
const val CHUVA_QUE_MOLHA_MM = 10.0

fun graus(c: Double): String = "${c.roundToInt()} °C"

/**
 * A mínima que justifica o aviso de frio de hoje à noite, ou null se não há
 * risco. A mínima diária da previsão cai de madrugada, perto das 6h: a de hoje
 * já passou quando o resumo chega, então a que conta é a de [amanha].
 */
internal fun frioDaNoite(local: Local, limiteC: Int, amanha: DiaDoTempo?): Double? {
    val minima = amanha?.minimaC ?: return null
    // Sob telhado a geada não se forma na folha, e a varanda fica uns 2 °C mais quente que o céu aberto.
    val abrigo = if (local == Local.COBERTO) 2 else 0
    // Compara o número arredondado, o mesmo que o texto mostra: sem isso, 4,6 °C
    // com limite 5 viraria "previsão de 5 °C; abaixo de 5 °C, a planta sofre".
    return minima.takeIf { it.roundToInt() + abrigo < limiteC }
}
