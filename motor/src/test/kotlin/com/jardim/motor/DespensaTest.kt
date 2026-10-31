package com.jardim.motor

import com.jardim.motor.agenda.Compra
import com.jardim.motor.agenda.Insumo
import com.jardim.motor.agenda.Insumo.AREIA
import com.jardim.motor.agenda.Insumo.CASCA_DE_PINUS
import com.jardim.motor.agenda.Insumo.COMPOSTO
import com.jardim.motor.agenda.Insumo.FARINHA_DE_OSSO
import com.jardim.motor.agenda.Insumo.HUMUS
import com.jardim.motor.agenda.Insumo.PEDRISCO
import com.jardim.motor.agenda.Insumo.PERLITA
import com.jardim.motor.agenda.Insumo.SUBSTRATO_CACTOS
import com.jardim.motor.agenda.Insumo.TERRA_VEGETAL
import com.jardim.motor.agenda.ItemPreparo
import com.jardim.motor.agenda.faltaNoSubstrato
import com.jardim.motor.agenda.listaDeCompras
import com.jardim.motor.agenda.resolvidosPelaDespensa
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DespensaTest {

    // "40% terra vegetal, 30% composto orgânico ou húmus de minhoca, 20% areia
    // grossa, 10% perlita ou casca de pinus fina", como o insumos.py extrai.
    private val manjericao = listOf(listOf(listOf(TERRA_VEGETAL), listOf(COMPOSTO, HUMUS), listOf(AREIA), listOf(PERLITA, CASCA_DE_PINUS)))

    // "Substrato para cactos e suculentas, ou 40% terra vegetal, 40% areia grossa, 20% perlita".
    private val cacto = listOf(listOf(listOf(SUBSTRATO_CACTOS)), listOf(listOf(TERRA_VEGETAL), listOf(AREIA), listOf(PERLITA)))

    @Test
    fun `qualquer alternativa do grupo serve`() {
        assertEquals(listOf(listOf(PERLITA, CASCA_DE_PINUS)), faltaNoSubstrato(manjericao, setOf(TERRA_VEGETAL, HUMUS, AREIA)))
        assertEquals(emptyList(), faltaNoSubstrato(manjericao, setOf(TERRA_VEGETAL, COMPOSTO, AREIA, CASCA_DE_PINUS)))
    }

    @Test
    fun `vale a receita mais fácil de completar`() {
        assertEquals(listOf(listOf(SUBSTRATO_CACTOS)), faltaNoSubstrato(cacto, emptySet()))
        assertEquals(listOf(listOf(PERLITA)), faltaNoSubstrato(cacto, setOf(TERRA_VEGETAL, AREIA)))
        assertEquals(emptyList(), faltaNoSubstrato(cacto, setOf(SUBSTRATO_CACTOS)))
    }

    @Test
    fun `sem receita o app não sabe, e planta de ar não pede nada`() {
        assertNull(faltaNoSubstrato(null, setOf(TERRA_VEGETAL)))
        assertEquals(emptyList(), faltaNoSubstrato(listOf(emptyList()), emptySet()))
    }

    @Test
    fun `a despensa resolve substrato e drenagem`() {
        val tudo = setOf(TERRA_VEGETAL, HUMUS, AREIA, PERLITA, PEDRISCO)
        assertEquals(setOf(ItemPreparo.SUBSTRATO, ItemPreparo.DRENAGEM), resolvidosPelaDespensa(manjericao, pedeDrenagem = true, tudo))
        assertEquals(setOf(ItemPreparo.SUBSTRATO), resolvidosPelaDespensa(manjericao, pedeDrenagem = false, tudo))
        assertEquals(emptySet(), resolvidosPelaDespensa(null, pedeDrenagem = false, tudo))
    }

    @Test
    fun `a lista junta o insumo que serve para mais plantas`() {
        val pedidos = listOf(
            "Manjericão" to listOf(PERLITA, CASCA_DE_PINUS),
            "Manjericão" to listOf(AREIA),
            "Alecrim" to listOf(PERLITA),
            "Alecrim" to listOf(FARINHA_DE_OSSO),
            "Cacto" to listOf(PERLITA),
        )
        assertEquals(
            listOf(
                Compra(PERLITA, listOf("Manjericão", "Alecrim", "Cacto")),
                Compra(AREIA, listOf("Manjericão")),
                Compra(FARINHA_DE_OSSO, listOf("Alecrim")),
            ),
            listaDeCompras(pedidos),
        )
    }

    @Test
    fun `a compra diz o que serve no lugar para todas as plantas`() {
        val drenagem = listOf(Insumo.ARGILA_EXPANDIDA, PEDRISCO)
        assertEquals(
            listOf(Compra(Insumo.ARGILA_EXPANDIDA, listOf("Manjericão", "Alecrim"), ou = listOf(PEDRISCO))),
            listaDeCompras(listOf("Manjericão" to drenagem, "Alecrim" to drenagem)),
        )
        // Uma planta só aceita perlita: a alternativa da outra não entra.
        assertEquals(
            listOf(Compra(PERLITA, listOf("Manjericão", "Alecrim"))),
            listaDeCompras(listOf("Manjericão" to listOf(PERLITA, CASCA_DE_PINUS), "Alecrim" to listOf(PERLITA))),
        )
    }

    @Test
    fun `no empate vale a alternativa que o texto cita primeiro`() {
        assertEquals(
            listOf(Compra(PERLITA, listOf("Rosa-do-deserto"), ou = listOf(Insumo.CASCA_DE_ARROZ))),
            listaDeCompras(listOf("Rosa-do-deserto" to listOf(PERLITA, Insumo.CASCA_DE_ARROZ))),
        )
    }

    @Test
    fun `dados insumos json usa o mesmo vocabulário do enum`() {
        val arquivo = diretorioDoCatalogo().resolve("../../../../../dados/insumos.json").canonicalFile
        val dados = Json.parseToJsonElement(arquivo.readText()).jsonObject
        assertEquals(Insumo.entries.map { it.name }, dados.getValue("insumos").jsonArray.map { it.jsonPrimitive.content })
        val usados = (dados.getValue("especies") as JsonObject).values
            .flatMap { it.jsonObject.getValue("receitas") as JsonArray }
            .flatMap { it.jsonArray }.flatMap { it.jsonArray }.map { it.jsonPrimitive.content }.toSet()
        assertEquals(emptySet(), usados - Insumo.entries.map { it.name }.toSet())
    }
}
