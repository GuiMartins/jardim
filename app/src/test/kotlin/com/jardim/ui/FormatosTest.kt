package com.jardim.ui

import com.jardim.motor.catalogo.ItemCatalogo
import com.jardim.motor.modelo.Dificuldade
import com.jardim.motor.modelo.Luminosidade
import com.jardim.motor.modelo.TipoCiclo
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FormatosTest {

    @Test
    fun `datas em português`() {
        assertEquals("Quinta-feira, 24 de setembro", dataLonga(LocalDate.of(2026, 9, 24)))
        assertEquals("1 de outubro de 2026", dataCompleta(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `datas da linha do tempo`() {
        val hoje = LocalDate.of(2026, 9, 25)
        assertEquals("Amanhã", diaDaPrevisao(LocalDate.of(2026, 9, 26), hoje))
        assertEquals("Domingo, 27/09", diaDaPrevisao(LocalDate.of(2026, 9, 27), hoje))
        assertEquals("24/09 às 22:34", dataHora(LocalDateTime.of(2026, 9, 24, 22, 34)))
    }

    @Test
    fun `meses de plantio do girassol, sem o ponto da abreviação`() {
        assertEquals("set, out, nov, dez, jan, fev", meses(listOf(9, 10, 11, 12, 1, 2)))
    }

    @Test
    fun `atraso no singular e no plural`() {
        assertEquals("Para hoje", atraso(0))
        assertEquals("Atrasada há 1 dia", atraso(1))
        assertEquals("Atrasada há 5 dias", atraso(5))
    }

    @Test
    fun `números sem casa decimal à toa, com vírgula quando precisa`() {
        assertEquals("12", numero(12.0))
        assertEquals("4,5", numero(4.5))
    }

    @Test
    fun `data do DatePicker vai e volta sem perder um dia`() {
        val data = LocalDate.of(2026, 10, 1)
        assertEquals(data, data.emMillisUtc().comoDataUtc())
    }

    @Test
    fun `toda categoria do levantamento tem emoji próprio no lugar da foto`() {
        val categorias = listOf(
            "HORTALICA", "ERVA_AROMATICA", "MEDICINAL", "FRUTIFERA", "FLOR", "FOLHAGEM", "SUCULENTA", "ORQUIDEA",
            "BROMELIA", "SAMAMBAIA", "PALMEIRA", "ARBUSTO", "ARVORE", "TREPADEIRA", "FORRACAO", "AQUATICA",
        )
        categorias.forEach { assertNotEquals(emojiDaCategoria(null), emojiDaCategoria(it), it) }
    }

    @Test
    fun `o que a planta pede do lugar, na descoberta`() {
        fun item(luz: Luminosidade, minimo: Double, recomendadas: Double, litros: Double) = ItemCatalogo(
            arquivo = "x.json", nomeComum = "X", nomeCientifico = "X", dificuldade = Dificuldade.FACIL,
            tipoCiclo = TipoCiclo.PERENE, duracaoCicloDias = null, mesesPlantio = emptyList(),
            luminosidade = luz, horasSolMinimas = minimo, horasSolDiariasRecomendadas = recomendadas, volumeMinimoLitros = litros,
        )
        assertEquals("6 h de sol ou mais · vaso de 12 L", pedeDoLugar(item(Luminosidade.SOL_DIRETO, 6.0, 8.0, 12.0)))
        assertEquals("Até 2 h de sol · vaso de 5 L", pedeDoLugar(item(Luminosidade.MEIA_SOMBRA, 0.0, 2.0, 5.0)))
        assertEquals("3 a 5 h de sol · vaso de 40 L", pedeDoLugar(item(Luminosidade.MEIA_SOMBRA, 3.0, 5.0, 40.0)))
        assertEquals("Sem sol direto · vaso de 0,5 L", pedeDoLugar(item(Luminosidade.SOMBRA, 0.0, 0.0, 0.5)))
    }
}
