package com.jardim.motor

import com.jardim.motor.leitura.LeitorCultivo
import com.jardim.motor.leitura.ResultadoLeitura
import com.jardim.motor.modelo.Luminosidade
import com.jardim.motor.modelo.TipoTarefa
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class LeitorCultivoTest {

    private val girassol = arquivoDoCatalogo("girassol.json").readText()

    private fun invalido(texto: String): List<String> =
        assertIs<ResultadoLeitura.Invalido>(LeitorCultivo.ler(texto)).problemas

    @Test
    fun `lê o JSON da skill, snake_case e enums inclusive`() {
        val cultivo = assertIs<ResultadoLeitura.Ok>(LeitorCultivo.ler(girassol)).cultivo
        assertEquals("Girassol", cultivo.planta.nomeComum)
        assertEquals(105, cultivo.planta.duracaoCicloDias)
        assertEquals(Luminosidade.SOL_DIRETO, cultivo.requisitosIniciais.luminosidade)
        assertEquals(TipoTarefa.TUTORAGEM, cultivo.tarefasAgendadas.single { it.id == "amarrar_tutor" }.tipo)
        assertEquals(listOf(listOf(12, 1, 2), listOf(5, 6)), cultivo.eventosSazonais.map { it.meses })
    }

    @Test
    fun `aceita a resposta da skill do jeito que ela vem, dentro de bloco json`() {
        assertIs<ResultadoLeitura.Ok>(LeitorCultivo.ler("```json\n$girassol\n```"))
    }

    @Test
    fun `campo fora do esquema é erro`() {
        invalido(girassol.replace("\"camada_drenagem\": true", "\"camada_drenagem\": true, \"cor\": \"amarelo\""))
    }

    @Test
    fun `campo anulável continua obrigatório`() {
        invalido(girassol.replace("\"duracao_ciclo_dias\": 105,", ""))
    }

    @Test
    fun `enum fora da lista é erro`() {
        invalido(girassol.replace("\"SOL_DIRETO\"", "\"SOL\""))
    }

    @Test
    fun `regra da linha do tempo quebrada volta com a mensagem do validador`() {
        val problemas = invalido(girassol.replace("\"duracao_ciclo_dias\": 105", "\"duracao_ciclo_dias\": 110"))
        assertTrue(problemas.any { "deve ser igual ao dia_fim da última fase" in it }, problemas.toString())
    }

    @Test
    fun `todo JSON do catálogo do app é válido`() {
        val arquivos = diretorioDoCatalogo().listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(arquivos.isNotEmpty(), "catálogo vazio em ${diretorioDoCatalogo()}")
        val quebrados = arquivos.mapNotNull { arquivo ->
            (LeitorCultivo.ler(arquivo.readText()) as? ResultadoLeitura.Invalido)?.let { "${arquivo.name}: ${it.problemas}" }
        }
        if (quebrados.isNotEmpty()) fail(quebrados.joinToString("\n"))
    }
}
