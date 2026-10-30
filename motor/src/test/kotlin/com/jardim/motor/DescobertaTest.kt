package com.jardim.motor

import com.jardim.motor.catalogo.FiltroDescoberta
import com.jardim.motor.catalogo.ItemCatalogo
import com.jardim.motor.catalogo.Sol
import com.jardim.motor.catalogo.Vaso
import com.jardim.motor.catalogo.descobrir
import com.jardim.motor.modelo.Dificuldade
import com.jardim.motor.modelo.Luminosidade
import com.jardim.motor.modelo.TipoCiclo
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DescobertaTest {

    private fun item(
        nome: String,
        luz: Luminosidade,
        minimo: Double,
        recomendadas: Double,
        litros: Double,
        categoria: String,
        posicao: Int,
        dificuldade: Dificuldade = Dificuldade.FACIL,
        meses: List<Int> = (1..12).toList(),
    ) = ItemCatalogo(
        arquivo = "$nome.json",
        nomeComum = nome,
        nomeCientifico = nome,
        categoria = categoria,
        posicao = posicao,
        dificuldade = dificuldade,
        tipoCiclo = TipoCiclo.PERENE,
        duracaoCicloDias = null,
        mesesPlantio = meses,
        luminosidade = luz,
        horasSolMinimas = minimo,
        horasSolDiariasRecomendadas = recomendadas,
        volumeMinimoLitros = litros,
    )

    // Valores tirados do catálogo real.
    private val girassol = item("Girassol", Luminosidade.SOL_DIRETO, 6.0, 8.0, 12.0, "FLOR", 3, meses = listOf(9, 10, 11, 12, 1, 2))
    private val alface = item("Alface", Luminosidade.SOL_DIRETO, 4.0, 5.0, 3.0, "HORTALICA", 5)
    private val samambaia = item("Samambaia", Luminosidade.MEIA_SOMBRA, 0.0, 2.0, 5.0, "SAMAMBAIA", 2)
    private val cafeeiro = item("Cafeeiro", Luminosidade.MEIA_SOMBRA, 3.0, 5.0, 40.0, "ARBUSTO", 8, Dificuldade.MEDIA)
    private val zamioculca = item("Zamioculca", Luminosidade.SOMBRA, 0.0, 0.0, 3.0, "FOLHAGEM", 1)
    private val todas = listOf(girassol, alface, samambaia, cafeeiro, zamioculca)

    private val maio = LocalDate.of(2026, 5, 10)

    private fun nomes(filtro: FiltroDescoberta, calendarioVale: Boolean = true) =
        descobrir(todas, filtro, maio, calendarioVale).plantas.map { it.nomeComum }

    @Test
    fun `sem filtro vem tudo, da mais popular à menos`() {
        assertEquals(listOf("Zamioculca", "Samambaia", "Girassol", "Alface", "Cafeeiro"), nomes(FiltroDescoberta()))
    }

    @Test
    fun `sem sol direto só entra quem pede zero hora`() {
        assertEquals(listOf("Zamioculca", "Samambaia"), nomes(FiltroDescoberta(sol = Sol.NENHUM)))
    }

    @Test
    fun `pouco sol aceita quem pede até 3 h`() {
        assertEquals(listOf("Zamioculca", "Samambaia", "Cafeeiro"), nomes(FiltroDescoberta(sol = Sol.POUCO)))
    }

    @Test
    fun `planta de meia-sombra fica fora do sol que passa do que ela aguenta`() {
        // Samambaia pede 2 h: com 4 a 5 h de sol, queima. Cafeeiro pede 5 h e aguenta até 6.
        assertEquals(listOf("Alface", "Cafeeiro"), nomes(FiltroDescoberta(sol = Sol.MEIO)))
        assertEquals(listOf("Girassol", "Alface", "Cafeeiro"), nomes(FiltroDescoberta(sol = Sol.MUITO)))
    }

    @Test
    fun `o vaso tem de comportar o volume mínimo`() {
        assertEquals(listOf("Zamioculca", "Alface"), nomes(FiltroDescoberta(vaso = Vaso.PEQUENO)))
        assertEquals(listOf("Zamioculca", "Samambaia", "Alface"), nomes(FiltroDescoberta(vaso = Vaso.MEDIO)))
        assertEquals(listOf("Zamioculca", "Samambaia", "Girassol", "Alface"), nomes(FiltroDescoberta(vaso = Vaso.GRANDE)))
        assertEquals(5, nomes(FiltroDescoberta(vaso = Vaso.CANTEIRO)).size)
    }

    @Test
    fun `só fáceis tira as médias e difíceis`() {
        assertEquals(listOf("Girassol", "Alface"), nomes(FiltroDescoberta(sol = Sol.MUITO, soFaceis = true)))
    }

    @Test
    fun `só na época usa os meses de plantio, menos fora do Centro-Sul`() {
        // Girassol se planta de setembro a fevereiro; em maio fica de fora.
        assertEquals(listOf("Alface", "Cafeeiro"), nomes(FiltroDescoberta(sol = Sol.MUITO, soNaEpoca = true)))
        assertEquals(listOf("Girassol", "Alface", "Cafeeiro"), nomes(FiltroDescoberta(sol = Sol.MUITO, soNaEpoca = true), calendarioVale = false))
    }

    @Test
    fun `as categorias contam o que cabe, sem o próprio filtro de categoria`() {
        val resultado = descobrir(todas, FiltroDescoberta(sol = Sol.MUITO, categorias = setOf("HORTALICA")), maio, true)
        assertEquals(listOf("Alface"), resultado.plantas.map { it.nomeComum })
        assertEquals(listOf("ARBUSTO" to 1, "FLOR" to 1, "HORTALICA" to 1), resultado.categorias)
    }
}
