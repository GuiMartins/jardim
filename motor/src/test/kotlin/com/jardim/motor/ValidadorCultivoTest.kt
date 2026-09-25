package com.jardim.motor

import com.jardim.motor.leitura.ValidadorCultivo
import com.jardim.motor.modelo.PlantaCultivo
import com.jardim.motor.modelo.TipoTarefa
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidadorCultivoTest {

    private fun assertReprova(cultivo: PlantaCultivo, trecho: String) {
        val problemas = ValidadorCultivo.validar(cultivo)
        assertTrue(problemas.any { trecho in it }, "esperava \"$trecho\" em $problemas")
    }

    @Test
    fun `as fábricas dos testes geram cultivos válidos`() {
        assertEquals(emptyList(), ValidadorCultivo.validar(anual()))
        assertEquals(emptyList(), ValidadorCultivo.validar(perene()))
    }

    @Test
    fun `lacuna entre fases`() =
        assertReprova(anual(duracao = 20, fases = listOf(fase("a", 1, 5), fase("b", 7, 20))), "há lacuna")

    @Test
    fun `sobreposição entre fases`() =
        assertReprova(anual(duracao = 20, fases = listOf(fase("a", 1, 5), fase("b", 5, 20))), "há sobreposição")

    @Test
    fun `primeira fase fora do dia 1`() =
        assertReprova(anual(duracao = 20, fases = listOf(fase("a", 2, 20))), "começa no dia 1")

    @Test
    fun `anual não pode terminar em fase aberta`() =
        assertReprova(anual(fases = listOf(fase("a", 1, null))), "só é permitido para planta PERENE")

    @Test
    fun `duração diferente do fim da última fase`() =
        assertReprova(anual(duracao = 40, fases = listOf(fase("a", 1, 35))), "deve ser igual ao dia_fim")

    @Test
    fun `perene com duração`() =
        assertReprova(perene().let { it.copy(planta = it.planta.copy(duracaoCicloDias = 365)) }, "deve ser null para planta PERENE")

    @Test
    fun `sem rega crítica`() =
        assertReprova(anual(tarefas = listOf(tarefa(critica = false))), "REGA com critica true")

    @Test
    fun `frequência zero vira alerta, não tarefa`() =
        assertReprova(anual(tarefas = listOf(tarefa(frequencia = 0))), "ação única vira alerta")

    @Test
    fun `tarefa passando do fim do ciclo`() =
        assertReprova(anual(duracao = 30, tarefas = listOf(tarefa(fim = 31))), "passa do fim do ciclo")

    @Test
    fun `tarefa sem fim em planta anual`() =
        assertReprova(anual(tarefas = listOf(tarefa(fim = null))), "null só é permitido para planta PERENE")

    @Test
    fun `alerta depois do fim do ciclo`() =
        assertReprova(anual(duracao = 30, alertas = listOf(alerta(31))), "passa do fim do ciclo")

    @Test
    fun `id repetido e fora de snake_case`() {
        val cultivo = anual(tarefas = listOf(tarefa("rega"), tarefa("rega", TipoTarefa.PODA), tarefa("Rega-Extra")))
        assertReprova(cultivo, "'rega' repetido")
        assertReprova(cultivo, "'Rega-Extra' deveria estar em snake_case")
    }

    @Test
    fun `mês fora de 1 a 12`() =
        assertReprova(perene(eventos = listOf(evento(12, 13))), "eventos_sazonais[0].meses")

    @Test
    fun `sol mínimo acima do recomendado`() {
        val base = anual()
        assertReprova(base.copy(requisitosIniciais = base.requisitosIniciais.copy(horasSolMinimas = 8.0)), "não pode passar das recomendadas")
    }
}
