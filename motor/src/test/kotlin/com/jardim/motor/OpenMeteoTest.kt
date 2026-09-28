package com.jardim.motor

import com.jardim.motor.agenda.DiaDoTempo
import com.jardim.motor.leitura.LeitorCultivo
import com.jardim.motor.leitura.Migracoes
import com.jardim.motor.leitura.ResultadoLeitura
import com.jardim.motor.tempo.Cidade
import com.jardim.motor.tempo.OpenMeteo
import com.jardim.motor.tempo.noCentroSul
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenMeteoTest {

    @Test
    fun `lê a previsão diária e pula o dia sem temperatura`() {
        val resposta = """
            {"latitude":-25.4,"longitude":-49.25,"timezone":"America/Sao_Paulo",
             "daily_units":{"time":"iso8601"},
             "daily":{"time":["2026-06-11","2026-06-12","2026-06-13"],
                      "temperature_2m_min":[8.1,1.6,null],
                      "temperature_2m_max":[19.4,14.0,null],
                      "precipitation_sum":[12.3,null,0.0],
                      "wind_gusts_10m_max":[31.0,64.8,null]}}
        """
        assertEquals(
            listOf(
                DiaDoTempo(LocalDate.of(2026, 6, 11), 8.1, 19.4, 12.3, 31.0),
                DiaDoTempo(LocalDate.of(2026, 6, 12), 1.6, 14.0, 0.0, 64.8),
            ),
            OpenMeteo.lerPrevisao(resposta),
        )
    }

    @Test
    fun `lê a busca de cidade, e busca sem resultado é lista vazia`() {
        val resposta = """
            {"results":[{"id":6322752,"name":"Curitiba","latitude":-25.42778,"longitude":-49.27306,
              "country_code":"BR","admin1":"Paraná","country":"Brasil","population":1948626}],
             "generationtime_ms":0.5}
        """
        assertEquals(listOf(Cidade("Curitiba", "Paraná", "BR", -25.42778, -49.27306)), OpenMeteo.lerBusca(resposta))
        assertEquals("Curitiba, Paraná", OpenMeteo.lerBusca(resposta).single().descricao)
        assertEquals(emptyList(), OpenMeteo.lerBusca("""{"generationtime_ms":0.3}"""))
    }

    @Test
    fun `a URL da busca escapa acento e espaço`() {
        assertTrue("name=S%C3%A3o+Paulo&" in OpenMeteo.urlBusca(" São Paulo "))
    }

    @Test
    fun `Centro-Sul pelo estado, com ou sem acento, nome ou sigla`() {
        fun em(estado: String?, latitude: Double = -20.0, pais: String? = "BR") = noCentroSul(Cidade("X", estado, pais, latitude, -45.0))
        assertTrue(em("Paraná"))
        assertTrue(em("Estado de São Paulo"))
        assertTrue(em("DF"))
        assertTrue(em("Mato Grosso", latitude = -9.0))
        assertFalse(em("Bahia", latitude = -16.0))
        assertFalse(em("Rio Grande do Norte"))
        assertFalse(em("Pará"))
        // Sem estado, vale a latitude; fora do Brasil, nunca.
        assertTrue(em(null, latitude = -23.5))
        assertFalse(em(null, latitude = -3.7))
        assertFalse(em("Lisboa", latitude = 38.7, pais = "PT"))
    }

    @Test
    fun `migração completa a cópia antiga com as temperaturas do catálogo`() {
        val atual = arquivoDoCatalogo("girassol.json").readText()
        val antiga = atual.replace(Regex(""",\s*"temperatura_minima_c": -?\d+,\s*"temperatura_maxima_c": -?\d+"""), "")
        assertFalse("temperatura_minima_c" in antiga)
        assertTrue(LeitorCultivo.ler(antiga) is ResultadoLeitura.Invalido)

        val migrada = LeitorCultivo.ler(Migracoes.comTemperaturas(antiga, atual)) as ResultadoLeitura.Ok
        assertEquals(girassol(), migrada.cultivo)

        val semCatalogo = LeitorCultivo.ler(Migracoes.comTemperaturas(antiga, null)) as ResultadoLeitura.Ok
        assertEquals(Migracoes.MINIMA_PADRAO to Migracoes.MAXIMA_PADRAO, semCatalogo.cultivo.planta.let { it.temperaturaMinimaC to it.temperaturaMaximaC })

        assertEquals(atual, Migracoes.comTemperaturas(atual, null))
    }
}
