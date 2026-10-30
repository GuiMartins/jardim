package com.jardim.ui.novaplanta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jardim.JardimApp
import com.jardim.avisos.Preferencias
import com.jardim.dados.Catalogo
import com.jardim.dados.Miniaturas
import com.jardim.motor.catalogo.Descoberta
import com.jardim.motor.catalogo.FiltroDescoberta
import com.jardim.motor.catalogo.descobrir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface EstadoDescoberta {
    data object Carregando : EstadoDescoberta

    data class Pronto(val filtro: FiltroDescoberta, val calendarioVale: Boolean, val descoberta: Descoberta) : EstadoDescoberta
}

class DescobrirViewModel(
    catalogo: Catalogo,
    private val preferencias: Preferencias,
    val miniaturas: Miniaturas,
) : ViewModel() {

    // As categorias valem só enquanto a tela está aberta; o resto fica no DataStore.
    private val categorias = MutableStateFlow(emptySet<String>())

    val estado: StateFlow<EstadoDescoberta> =
        combine(
            flow { emit(catalogo.itens) }.flowOn(Dispatchers.IO),
            preferencias.descoberta,
            categorias,
            preferencias.calendarioVale,
        ) { itens, guardado, categorias, calendarioVale ->
            val filtro = guardado.copy(categorias = categorias)
            EstadoDescoberta.Pronto(filtro, calendarioVale, descobrir(itens, filtro, LocalDate.now(), calendarioVale))
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EstadoDescoberta.Carregando)

    fun mudar(filtro: FiltroDescoberta) {
        categorias.value = filtro.categorias
        viewModelScope.launch { preferencias.guardarDescoberta(filtro) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JardimApp
                DescobrirViewModel(app.catalogo, app.preferencias, app.miniaturas)
            }
        }
    }
}
