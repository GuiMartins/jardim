package com.jardim.ui.novaplanta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jardim.JardimApp
import com.jardim.avisos.Preferencias
import com.jardim.dados.Catalogo
import com.jardim.dados.Especie
import com.jardim.dados.JardimRepositorio
import com.jardim.dados.Miniaturas
import com.jardim.dados.Planejado
import com.jardim.motor.agenda.ItemPreparo
import com.jardim.motor.agenda.Local
import com.jardim.motor.catalogo.ResultadoBusca
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

sealed interface EstadoBusca {
    data object Carregando : EstadoBusca

    data class Pronto(val total: Int, val consulta: String, val resultados: List<ResultadoBusca>) : EstadoBusca
}

class NovaPlantaViewModel(
    private val catalogo: Catalogo,
    private val repositorio: JardimRepositorio,
    preferencias: Preferencias,
    val miniaturas: Miniaturas,
) : ViewModel() {

    /** Fora do Centro-Sul, a tela não diz "fora de época" (Preferencias.calendarioVale). */
    val calendarioVale: StateFlow<Boolean> = preferencias.calendarioVale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val consulta = MutableStateFlow("")

    /**
     * Cada letra digitada refaz a busca fora da main thread; mapLatest descarta
     * a busca anterior se ainda estiver rodando quando chega a próxima letra.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val estado: StateFlow<EstadoBusca> =
        flow { emit(catalogo.busca) } // lê o índice na primeira vez (Dispatchers.IO, abaixo)
            .flowOn(Dispatchers.IO)
            .flatMapLatest { busca -> consulta.mapLatest { q -> EstadoBusca.Pronto(busca.total, q, busca.buscar(q)) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EstadoBusca.Carregando)

    fun buscar(texto: String) {
        consulta.value = texto
    }

    suspend fun carregar(arquivo: String): Especie? = withContext(Dispatchers.IO) { catalogo.especie(arquivo) }

    fun planejado(id: Long): Flow<Planejado?> = repositorio.planejados.map { lista -> lista.firstOrNull { it.id == id } }

    // As gravações rodam no viewModelScope: sair da tela no meio não as cancela.

    fun plantar(especie: Especie, apelido: String, dataPlantio: LocalDate, local: Local, planejadoId: Long?, depois: () -> Unit) {
        viewModelScope.launch {
            val nome = apelido.trim().ifEmpty { especie.cultivo.planta.nomeComum }
            repositorio.plantar(especie, nome, dataPlantio, local, planejadoId)
            depois()
        }
    }

    fun planejar(especie: Especie, faltam: Set<ItemPreparo>, local: Local?, depois: () -> Unit) {
        viewModelScope.launch {
            repositorio.planejar(especie, faltam, local, LocalDate.now())
            depois()
        }
    }

    fun mudarPreparo(planejadoId: Long, faltam: Set<ItemPreparo>, local: Local?) {
        viewModelScope.launch { repositorio.mudarPreparo(planejadoId, faltam, local) }
    }

    fun desistir(planejadoId: Long, depois: () -> Unit) {
        viewModelScope.launch {
            repositorio.desistir(planejadoId)
            depois()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JardimApp
                NovaPlantaViewModel(app.catalogo, app.repositorio, app.preferencias, app.miniaturas)
            }
        }
    }
}
