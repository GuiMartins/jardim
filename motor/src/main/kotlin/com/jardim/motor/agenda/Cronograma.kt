package com.jardim.motor.agenda

import com.jardim.motor.modelo.Fase
import com.jardim.motor.modelo.PlantaCultivo
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Agenda de um plantio, calculada a partir do JSON da espécie, da data do dia 1
 * e do histórico. Não guarda estado nem grava ocorrência futura no banco:
 * pendência é "o que o JSON manda fazer até hoje e o histórico não mostra feito".
 * Isso também resolve as perenes, cujas tarefas se repetem para sempre.
 *
 * Regras de atraso (decididas na etapa 1, ver CLAUDE.md):
 * - Tarefa recorrente não acumula. Vence em `dia_inicio_ciclo`, fica pendente com
 *   o atraso crescendo até ser feita, e a próxima vence `frequencia_dias` depois
 *   do dia em que foi feita de verdade. Pular conta como feita.
 * - Se a janela da tarefa (`dia_fim_ciclo`) fecha com ela pendente, ela some.
 * - Alerta (`alertas_fase`) é único: fica pendente até ser registrado.
 * - Evento sazonal vale pelo mês: aparece no dia 1, se a planta já existia
 *   nesse dia, e some quando o mês acaba.
 */
class Cronograma(
    private val cultivo: PlantaCultivo,
    private val dataPlantio: LocalDate,
    registros: List<RegistroAcao>,
) {
    private val ultimaAcaoPorChave: Map<String, LocalDate> = registros
        .groupBy { it.chave }
        .mapValues { (_, doItem) -> doItem.maxOf { it.realizadoEm.toLocalDate() } }

    /** Dia 1 = data do plantio. Antes dela o número é zero ou negativo. */
    fun diaDoCiclo(data: LocalDate): Int = ChronoUnit.DAYS.between(dataPlantio, data).toInt() + 1

    fun dataDoDia(dia: Int): LocalDate = dataPlantio.plusDays(dia - 1L)

    /** Anuais e bienais terminam em `duracao_ciclo_dias`; perenes nunca. */
    fun cicloEncerradoEm(data: LocalDate): Boolean {
        val duracao = cultivo.planta.duracaoCicloDias ?: return false
        return diaDoCiclo(data) > duracao
    }

    fun faseEm(data: LocalDate): Fase? {
        val dia = diaDoCiclo(data)
        return cultivo.fasesCicloVida.firstOrNull { dia >= it.diaInicio && dia <= (it.diaFim ?: Int.MAX_VALUE) }
    }

    /** Tudo que vence hoje ou já venceu, críticas primeiro e depois as mais atrasadas. */
    fun pendenciasEm(hoje: LocalDate): List<Pendencia> {
        val dia = diaDoCiclo(hoje)
        if (dia < 1 || cicloEncerradoEm(hoje)) return emptyList()
        return (pendenciasDeTarefas(dia) + pendenciasDeAlertas(dia) + pendenciasSazonais(hoje))
            .sortedWith(compareByDescending<Pendencia> { it.critica }.thenByDescending { it.diasDeAtraso })
    }

    private fun pendenciasDeTarefas(dia: Int): List<Pendencia> = cultivo.tarefasAgendadas.mapNotNull { t ->
        val chave = "tarefa:${t.id}"
        val diaUltimaAcao = ultimaAcaoPorChave[chave]?.let { diaDoCiclo(it) }
        val vence = if (diaUltimaAcao == null) {
            t.diaInicioCiclo
        } else {
            maxOf(diaUltimaAcao + t.frequenciaDias, t.diaInicioCiclo)
        }
        val fimJanela = t.diaFimCiclo ?: Int.MAX_VALUE
        if (vence > dia || dia > fimJanela) return@mapNotNull null
        Pendencia(
            chave = chave,
            origem = Origem.TAREFA,
            titulo = t.titulo,
            instrucoes = t.instrucoes,
            contexto = null,
            tipo = t.tipo,
            critica = t.critica,
            venceEm = dataDoDia(vence),
            diasDeAtraso = dia - vence,
        )
    }

    // Alerta não tem id no JSON; a posição na lista serve porque cada plantio
    // guarda a própria cópia do JSON, que não muda depois do plantio.
    private fun pendenciasDeAlertas(dia: Int): List<Pendencia> = cultivo.alertasFase.mapIndexedNotNull { i, a ->
        val chave = "alerta:$i"
        if (a.diaDisparo > dia || chave in ultimaAcaoPorChave) return@mapIndexedNotNull null
        Pendencia(
            chave = chave,
            origem = Origem.ALERTA,
            titulo = a.titulo,
            instrucoes = a.acaoRequerida,
            contexto = a.mensagem,
            tipo = null,
            critica = false,
            venceEm = dataDoDia(a.diaDisparo),
            diasDeAtraso = dia - a.diaDisparo,
        )
    }

    private fun pendenciasSazonais(hoje: LocalDate): List<Pendencia> {
        val inicioDoMes = hoje.withDayOfMonth(1)
        // Planta que chegou no meio do mês pula o evento deste ano: ele dispara no
        // dia 1 e nesse dia ela ainda não existia.
        if (dataPlantio > inicioDoMes) return emptyList()
        val mes = YearMonth.from(hoje)
        return cultivo.eventosSazonais.mapIndexedNotNull { i, e ->
            // O mês entra na chave: a poda de julho de 2026 feita não vale para 2027.
            val chave = "sazonal:$i:$mes"
            if (hoje.monthValue !in e.meses || chave in ultimaAcaoPorChave) return@mapIndexedNotNull null
            Pendencia(
                chave = chave,
                origem = Origem.SAZONAL,
                titulo = e.titulo,
                instrucoes = e.acaoRequerida,
                contexto = e.mensagem,
                tipo = null,
                critica = false,
                venceEm = inicioDoMes,
                diasDeAtraso = ChronoUnit.DAYS.between(inicioDoMes, hoje).toInt(),
            )
        }
    }
}
