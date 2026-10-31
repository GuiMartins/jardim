package com.jardim.motor.agenda

/**
 * O que vai no substrato, com os códigos de dados/insumos.json (o
 * `insumos.py` extrai do `substrato_recomendado` de cada espécie). A despensa
 * grava pelo nome no DataStore: renomear apaga a marca de quem já tinha.
 */
enum class Insumo {
    SUBSTRATO_CACTOS,
    SUBSTRATO_ORQUIDEAS,
    SUBSTRATO_ACIDO,
    SUBSTRATO_COMERCIAL,
    TERRA_VEGETAL,
    HUMUS,
    COMPOSTO,
    ESTERCO,
    TURFA,
    MUSGO_ESFAGNO,
    FIBRA_DE_COCO,
    CASCA_DE_PINUS,
    CASCA_DE_ARROZ,
    CARVAO,
    PERLITA,
    VERMICULITA,
    AREIA,
    ARGILA_EXPANDIDA,
    PEDRISCO,
    CALCARIO,
    FARINHA_DE_OSSO,
    TORTA_DE_MAMONA,
    BOKASHI,
    CINZA,
    ADUBO_LIBERACAO_LENTA,
}

/**
 * Uma receita de substrato: cada grupo são os insumos que servem um no lugar
 * do outro ("composto ou húmus"). Uma espécie pode ter mais de uma receita
 * ("substrato para cactos, ou terra, areia e perlita").
 */
typealias Receita = List<List<Insumo>>

/** Para a camada no fundo do vaso, qualquer um dos dois serve. */
val INSUMOS_DE_DRENAGEM: List<Insumo> = listOf(Insumo.ARGILA_EXPANDIDA, Insumo.PEDRISCO)

/**
 * Os grupos que faltam na receita mais fácil de completar com o que se tem; no
 * empate, a que usa mais do que já está em casa (com terra e areia, comprar
 * perlita, e não o substrato pronto para cactos). Vazio: tem tudo. Null: a
 * espécie não tem receita extraída, e o app não sabe.
 */
fun faltaNoSubstrato(receitas: List<Receita>?, tenho: Set<Insumo>): List<List<Insumo>>? {
    if (receitas.isNullOrEmpty()) return null
    val melhor = receitas.minWith(
        compareBy<Receita> { receita -> receita.count { grupo -> grupo.none { it in tenho } } }
            .thenByDescending { receita -> receita.count { grupo -> grupo.any { it in tenho } } },
    )
    return melhor.filter { grupo -> grupo.none { it in tenho } }
}

/** Os itens do preparo que a despensa já resolve. */
fun resolvidosPelaDespensa(receitas: List<Receita>?, pedeDrenagem: Boolean, tenho: Set<Insumo>): Set<ItemPreparo> = buildSet {
    if (faltaNoSubstrato(receitas, tenho)?.isEmpty() == true) add(ItemPreparo.SUBSTRATO)
    if (pedeDrenagem && INSUMOS_DE_DRENAGEM.any { it in tenho }) add(ItemPreparo.DRENAGEM)
}

/**
 * Um insumo para comprar, as plantas que precisam dele e o que serviria no
 * lugar para todas elas (a drenagem aceita argila expandida ou pedrisco).
 */
data class Compra(val insumo: Insumo, val para: List<String>, val ou: List<Insumo> = emptyList())

/**
 * Junta o que falta de várias plantas, cada pedido sendo (planta, grupo que
 * falta). Escolhe primeiro o insumo que resolve mais pedidos: se o manjericão
 * aceita "perlita ou casca de pinus" e o alecrim pede perlita, a lista diz só
 * "perlita: manjericão, alecrim". No empate, vale o que o texto da espécie
 * cita primeiro ("perlita ou casca de arroz": perlita).
 */
fun listaDeCompras(pedidos: List<Pair<String, List<Insumo>>>): List<Compra> {
    val pendentes = pedidos.filter { it.second.isNotEmpty() }.toMutableList()
    val compras = mutableListOf<Compra>()
    while (pendentes.isNotEmpty()) {
        val insumo = Insumo.entries.minWith(
            compareByDescending<Insumo> { insumo -> pendentes.count { insumo in it.second } }
                .thenBy { insumo -> pendentes.sumOf { (_, grupo) -> grupo.indexOf(insumo).let { if (it < 0) grupo.size else it } } },
        )
        val atendidos = pendentes.filter { insumo in it.second }
        val ou = atendidos.map { it.second.toSet() }.reduce { a, b -> a intersect b } - insumo
        compras += Compra(insumo, atendidos.map { it.first }.distinct(), Insumo.entries.filter { it in ou })
        pendentes -= atendidos.toSet()
    }
    return compras
}
