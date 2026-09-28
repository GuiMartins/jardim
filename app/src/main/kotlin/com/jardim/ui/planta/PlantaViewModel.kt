package com.jardim.ui.planta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jardim.JardimApp
import kotlinx.coroutines.launch
import com.jardim.motor.agenda.Local
import com.jardim.avisos.Preferencias
import com.jardim.dados.JardimRepositorio
import com.jardim.dados.PlantioAtivo
import com.jardim.motor.agenda.DiaPrevisto
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.modelo.Fase
import com.jardim.motor.modelo.Praga
import com.jardim.ui.icone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime

enum class Situacao { PASSADA, ATUAL, FUTURA }

data class FaseNaLinha(val fase: Fase, val inicio: LocalDate, val fim: LocalDate?, val situacao: Situacao)

data class ItemHistorico(val icone: String, val titulo: String, val realizadoEm: LocalDateTime, val status: StatusAcao)

data class EstadoPlanta(
    val apelido: String,
    val nomeComum: String,
    val nomeCientifico: String,
    val hoje: LocalDate,
    val dia: Int,
    /** null para perene: não há barra de progresso. */
    val duracao: Int?,
    val cicloEncerrado: Boolean,
    val faseAtual: Fase?,
    val fases: List<FaseNaLinha>,
    val proximos: List<DiaPrevisto>,
    val problemas: List<Praga>,
    val historico: List<ItemHistorico>,
    /** null nos plantios de antes da pergunta: a tela pede a resposta. */
    val local: Local?,
    val minimaC: Int,
    val maximaC: Int,
    /** Sem cidade não há previsão, e a tela avisa onde escolher. */
    val temCidade: Boolean,
)

sealed interface TelaPlanta {
    data object Carregando : TelaPlanta

    /** Encerrada (ou apagada) enquanto a tela estava aberta: a tela volta sozinha. */
    data object Sumiu : TelaPlanta

    data class Pronta(val estado: EstadoPlanta) : TelaPlanta
}

class PlantaViewModel(
    private val repositorio: JardimRepositorio,
    preferencias: Preferencias,
    private val plantioId: Long,
) : ViewModel() {

    private val hoje = MutableStateFlow(LocalDate.now())

    val tela: StateFlow<TelaPlanta> =
        combine(repositorio.plantiosAtivos, hoje, preferencias.cidade) { plantios, dia, cidade ->
            plantios.firstOrNull { it.id == plantioId }?.let { TelaPlanta.Pronta(it.estado(dia, cidade != null)) } ?: TelaPlanta.Sumiu
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TelaPlanta.Carregando)

    fun atualizarDia() {
        hoje.value = LocalDate.now()
    }

    fun mudarLocal(local: Local) {
        viewModelScope.launch { repositorio.mudarLocal(plantioId, local) }
    }

    private fun PlantioAtivo.estado(hoje: LocalDate, temCidade: Boolean): EstadoPlanta {
        val cronograma = cronograma()
        val dia = cronograma.diaDoCiclo(hoje)
        return EstadoPlanta(
            apelido = apelido,
            nomeComum = cultivo.planta.nomeComum,
            nomeCientifico = cultivo.planta.nomeCientifico,
            hoje = hoje,
            dia = dia,
            duracao = cultivo.planta.duracaoCicloDias,
            cicloEncerrado = cronograma.cicloEncerradoEm(hoje),
            faseAtual = cronograma.faseEm(hoje),
            fases = cultivo.fasesCicloVida.map { fase ->
                val fim = fase.diaFim
                val situacao = when {
                    fim != null && dia > fim -> Situacao.PASSADA
                    dia >= fase.diaInicio -> Situacao.ATUAL
                    else -> Situacao.FUTURA
                }
                FaseNaLinha(fase, cronograma.dataDoDia(fase.diaInicio), fim?.let(cronograma::dataDoDia), situacao)
            },
            proximos = cronograma.proximosDias(hoje),
            problemas = cultivo.pragasComuns,
            // Inclui o que foi feito antes de um "Ressemeei": a agenda ignora,
            // mas o histórico é da planta, não do ciclo.
            historico = registros.sortedByDescending { it.realizadoEm }.map { r ->
                val quem = cronograma.identificar(r.chave)
                ItemHistorico(
                    icone = quem?.let { icone(it.origem, it.tipo, it.risco) } ?: "•",
                    titulo = quem?.titulo ?: r.chave,
                    realizadoEm = r.realizadoEm,
                    status = r.status,
                )
            },
            local = local,
            minimaC = cultivo.planta.temperaturaMinimaC,
            maximaC = cultivo.planta.temperaturaMaximaC,
            temCidade = temCidade,
        )
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JardimApp
                val id = checkNotNull(createSavedStateHandle().get<String>("id")).toLong()
                PlantaViewModel(app.repositorio, app.preferencias, id)
            }
        }
    }
}
