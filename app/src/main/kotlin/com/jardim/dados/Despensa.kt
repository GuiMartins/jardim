package com.jardim.dados

import com.jardim.avisos.Preferencias
import com.jardim.motor.agenda.Compra
import com.jardim.motor.agenda.INSUMOS_DE_DRENAGEM
import com.jardim.motor.agenda.Insumo
import com.jardim.motor.agenda.ItemPreparo
import com.jardim.motor.agenda.faltaNoSubstrato
import com.jardim.motor.agenda.listaDeCompras
import com.jardim.motor.agenda.resolvidosPelaDespensa
import com.jardim.motor.modelo.FormaInicio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Um item da lista de compras que não é insumo: muda, sementes, vaso, ou o
 * substrato de uma espécie cujo texto o insumos.py não entendeu.
 */
data class OutraCompra(
    val planejadoId: Long,
    val item: ItemPreparo,
    val planta: String,
    val formaInicio: FormaInicio,
    val volumeLitros: Double?,
)

data class ListaDeCompras(val insumos: List<Compra>, val outras: List<OutraCompra>) {
    val vazia: Boolean get() = insumos.isEmpty() && outras.isEmpty()
}

/**
 * Minha despensa: o que a pessoa tem em casa, cruzado com o que as plantas de
 * "Para plantar" ainda pedem. Os índices do catálogo são lidos do disco: as
 * leituras rodam no Dispatchers.IO.
 */
class Despensa(
    private val preferencias: Preferencias,
    private val repositorio: JardimRepositorio,
    private val catalogo: Catalogo,
) {
    val itens: Flow<Set<Insumo>> = preferencias.despensa

    /**
     * Marcar um insumo tira "substrato" e "drenagem" das plantas planejadas que
     * só esperavam por ele. Desmarcar não volta atrás: quem já tem o substrato
     * pronto em casa não precisa de perlita de novo.
     */
    suspend fun mudar(insumo: Insumo, tem: Boolean) {
        preferencias.mudarDespensa(insumo, tem)
        if (tem) resolverPlanejados()
    }

    /** "Comprei" num item que não é insumo: sai do que falta naquela planta. */
    suspend fun comprei(compra: OutraCompra) {
        val planejado = repositorio.planejados.first().firstOrNull { it.id == compra.planejadoId } ?: return
        repositorio.mudarPreparo(planejado.id, planejado.faltam - compra.item, planejado.local)
    }

    private suspend fun resolverPlanejados() {
        val tenho = preferencias.despensa.first()
        val planejados = repositorio.planejados.first()
        withContext(Dispatchers.IO) {
            planejados.forEach { p ->
                val resolvidos = resolvidosPelaDespensa(catalogo.item(p.arquivo)?.substrato, ItemPreparo.DRENAGEM in p.faltam, tenho)
                if (p.faltam.any { it in resolvidos }) repositorio.mudarPreparo(p.id, p.faltam - resolvidos, p.local)
            }
        }
    }

    val compras: Flow<ListaDeCompras> = combine(repositorio.planejados, preferencias.despensa) { planejados, tenho ->
        val pedidos = mutableListOf<Pair<String, List<Insumo>>>()
        val outras = mutableListOf<OutraCompra>()
        planejados.forEach { p ->
            val item = catalogo.item(p.arquivo)
            fun outra(i: ItemPreparo) = OutraCompra(p.id, i, p.nome, p.formaInicio, item?.volumeMinimoLitros)
            if (ItemPreparo.INICIO in p.faltam) outras += outra(ItemPreparo.INICIO)
            if (ItemPreparo.VASO in p.faltam) outras += outra(ItemPreparo.VASO)
            if (ItemPreparo.SUBSTRATO in p.faltam) {
                val falta = faltaNoSubstrato(item?.substrato, tenho)
                if (falta == null) outras += outra(ItemPreparo.SUBSTRATO) else falta.forEach { pedidos += p.nome to it }
            }
            if (ItemPreparo.DRENAGEM in p.faltam && INSUMOS_DE_DRENAGEM.none { it in tenho }) {
                pedidos += p.nome to INSUMOS_DE_DRENAGEM
            }
        }
        ListaDeCompras(listaDeCompras(pedidos), outras)
    }.flowOn(Dispatchers.IO)
}
