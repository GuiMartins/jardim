package com.jardim.motor

import com.jardim.motor.agenda.Cronograma
import com.jardim.motor.agenda.Identificacao
import com.jardim.motor.agenda.ModoAviso
import com.jardim.motor.agenda.Origem
import com.jardim.motor.agenda.RegistroAcao
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.agenda.foraDeEpoca
import com.jardim.motor.agenda.paraAviso
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
    fun `conferi, não precisou - também reinicia a contagem`() {
        val c = cronograma(
            anual(tarefas = listOf(tarefa(frequencia = 2, inicio = 6))),
            feito("tarefa:rega", dia(6), StatusAcao.NAO_PRECISOU),
        )
        assertEquals(emptyList(), c.pendenciasEm(dia(7)))
        assertEquals(1, c.pendenciasEm(dia(8)).size)
    }

    // --- ressemeadura ---

    @Test
    fun `ressemear - histórico de antes do novo dia 1 não conta`() {
        // Plantou em PLANTIO, marcou o alerta do dia 10 e a rega, não germinou e
        // ressemeou no dia 11: esse passa a ser o novo dia 1.
        val cultivo = anual(tarefas = listOf(tarefa(frequencia = 3, inicio = 1)), alertas = listOf(alerta(10)))
        val novoPlantio = dia(11)
        val c = Cronograma(
            cultivo,
            novoPlantio,
            listOf(feito("alerta:0", dia(10)), feito("tarefa:rega", dia(10))),
        )
        assertEquals(listOf("tarefa:rega"), c.pendenciasEm(novoPlantio).map { it.chave })
        assertEquals("alerta:0", c.pendenciasEm(novoPlantio.plusDays(9)).first { it.origem == Origem.ALERTA }.chave)
    }

    // --- época de plantio ---

    @Test
    fun `fora de época só pelo mês de meses_plantio`() {
        val girassol = girassol() // setembro a fevereiro
        assertFalse(foraDeEpoca(girassol, LocalDate.of(2026, 9, 24)))
        assertFalse(foraDeEpoca(girassol, LocalDate.of(2027, 2, 28)))
        assertTrue(foraDeEpoca(girassol, LocalDate.of(2027, 5, 10)))
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

    // --- cenário real: o girassol, JSON de referência do projeto ---

    private val plantioGirassol = LocalDate.of(2026, 10, 1)

    @Test
    fun `girassol esquecido desde o plantio, no dia 14`() {
        val hoje = Cronograma(girassol(), plantioGirassol, emptyList()).pendenciasEm(plantioGirassol.plusDays(13))
        // A rega da germinação (dias 1 a 8) já saiu da janela. A rega da
        // plântula não é crítica, então a ordem é só pelo atraso.
        assertEquals(
            listOf("tarefa:rega_plantula", "tarefa:inspecao_pragas", "alerta:0", "alerta:1"),
            hoje.map { it.chave },
        )
        assertEquals(listOf(5, 5, 4, 0), hoje.map { it.diasDeAtraso })
    }

    @Test
    fun `próximos dias do girassol esquecido, visto no dia 14`() {
        // Supõe que o atrasado é feito hoje (dia 14): a rega da plântula volta
        // no 16, 18 e 20, a inspeção no 17 e 20, e a adubação começa no 20.
        val c = Cronograma(girassol(), plantioGirassol, emptyList())
        val previsao = c.proximosDias(plantioGirassol.plusDays(13))
        assertEquals(
            mapOf(
                16 to setOf("tarefa:rega_plantula"),
                17 to setOf("tarefa:inspecao_pragas"),
                18 to setOf("tarefa:rega_plantula"),
                20 to setOf("tarefa:rega_plantula", "tarefa:inspecao_pragas", "tarefa:adubacao_cobertura"),
            ),
            previsao.associate { it.dia to it.pendencias.map { p -> p.chave }.toSet() },
        )
        assertTrue(previsao.flatMap { it.pendencias }.all { it.diasDeAtraso == 0 })
    }

    @Test
    fun `próximos dias param no fim do ciclo`() {
        val c = Cronograma(girassol(), plantioGirassol, emptyList())
        assertTrue(c.proximosDias(plantioGirassol.plusDays(104)).isEmpty()) // visto do dia 105
    }

    @Test
    fun `identifica cada tipo de chave do histórico`() {
        val c = Cronograma(girassol(), plantioGirassol, emptyList())
        assertEquals(Identificacao(Origem.TAREFA, "Regar o girassol jovem", TipoTarefa.REGA), c.identificar("tarefa:rega_plantula"))
        assertEquals("Hora do desbaste!", c.identificar("alerta:1")?.titulo)
        assertEquals("Calor e temporais de verão", c.identificar("sazonal:0:2026-12")?.titulo)
        assertNull(c.identificar("tarefa:nao_existe"))
        assertNull(c.identificar("alerta:99"))
    }

    @Test
    fun `girassol no dia 31 - de manhã vai tudo, à noite só a rega crítica`() {
        val pendencias = Cronograma(girassol(), plantioGirassol, emptyList()).pendenciasEm(plantioGirassol.plusDays(30))
        assertTrue(pendencias.size > 1)
        assertEquals(pendencias, paraAviso(ModoAviso.RESUMO, pendencias))
        // A rega da plântula (não crítica) acabou no 30; a do crescimento, crítica, começa hoje.
        assertEquals(listOf("tarefa:rega_crescimento_floracao"), paraAviso(ModoAviso.NOITE, pendencias).map { it.chave })
    }

    @Test
    fun `girassol cuidado em dia, do plantio à colheita`() {
        val girassol = girassol()
        val registros = mutableListOf<RegistroAcao>()
        val itensPorDia = (1..105).map { d ->
            val hoje = plantioGirassol.plusDays(d - 1L)
            val pendencias = Cronograma(girassol, plantioGirassol, registros).pendenciasEm(hoje)
            assertTrue(pendencias.all { it.diasDeAtraso == 0 }, "dia $d: $pendencias")
            pendencias.mapTo(registros) { feito(it.chave, hoje) }
            pendencias.size
        }

        val porChave = registros.groupingBy { it.chave }.eachCount()
        assertEquals(8, porChave["tarefa:rega_germinacao"])
        assertEquals(11, porChave["tarefa:rega_plantula"]) // dias 9, 11, ..., 29
        assertEquals(60, porChave["tarefa:rega_crescimento_floracao"])
        assertEquals(5, porChave["tarefa:rega_maturacao"]) // dias 91, 94, ..., 103
        assertEquals(3, porChave["tarefa:adubacao_cobertura"]) // dias 20, 35, 50
        assertEquals(3, porChave["tarefa:amarrar_tutor"]) // dias 32, 42, 52
        assertEquals(29, porChave["tarefa:inspecao_pragas"]) // dias 9, 12, ..., 93
        assertEquals(7, porChave.keys.count { it.startsWith("alerta:") })
        // Plantado em 1º de outubro, está vivo em 1º de dezembro (dia 62) e em
        // 1º de janeiro (dia 93); o ciclo termina em 13 de janeiro.
        assertEquals(setOf("sazonal:0:2026-12", "sazonal:0:2027-01"), porChave.keys.filter { it.startsWith("sazonal:") }.toSet())
        assertEquals(128, registros.size)

        // O resumo diário do girassol nunca passa de 3 itens.
        assertEquals(3, itensPorDia.max())
        assertTrue(Cronograma(girassol, plantioGirassol, registros).cicloEncerradoEm(plantioGirassol.plusDays(105)))
    }
}
