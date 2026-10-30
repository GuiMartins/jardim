package com.jardim.motor.catalogo

import com.jardim.motor.agenda.foraDeEpoca
import com.jardim.motor.modelo.Dificuldade
import com.jardim.motor.modelo.Luminosidade
import java.time.LocalDate

/** Quanto sol direto bate no lugar, nas faixas que a tela oferece (horas por dia). */
enum class Sol(val de: Int, val ate: Int) {
    NENHUM(0, 0),
    POUCO(1, 3),
    MEIO(4, 5),
    MUITO(6, 24),
}

/** O maior vaso que cabe no lugar. Canteiro não tem limite. */
enum class Vaso(val ateLitros: Double) {
    PEQUENO(3.0),
    MEDIO(10.0),
    GRANDE(30.0),
    CANTEIRO(Double.MAX_VALUE),
}

/** O que a pessoa respondeu na descoberta; null e vazio = sem filtro. */
data class FiltroDescoberta(
    val sol: Sol? = null,
    val vaso: Vaso? = null,
    val soFaceis: Boolean = false,
    val soNaEpoca: Boolean = false,
    val categorias: Set<String> = emptySet(),
)

/**
 * O resultado da descoberta: as plantas que cabem, da mais popular à menos, e
 * as categorias delas com a contagem, para os chips. A contagem ignora o
 * filtro de categoria: marcar "Hortaliça" não zera os outros chips.
 */
data class Descoberta(val plantas: List<ItemCatalogo>, val categorias: List<Pair<String, Int>>)

/**
 * A planta aguenta o sol do lugar: o mínimo dela cabe na faixa e, se é de
 * meia-sombra, a faixa não passa de uma hora além do que ela pede, porque
 * samambaia no sol da tarde queima.
 */
fun cabeNoSol(item: ItemCatalogo, sol: Sol): Boolean {
    val minimo = item.horasSolMinimas ?: return true
    if (minimo > sol.ate) return false
    if (item.luminosidade == Luminosidade.SOL_DIRETO) return true
    val recomendadas = item.horasSolDiariasRecomendadas ?: return true
    return recomendadas + 1 >= sol.de
}

fun cabeNoVaso(item: ItemCatalogo, vaso: Vaso): Boolean = (item.volumeMinimoLitros ?: 0.0) <= vaso.ateLitros

/**
 * [calendarioVale] é falso fora do Centro-Sul: lá os meses do catálogo não
 * valem, e "só na época" é ignorado em vez de esconder plantas sem motivo.
 */
fun descobrir(itens: List<ItemCatalogo>, filtro: FiltroDescoberta, hoje: LocalDate, calendarioVale: Boolean): Descoberta {
    val cabem = itens.filter { item ->
        (filtro.sol == null || cabeNoSol(item, filtro.sol)) &&
            (filtro.vaso == null || cabeNoVaso(item, filtro.vaso)) &&
            (!filtro.soFaceis || item.dificuldade == Dificuldade.FACIL) &&
            (!filtro.soNaEpoca || !calendarioVale || !foraDeEpoca(item.mesesPlantio, hoje))
    }
    val categorias = cabem.mapNotNull { it.categoria }
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    val plantas = cabem
        .filter { filtro.categorias.isEmpty() || it.categoria in filtro.categorias }
        .sortedWith(compareBy<ItemCatalogo, Int?>(nullsLast()) { it.posicao }.thenBy { it.nomeComum })
    return Descoberta(plantas, categorias)
}
