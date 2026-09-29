package com.jardim.motor

import com.jardim.motor.agenda.ItemPreparo
import com.jardim.motor.agenda.avisarEpoca
import com.jardim.motor.agenda.inicioDaEpocaDesde
import com.jardim.motor.agenda.itensDePreparo
import com.jardim.motor.agenda.mesesDeInicio
import com.jardim.motor.agenda.proximoInicioDaEpoca
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// O girassol se planta de setembro a fevereiro: a época começa em 1º de setembro.
class PlanejamentoTest {

    private val girassol = girassol().planta.mesesPlantio
    private fun data(ano: Int, mes: Int, dia: Int) = LocalDate.of(ano, mes, dia)

    @Test
    fun `época começa no mês cujo anterior está fora, inclusive virando o ano`() {
        assertEquals(setOf(9), mesesDeInicio(girassol))
        assertEquals(setOf(3, 9), mesesDeInicio(listOf(3, 4, 9, 10)))
        assertEquals(setOf(1), mesesDeInicio(listOf(1, 2)))
        assertEquals(emptySet(), mesesDeInicio((1..12).toList()))
    }

    @Test
    fun `próximo começo da época`() {
        assertEquals(data(2026, 9, 1), proximoInicioDaEpoca(girassol, data(2026, 5, 10)))
        // No próprio dia 1, o próximo é o do ano seguinte.
        assertEquals(data(2027, 9, 1), proximoInicioDaEpoca(girassol, data(2026, 9, 1)))
        assertEquals(data(2027, 9, 1), proximoInicioDaEpoca(girassol, data(2026, 12, 20)))
        assertNull(proximoInicioDaEpoca((1..12).toList(), data(2026, 5, 10)))
    }

    @Test
    fun `planejada fora de época avisa quando a época chega`() {
        val maio = data(2026, 5, 10)
        assertNull(inicioDaEpocaDesde(girassol, maio, data(2026, 8, 31)))
        assertEquals(data(2026, 9, 1), inicioDaEpocaDesde(girassol, maio, data(2026, 9, 1)))
        assertEquals(data(2026, 9, 1), inicioDaEpocaDesde(girassol, maio, data(2026, 12, 15)))
    }

    @Test
    fun `planejada dentro da época só avisa na época seguinte`() {
        val outubro = data(2026, 10, 10)
        assertFalse(avisarEpoca(girassol, outubro, null, data(2026, 12, 1)))
        assertFalse(avisarEpoca(girassol, data(2026, 9, 1), null, data(2026, 9, 1)))
        assertTrue(avisarEpoca(girassol, outubro, null, data(2027, 9, 1)))
    }

    @Test
    fun `avisa uma vez por época, mesmo se o dia 1 passou sem aviso`() {
        val maio = data(2026, 5, 10)
        // O celular ficou desligado no dia 1: sai no primeiro resumo depois.
        assertTrue(avisarEpoca(girassol, maio, null, data(2026, 9, 4)))
        assertFalse(avisarEpoca(girassol, maio, data(2026, 9, 4), data(2026, 9, 5)))
        // Não plantou: a época seguinte avisa de novo.
        assertTrue(avisarEpoca(girassol, maio, data(2026, 9, 4), data(2027, 9, 1)))
    }

    @Test
    fun `quem planta o ano todo nunca recebe aviso de época`() {
        assertFalse(avisarEpoca((1..12).toList(), data(2026, 5, 10), null, data(2027, 5, 10)))
    }

    @Test
    fun `drenagem só entra no preparo quando a espécie pede`() {
        val comDrenagem = anual()
        assertEquals(ItemPreparo.entries, itensDePreparo(comDrenagem))
        val semDrenagem = comDrenagem.copy(requisitosIniciais = comDrenagem.requisitosIniciais.copy(camadaDrenagem = false))
        assertFalse(ItemPreparo.DRENAGEM in itensDePreparo(semDrenagem))
    }
}
