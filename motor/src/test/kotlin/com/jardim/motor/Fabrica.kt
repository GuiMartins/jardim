package com.jardim.motor

import com.jardim.motor.agenda.RegistroAcao
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.modelo.Alerta
import com.jardim.motor.modelo.Dificuldade
import com.jardim.motor.modelo.EventoSazonal
import com.jardim.motor.modelo.Fase
import com.jardim.motor.modelo.FormaInicio
import com.jardim.motor.modelo.Luminosidade
import com.jardim.motor.modelo.Planta
import com.jardim.motor.modelo.PlantaCultivo
import com.jardim.motor.modelo.Praga
import com.jardim.motor.modelo.RequisitosIniciais
import com.jardim.motor.modelo.Tarefa
import com.jardim.motor.modelo.TipoCiclo
import com.jardim.motor.modelo.TipoPraga
import com.jardim.motor.modelo.TipoTarefa
import java.io.File
import java.time.LocalDate

// Monta PlantaCultivo mínimos para os testes: cada teste declara só a tarefa,
// alerta ou evento que interessa, e o resto vem preenchido e válido (o
// ValidadorCultivoTest confere que as fábricas passam no validador).

val PLANTIO: LocalDate = LocalDate.of(2026, 3, 1)

/** Data do dia N do ciclo de um plantio feito em PLANTIO. */
fun dia(n: Int): LocalDate = PLANTIO.plusDays(n - 1L)

fun tarefa(
    id: String = "rega",
    tipo: TipoTarefa = TipoTarefa.REGA,
    frequencia: Int = 2,
    inicio: Int = 1,
    fim: Int? = 60,
    critica: Boolean = true,
) = Tarefa(id, "Título de $id", tipo, frequencia, inicio, fim, "Instruções de $id", critica)

fun fase(id: String, inicio: Int, fim: Int?) = Fase(id, "Fase $id", inicio, fim, "Descrição de $id")

fun alerta(dia: Int) = Alerta(dia, "Alerta do dia $dia", "Mensagem do dia $dia", "Ação do dia $dia")

fun evento(vararg meses: Int) = EventoSazonal(meses.toList(), "Evento ${meses.toList()}", "Mensagem", "Ação")

fun anual(
    duracao: Int = 60,
    tarefas: List<Tarefa> = listOf(tarefa(fim = duracao)),
    alertas: List<Alerta> = emptyList(),
    eventos: List<EventoSazonal> = emptyList(),
    fases: List<Fase> = listOf(fase("unica", 1, duracao)),
) = cultivo(TipoCiclo.ANUAL, duracao, fases, tarefas, alertas, eventos)

fun perene(
    tarefas: List<Tarefa> = listOf(tarefa(fim = null)),
    alertas: List<Alerta> = emptyList(),
    eventos: List<EventoSazonal> = emptyList(),
    fases: List<Fase> = listOf(fase("adaptacao", 1, 30), fase("adulta", 31, null)),
) = cultivo(TipoCiclo.PERENE, null, fases, tarefas, alertas, eventos)

private fun cultivo(
    tipoCiclo: TipoCiclo,
    duracao: Int?,
    fases: List<Fase>,
    tarefas: List<Tarefa>,
    alertas: List<Alerta>,
    eventos: List<EventoSazonal>,
) = PlantaCultivo(
    planta = Planta(
        "Planta de teste", "Plantae testis", "Só existe nos testes.",
        tipoCiclo, Dificuldade.FACIL, FormaInicio.MUDA, duracao, (1..12).toList(),
    ),
    requisitosIniciais = RequisitosIniciais(
        "Vaso de 20 cm com furos", 5.0, Luminosidade.SOL_DIRETO, 4.0, 6.0, "Terra e húmus", true,
    ),
    fasesCicloVida = fases,
    tarefasAgendadas = tarefas,
    alertasFase = alertas,
    pragasComuns = listOf(Praga("Pulgão", TipoPraga.PRAGA, "Insetos no verso", "Jato de água")),
    eventosSazonais = eventos,
)

fun feito(chave: String, em: LocalDate, status: StatusAcao = StatusAcao.CONCLUIDA) =
    RegistroAcao(chave, em, em.atTime(9, 0), status)

/** Arquivo do catálogo que vai no APK (app/src/main/assets/catalogo). */
fun arquivoDoCatalogo(nome: String): File = File(diretorioDoCatalogo(), nome)

fun diretorioDoCatalogo(): File = File(
    checkNotNull(System.getProperty("catalogo.dir")) { "rode pelo Gradle: ele passa o catalogo.dir" },
)
