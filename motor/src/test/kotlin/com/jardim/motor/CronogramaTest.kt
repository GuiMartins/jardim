package com.jardim.motor

import com.jardim.motor.agenda.Cronograma
import com.jardim.motor.agenda.Origem
import com.jardim.motor.agenda.RegistroAcao
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.leitura.LeitorCultivo
import com.jardim.motor.leitura.ResultadoLeitura
import com.jardim.motor.modelo.PlantaCultivo
import com.jardim.motor.modelo.TipoTarefa
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CronogramaTest {

    private fun cronograma(
        cultivo: PlantaCultivo,
        vararg registros: RegistroAcao,
        plantio: LocalDate = PLANTIO,
    ) = Cronograma(cultivo, plantio, registros.toList())

    // --- contagem de dias ---

    @Test
    fun `dia do plantio é o dia 1`() {
        val c = cronograma(anual())
        assertEquals(1, c.diaDoCiclo(PLANTIO))
        assertEquals(15, c.diaDoCiclo(dia(15)))
        assertEquals(dia(15), c.dataDoDia(15))
    }

    @Test
    fun `nada vence antes do plantio`() {
        assertEquals(emptyList(), cronograma(anual()).pendenciasEm(PLANTIO.minusDays(1)))
    }

    // --- tarefa recorrente ---

    @Test
    fun `tarefa vence no dia de início`() {
        val c = cronograma(anual(tarefas = listOf(tarefa(inicio = 6))))
        assertEquals(emptyList(), c.pendenciasEm(dia(5)))
        val hoje = c.pendenciasEm(dia(6)).single()
        assertEquals("tarefa:rega", hoje.chave)
        assertEquals(0, hoje.diasDeAtraso)
    }

    @Test
    fun `tarefa atrasada não acumula - continua uma pendência só, com o atraso crescendo`() {
        val c = cronograma(anual(tarefas = listOf(tarefa(frequencia = 2, inicio = 6))))
        val hoje = c.pendenciasEm(dia(12)).single()
        assertEquals(dia(6), hoje.venceEm)
        assertEquals(6, hoje.diasDeAtraso)
    }

    @Test
    fun `feita no dia, some da lista`() {
        val c = cronograma(anual(tarefas = listOf(tarefa(inicio = 6))), feito("tarefa:rega", dia(6)))
        assertEquals(emptyList(), c.pendenciasEm(dia(6)))
    }

    @Test
    fun `próxima conta a partir do dia em que foi feita, não da grade fixa`() {
        // Vencia no 6 e foi feita no 9: a próxima é no 11, não no 8 nem no 10.
        val c = cronograma(anual(tarefas = listOf(tarefa(frequencia = 2, inicio = 6))), feito("tarefa:rega", dia(9)))
        assertEquals(emptyList(), c.pendenciasEm(dia(10)))
        assertEquals(dia(11), c.pendenciasEm(dia(11)).single().venceEm)
    }

    @Test
    fun `vale a ação mais recente quando há várias no histórico`() {
        val c = cronograma(
            anual(tarefas = listOf(tarefa(frequencia = 3, inicio = 1))),
            feito("tarefa:rega", dia(4)),
            feito("tarefa:rega", dia(1)),
        )
        assertEquals(emptyList(), c.pendenciasEm(dia(6)))
        assertEquals(dia(7), c.pendenciasEm(dia(7)).single().venceEm)
    }

    @Test
    fun `pular também reinicia a contagem`() {
        val c = cronograma(
            anual(tarefas = listOf(tarefa(frequencia = 2, inicio = 6))),
            feito("tarefa:rega", dia(6), StatusAcao.PULADA),
        )
        assertEquals(emptyList(), c.pendenciasEm(dia(7)))
        assertEquals(1, c.pendenciasEm(dia(8)).size)
    }

    @Test
    fun `janela fechada com a tarefa pendente faz ela sumir`() {
        val adubacao = tarefa("adubacao", TipoTarefa.ADUBACAO, frequencia = 10, inicio = 12, fim = 22, critica = false)
        val c = cronograma(anual(tarefas = listOf(adubacao)))
        assertEquals(10, c.pendenciasEm(dia(22)).single().diasDeAtraso)
        assertEquals(emptyList(), c.pendenciasEm(dia(23)))
    }

    @Test
    fun `próxima ocorrência que cairia depois da janela não aparece`() {
        val adubacao = tarefa("adubacao", TipoTarefa.ADUBACAO, frequencia = 10, inicio = 12, fim = 22, critica = false)
        val c = cronograma(anual(tarefas = listOf(adubacao)), feito("tarefa:adubacao", dia(15)))
        assertEquals(emptyList(), c.pendenciasEm(dia(22)))
    }

    // --- alertas ---

    @Test
    fun `alerta fica pendente até ser registrado, com o atraso contando`() {
        val cultivo = anual(tarefas = listOf(tarefa(inicio = 30)), alertas = listOf(alerta(8)))
        val c = cronograma(cultivo)
        assertEquals(emptyList(), c.pendenciasEm(dia(7)))
        val hoje = c.pendenciasEm(dia(10)).single()
        assertEquals(Origem.ALERTA, hoje.origem)
        assertEquals(2, hoje.diasDeAtraso)
        assertEquals("Mensagem do dia 8", hoje.contexto)

        assertEquals(emptyList(), cronograma(cultivo, feito("alerta:0", dia(12))).pendenciasEm(dia(12)))
    }

    // --- fim do ciclo ---

    @Test
    fun `ciclo anual encerrado não cobra mais nada`() {
        val c = cronograma(anual(duracao = 35))
        assertEquals(1, c.pendenciasEm(dia(35)).size)
        assertTrue(c.cicloEncerradoEm(dia(36)))
        assertEquals(emptyList(), c.pendenciasEm(dia(36)))
    }

    @Test
    fun `perene cobra para sempre`() {
        val c = cronograma(perene(tarefas = listOf(tarefa(frequencia = 3, fim = null))), feito("tarefa:rega", dia(997)))
        assertFalse(c.cicloEncerradoEm(dia(1000)))
        assertEquals(dia(1000), c.pendenciasEm(dia(1000)).single().venceEm)
    }

    // --- eventos sazonais ---

    @Test
    fun `evento sazonal aparece no dia 1 do mês e some quando o mês acaba`() {
        val c = cronograma(perene(tarefas = listOf(tarefa(inicio = 5000, fim = null)), eventos = listOf(evento(7))))
        assertEquals(0, c.pendenciasEm(LocalDate.of(2026, 7, 1)).single().diasDeAtraso)
        val dia10 = c.pendenciasEm(LocalDate.of(2026, 7, 10)).single()
        assertEquals(Origem.SAZONAL, dia10.origem)
        assertEquals(9, dia10.diasDeAtraso)
        assertEquals(emptyList(), c.pendenciasEm(LocalDate.of(2026, 8, 1)))
    }

    @Test
    fun `planta que chegou no meio do mês pula o evento daquele ano`() {
        val c = cronograma(
            perene(tarefas = listOf(tarefa(inicio = 5000, fim = null)), eventos = listOf(evento(7))),
            plantio = LocalDate.of(2026, 7, 15),
        )
        assertEquals(emptyList(), c.pendenciasEm(LocalDate.of(2026, 7, 20)))
        assertEquals(1, c.pendenciasEm(LocalDate.of(2027, 7, 1)).size)
    }

    @Test
    fun `evento registrado vale só para aquele ano`() {
        val c = cronograma(
            perene(tarefas = listOf(tarefa(inicio = 5000, fim = null)), eventos = listOf(evento(7))),
            feito("sazonal:0:2026-07", LocalDate.of(2026, 7, 3)),
        )
        assertEquals(emptyList(), c.pendenciasEm(LocalDate.of(2026, 7, 10)))
        assertEquals(1, c.pendenciasEm(LocalDate.of(2027, 7, 5)).size)
    }

    // --- fases e ordem ---

    @Test
    fun `fase do dia`() {
        val anual = anual(duracao = 35, fases = listOf(fase("germinacao", 1, 5), fase("folhas", 6, 27), fase("colheita", 28, 35)))
        assertEquals("folhas", cronograma(anual).faseEm(dia(20))?.faseId)
        assertNull(cronograma(anual).faseEm(PLANTIO.minusDays(1)))
        assertEquals("adulta", cronograma(perene()).faseEm(dia(5000))?.faseId)
    }

    @Test
    fun `críticas primeiro, depois as mais atrasadas`() {
        val c = cronograma(
            anual(
                tarefas = listOf(
                    tarefa("inspecao", TipoTarefa.PRAGA_CHECK, inicio = 1, critica = false),
                    tarefa("poda", TipoTarefa.PODA, inicio = 5, critica = false),
                    tarefa("rega", inicio = 9),
                ),
            ),
        )
        assertEquals(listOf("tarefa:rega", "tarefa:inspecao", "tarefa:poda"), c.pendenciasEm(dia(10)).map { it.chave })
    }

    // --- cenário real ---

    @Test
    fun `rabanete no dia 8 sem nada feito - regar, inspecionar e desbastar`() {
        val rabanete = (LeitorCultivo.ler(arquivoDoCatalogo("rabanete.json").readText()) as ResultadoLeitura.Ok).cultivo
        val hoje = cronograma(rabanete).pendenciasEm(dia(8))
        // A rega da germinação (dias 1 a 5) já saiu da janela; a rega padrão
        // venceu no dia 6 e é crítica, então vem primeiro.
        assertEquals(listOf("tarefa:rega_padrao", "tarefa:inspecao_pragas", "alerta:0"), hoje.map { it.chave })
        assertEquals(listOf(2, 2, 0), hoje.map { it.diasDeAtraso })
    }
}
