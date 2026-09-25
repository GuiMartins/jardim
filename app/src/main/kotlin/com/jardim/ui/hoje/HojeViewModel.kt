package com.jardim.ui.hoje

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jardim.JardimApp
import com.jardim.avisos.Avisos
import com.jardim.dados.JardimRepositorio
import com.jardim.dados.PlantioAtivo
import com.jardim.motor.agenda.Pendencia
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.modelo.FormaInicio
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Um plantio como a tela "Hoje" mostra. */
data class CartaoPlantio(
    val id: Long,
    val apelido: String,
    val nomeComum: String,
    /** Decide o texto do menu: "Ressemeei" para semente, "Replantei" para o resto. */
    val deSemente: Boolean,
    val dia: Int,
    val fase: String?,
    val cicloEncerrado: Boolean,
    val pendencias: List<Pendencia>,
)

/** Uma ação recém-registrada, que a snackbar oferece desfazer. */
data class AcaoRegistrada(val registroId: Long, val mensagem: String)

class HojeViewModel(
    private val repositorio: JardimRepositorio,
    private val avisos: Avisos,
) : ViewModel() {

    private val hoje = MutableStateFlow(LocalDate.now())
    val dataDeHoje: StateFlow<LocalDate> = hoje

    /** null enquanto o banco não respondeu, para não piscar a tela de "sem plantas". */
    val cartoes: StateFlow<List<CartaoPlantio>?> =
        combine(repositorio.plantiosAtivos, hoje) { plantios, dia -> plantios.map { it.cartao(dia) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val registradas = Channel<AcaoRegistrada>(Channel.BUFFERED)
    val acoesRegistradas: Flow<AcaoRegistrada> = registradas.receiveAsFlow()

    /** Ao voltar para o app: quem deixou aberto de um dia para o outro vê o dia novo. */
    fun atualizarDia() {
        hoje.value = LocalDate.now()
    }

    fun marcar(plantioId: Long, pendencia: Pendencia, status: StatusAcao) {
        viewModelScope.launch {
            val id = repositorio.registrar(plantioId, pendencia, status)
            // Marcou no app: a notificação da mesma pendência não pode ficar lá cobrando.
            avisos.cancelar(plantioId, pendencia.chave)
            val como = if (status == StatusAcao.FEITA) "feito" else "conferido"
            registradas.send(AcaoRegistrada(id, "${pendencia.titulo}: $como"))
        }
    }

    fun notificacoesLigadas(): Boolean = avisos.ligadas()

    fun desfazer(registroId: Long) {
        viewModelScope.launch { repositorio.desfazer(registroId) }
    }

    fun recomecar(plantioId: Long) {
        viewModelScope.launch { repositorio.recomecar(plantioId, hoje.value) }
    }

    fun encerrar(plantioId: Long) {
        viewModelScope.launch { repositorio.encerrar(plantioId, hoje.value) }
    }

    private fun PlantioAtivo.cartao(dia: LocalDate): CartaoPlantio {
        val cronograma = cronograma()
        return CartaoPlantio(
            id = id,
            apelido = apelido,
            nomeComum = cultivo.planta.nomeComum,
            deSemente = cultivo.planta.formaInicio == FormaInicio.SEMENTE,
            dia = cronograma.diaDoCiclo(dia),
            fase = cronograma.faseEm(dia)?.nome,
            cicloEncerrado = cronograma.cicloEncerradoEm(dia),
            pendencias = cronograma.pendenciasEm(dia),
        )
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JardimApp
                HojeViewModel(app.repositorio, app.avisos)
            }
        }
    }
}
