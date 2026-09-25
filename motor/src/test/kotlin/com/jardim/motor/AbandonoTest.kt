package com.jardim.motor

import com.jardim.motor.agenda.Cronograma
import com.jardim.motor.agenda.RegistroAcao
import com.jardim.motor.agenda.devePerguntarSeEstaViva
import com.jardim.motor.modelo.PlantaCultivo
import com.jardim.motor.modelo.TipoTarefa
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbandonoTest {

    private fun perguntaNoDia(cultivo: PlantaCultivo, d: Int, vararg registros: RegistroAcao) =
        devePerguntarSeEstaViva(Cronograma(cultivo, PLANTIO, registros.toList()).pendenciasEm(dia(d)))

    @Test
    fun `rega com uma semana de atraso pergunta`() {
        val cultivo = anual(tarefas = listOf(tarefa(inicio = 1)))
        assertFalse(perguntaNoDia(cultivo, 7)) // 6 dias de atraso
        assertTrue(perguntaNoDia(cultivo, 8)) // 7 dias de atraso
    }

    @Test
    fun `rega não crítica também conta`() {
        // Germinação regada em dia; a rega da plântula (não crítica) venceu no
        // dia 9 e nunca foi feita.
        val germinou = feito("tarefa:rega_germinacao", dia(8))
        assertFalse(perguntaNoDia(girassol(), 15, germinou))
        assertTrue(perguntaNoDia(girassol(), 16, germinou))
    }

    @Test
    fun `atraso em inspeção, alerta ou evento sazonal não pergunta`() {
        val inspecaoEAlerta = anual(
            tarefas = listOf(tarefa("rega", inicio = 50), tarefa("inspecao", TipoTarefa.PRAGA_CHECK, inicio = 1, critica = false)),
            alertas = listOf(alerta(1)),
        )
        assertFalse(perguntaNoDia(inspecaoEAlerta, 40))

        val eventoDeMarco = perene(tarefas = listOf(tarefa(inicio = 5000, fim = null)), eventos = listOf(evento(3)))
        assertFalse(perguntaNoDia(eventoDeMarco, 31))
    }

    @Test
    fun `troca de janela da rega zera o atraso e adia a pergunta`() {
        // Comportamento atual, não ideal: a última rega foi no dia 25, a rega da
        // plântula acaba no 30 e a do crescimento começa no 31 sem histórico,
        // com atraso zero. A pergunta vem no dia 38, 13 dias sem regar, e não no
        // 34, quando a rega da plântula (vencida no 27) completaria 7 dias.
        val registros = arrayOf(feito("tarefa:rega_germinacao", dia(8)), feito("tarefa:rega_plantula", dia(25)))
        assertFalse(perguntaNoDia(girassol(), 34, *registros))
        assertFalse(perguntaNoDia(girassol(), 37, *registros))
        assertTrue(perguntaNoDia(girassol(), 38, *registros))
    }
}
