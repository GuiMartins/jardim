package com.jardim.ui.despensa

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jardim.JardimApp
import com.jardim.dados.Despensa
import com.jardim.motor.agenda.Insumo
import com.jardim.ui.GRUPOS_DA_DESPENSA
import com.jardim.ui.nomeDoInsumo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DespensaViewModel(private val despensa: Despensa) : ViewModel() {

    /** null enquanto o DataStore não respondeu, para as caixas não piscarem vazias. */
    val itens: StateFlow<Set<Insumo>?> = despensa.itens.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun mudar(insumo: Insumo, tem: Boolean) {
        viewModelScope.launch { despensa.mudar(insumo, tem) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { DespensaViewModel((this[APPLICATION_KEY] as JardimApp).despensa) }
        }
    }
}

/** Minha despensa: uma caixa por insumo, sem quantidade (tem ou não tem). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DespensaTela(aoVoltar: () -> Unit, viewModel: DespensaViewModel = viewModel(factory = DespensaViewModel.Factory)) {
    val itens by viewModel.itens.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Minha despensa") },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
    ) { padding ->
        val tenho = itens ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "explicacao") {
                Text(
                    "Marque o que você tem em casa. O preparo de cada planta e a lista de compras descontam o que já está aqui.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            GRUPOS_DA_DESPENSA.forEach { (titulo, insumos) ->
                item(key = titulo) {
                    Text(
                        titulo,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(insumos, key = { it.name }) { insumo ->
                    val tem = insumo in tenho
                    ListItem(
                        leadingContent = { Checkbox(checked = tem, onCheckedChange = null) },
                        headlineContent = { Text(nomeDoInsumo(insumo)) },
                        modifier = Modifier.toggleable(value = tem, role = Role.Checkbox) { viewModel.mudar(insumo, it) },
                    )
                }
            }
        }
    }
}
