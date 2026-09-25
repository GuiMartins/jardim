package com.jardim.ui.novaplanta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jardim.JardimApp
import com.jardim.dados.Catalogo
import com.jardim.dados.Especie
import com.jardim.dados.JardimRepositorio
import kotlinx.coroutines.launch
import java.time.LocalDate

class NovaPlantaViewModel(
    private val catalogo: Catalogo,
    private val repositorio: JardimRepositorio,
) : ViewModel() {

    val especies: List<Especie> get() = catalogo.especies

    fun especie(arquivo: String): Especie? = catalogo.especie(arquivo)

    /** Grava no viewModelScope: sair da tela no meio não cancela o plantio. */
    fun plantar(especie: Especie, apelido: String, dataPlantio: LocalDate, depois: () -> Unit) {
        viewModelScope.launch {
            repositorio.plantar(especie, apelido.trim().ifEmpty { especie.cultivo.planta.nomeComum }, dataPlantio)
            depois()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JardimApp
                NovaPlantaViewModel(app.catalogo, app.repositorio)
            }
        }
    }
}
