package com.jardim.motor

import com.jardim.motor.agenda.Cronograma
import com.jardim.motor.agenda.DiaDoTempo
import com.jardim.motor.agenda.Local
import com.jardim.motor.agenda.ModoAviso
import com.jardim.motor.agenda.Origem
import com.jardim.motor.agenda.RegistroAcao
import com.jardim.motor.agenda.Risco
import com.jardim.motor.agenda.Tempo
import com.jardim.motor.agenda.paraAviso
import com.jardim.motor.modelo.TipoTarefa
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A planta da Fábrica sofre abaixo de 5 °C e acima de 30 °C.
class TempoTest {

    private val hoje = dia(10)

    private fun tempoEm(data: LocalDate, minima: Double = 15.0, maxima: Double = 25.0, chuva: Double = 0.0, rajada: Double = 20.0) =
        DiaDoTempo(data, minima, maxima, chuva, rajada)

    /** Previsão amena de ontem até daqui a uma semana, com [mudar] aplicado a cada dia. */
    private fun previsao(mudar: (DiaDoTempo) -> DiaDoTempo = { it }) =
        (-1..7).map { mudar(tempoEm(hoje.plusDays(it.toLong()))) }

    private fun riscos(local: Local, dias: List<DiaDoTempo>, vararg registros: RegistroAcao) =
        Cronograma(anual(), PLANTIO, registros.toList(), Tempo(local, dias))
            .pendenciasEm(hoje).filter { it.origem == Origem.TEMPO }.map { it.risco }

    private val geadaAmanha = previsao { if (it.data == hoje.plusDays(1)) it.copy(minimaC = 2.0) else it }

    @Test
    fun `sem previsão ou com tempo ameno, a agenda é a de sempre`() {
        val semTempo = Cronograma(anual(), PLANTIO, emptyList()).pendenciasEm(hoje)
        assertEquals(semTempo, Cronograma(anual(), PLANTIO, emptyList(), Tempo(Local.AO_AR_LIVRE, previsao())).pendenciasEm(hoje))
    }

    @Test
    fun `noite de geada vira pendência crítica, que entra no lembrete da noite`() {
        val c = Cronograma(anual(), PLANTIO, emptyList(), Tempo(Local.AO_AR_LIVRE, geadaAmanha))
        val frio = c.pendenciasEm(hoje).single { it.risco == Risco.FRIO }
        assertTrue(frio.critica)
        assertEquals("tempo:frio:$hoje", frio.chave)
        assertTrue("2 °C" in frio.contexto!!, frio.contexto)
        assertTrue(frio in paraAviso(ModoAviso.NOITE, c.pendenciasEm(hoje)))
    }

    @Test
    fun `sob telhado a planta aguenta uns 2 °C a mais`() {
        fun amanha(minima: Double) = previsao { if (it.data == hoje.plusDays(1)) it.copy(minimaC = minima) else it }
        assertEquals(emptyList(), riscos(Local.COBERTO, amanha(4.0)))
        assertEquals(listOf(Risco.FRIO), riscos(Local.COBERTO, amanha(2.0)))
        assertEquals(listOf(Risco.FRIO), riscos(Local.AO_AR_LIVRE, amanha(4.0)))
    }

    @Test
    fun `madrugada acima do limite da planta não avisa`() {
        assertEquals(emptyList(), riscos(Local.AO_AR_LIVRE, previsao { it.copy(minimaC = 12.0) }))
    }

    @Test
    fun `calor acima do limite avisa, e o limite em si não`() {
        assertEquals(listOf(Risco.CALOR), riscos(Local.AO_AR_LIVRE, previsao { it.copy(maximaC = 33.0) }))
        assertEquals(emptyList(), riscos(Local.AO_AR_LIVRE, previsao { it.copy(maximaC = 30.4) }))
    }

    @Test
    fun `rajada forte avisa`() {
        assertEquals(listOf(Risco.VENTO), riscos(Local.COBERTO, previsao { it.copy(rajadaKmh = 75.0) }))
    }

    @Test
    fun `dentro de casa, nenhum aviso do tempo`() {
        val tudo = previsao { it.copy(minimaC = -1.0, maximaC = 38.0, chuvaMm = 40.0, rajadaKmh = 90.0) }
        assertEquals(emptyList(), riscos(Local.DENTRO_DE_CASA, tudo))
    }

    @Test
    fun `feito some até o dia seguinte`() {
        val calorTodoDia = previsao { it.copy(maximaC = 35.0) }
        assertEquals(emptyList(), riscos(Local.AO_AR_LIVRE, calorTodoDia, feito("tempo:calor:$hoje", hoje)))
        val amanha = Cronograma(anual(), PLANTIO, listOf(feito("tempo:calor:$hoje", hoje)), Tempo(Local.AO_AR_LIVRE, calorTodoDia))
            .pendenciasEm(hoje.plusDays(1))
        assertEquals(listOf(Risco.CALOR), amanha.mapNotNull { it.risco })
    }

    @Test
    fun `chuva de ontem só acrescenta contexto à rega de vaso ao ar livre`() {
        val choveu = previsao { if (it.data == hoje.minusDays(1)) it.copy(chuvaMm = 18.0) else it }
        val cultivo = anual(tarefas = listOf(tarefa(frequencia = 1), tarefa("adubo", TipoTarefa.ADUBACAO, frequencia = 1)))
        fun pendencias(local: Local) = Cronograma(cultivo, PLANTIO, emptyList(), Tempo(local, choveu)).pendenciasEm(hoje)

        val livre = pendencias(Local.AO_AR_LIVRE)
        assertTrue("18 mm" in livre.single { it.tipo == TipoTarefa.REGA }.contexto!!)
        assertNull(livre.single { it.tipo == TipoTarefa.ADUBACAO }.contexto)
        assertNull(pendencias(Local.COBERTO).single { it.tipo == TipoTarefa.REGA }.contexto)
    }

    @Test
    fun `a previsão dos próximos dias mostra a geada no dia certo`() {
        val geadaDepois = previsao { if (it.data == hoje.plusDays(4)) it.copy(minimaC = 1.0) else it }
        val proximos = Cronograma(anual(), PLANTIO, emptyList(), Tempo(Local.AO_AR_LIVRE, geadaDepois)).proximosDias(hoje)
        val comFrio = proximos.filter { d -> d.pendencias.any { it.risco == Risco.FRIO } }.map { it.data }
        assertTrue(hoje.plusDays(3) in comFrio, "$comFrio")
        assertTrue(comFrio.none { it < hoje.plusDays(3) }, "$comFrio")
    }

    @Test
    fun `histórico traduz a chave do tempo`() {
        val c = Cronograma(anual(), PLANTIO, emptyList())
        assertEquals(Risco.FRIO, c.identificar("tempo:frio:2026-06-12")?.risco)
        assertEquals("Proteger do frio esta noite", c.identificar("tempo:frio:2026-06-12")?.titulo)
    }
}
